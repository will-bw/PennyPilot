package com.pennypilot.app.data.recognition

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecognitionTaskStoreTest {
    private val file = File.createTempFile("recognition", ".enc").apply { delete(); deleteOnExit() }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = object : TaskCipher {
        override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key); iv + doFinal(bytes) }
        override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); doFinal(bytes.copyOfRange(12, bytes.size)) }
    }
    private fun task(id: String = "a", now: Long = System.currentTimeMillis()) = RecognitionTask(id, "event-$id", "com.tencent.mm", "转账成功 ¥18.00 小林", now, 7, "config-A", "ACCESSIBILITY", createdAt = now)
    @Test fun `encrypted tasks survive recreation without plaintext and retry attempts persist`() {
        val store = RecognitionTaskStore(file, cipher)
        assertEquals(RecognitionTaskStore.EnqueueResult.ENQUEUED, store.enqueue(task()))
        assertFalse(file.readText().contains("小林")); assertFalse(file.readText().contains("config-A"))
        store.incrementAttempts("a")
        val restored = RecognitionTaskStore(file, cipher).get("a")!!
        assertEquals(1, restored.attempts); assertEquals(7, restored.ledgerRevision); assertEquals("config-A", restored.configurationId)
        store.remove("a"); assertTrue(RecognitionTaskStore(file, cipher).pending().isEmpty())
    }
    @Test fun `same logical event retains first identity and cannot reset retries`() {
        val store = RecognitionTaskStore(file, cipher); store.enqueue(task()); store.incrementAttempts("a")
        assertEquals(RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED, store.enqueue(task().copy(configurationId = "new", text = "转账成功19元")))
        assertEquals("config-A", store.get("a")!!.configurationId); assertEquals(1, store.get("a")!!.attempts)
    }
    @Test fun `queue full is explicit existing tasks are not evicted`() {
        val store = RecognitionTaskStore(file, cipher)
        repeat(RecognitionTaskStore.MAX_TASKS) { assertEquals(RecognitionTaskStore.EnqueueResult.ENQUEUED, store.enqueue(task(it.toString()))) }
        assertEquals(RecognitionTaskStore.EnqueueResult.FULL, store.enqueue(task("extra")))
        assertEquals(RecognitionTaskStore.MAX_TASKS, store.pending().size)
    }
    @Test fun `expired entries deleted and tampering never returns a partial task`() {
        val store = RecognitionTaskStore(file, cipher); val now = System.currentTimeMillis(); store.enqueue(task(now = now), now)
        assertTrue(store.pending(now + RecognitionTaskStore.RETENTION_MS + 1).isEmpty()); assertFalse(file.exists())
        store.enqueue(task()); val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        try { store.pending(); fail("tampered ciphertext") } catch (_: Exception) { }
    }
    @Test fun `restoration and deletion are idempotent`() {
        val store = RecognitionTaskStore(file, cipher); store.enqueue(task()); store.clear(); store.clear()
        assertNull(RecognitionTaskStore(file, cipher).get("a"))
        assertEquals(RecognitionTaskStore.id("event"), RecognitionTaskStore.id("event"))
        assertNotEquals(RecognitionTaskStore.id("event"), RecognitionTaskStore.id("other"))
    }
    @Test fun `new evidence replaces queued version and old deletion cannot drop it`() {
        val store = RecognitionTaskStore(file, cipher); store.enqueue(task()); store.incrementAttempts("a")
        val original = store.get("a")!!
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED, store.enqueue(original.copy(text = original.text + "\n订单号：20261024000001")))
        assertEquals(2, store.get("a")!!.evidenceVersion); assertEquals(original.createdAt, store.get("a")!!.createdAt)
        store.remove("a", 1); assertNotNull(store.get("a"))
        store.remove("a", 2); assertNull(store.get("a"))
    }

    @Test fun `structured evidence survives encrypted restoration and changes version`() {
        val store = RecognitionTaskStore(file, cipher)
        val structure = """[{"id":"n1","text":"转账成功"}]"""
        store.enqueue(task().copy(structure = structure))
        assertEquals(structure, RecognitionTaskStore(file, cipher).get("a")!!.structure)
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED, store.enqueue(task().copy(structure = structure + " ")))
        assertEquals(2, store.get("a")!!.evidenceVersion)
        try { store.enqueue(task("oversized").copy(structure = "x".repeat(32_769))); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun `richer semantic snapshot changes version even when financial text is unchanged`() {
        val store = RecognitionTaskStore(file, cipher)
        val first = task().copy(receipt = CaptureSemanticReceipt(details = mapOf("merchantName" to "示例商户")), semanticFingerprint = "first")
        store.enqueue(first)
        val rich = first.copy(receipt = first.receipt!!.copy(context = listOf(
            com.pennypilot.app.data.parser.PaymentReceiptContext("门店", "示例广场店"))), semanticFingerprint = "rich")
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED, store.enqueue(rich))
        assertEquals(2, store.get("a")!!.evidenceVersion)
        assertEquals(rich.receipt, RecognitionTaskStore(file, cipher).get("a")!!.receipt)
        assertFalse(file.readText().contains("示例广场店"))
        assertEquals(RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED, store.enqueue(rich))
        store.remove("a", 1)
        assertEquals("rich", store.get("a")!!.semanticFingerprint)
    }

    @Test fun `unrecognized receipt ages into review and expires after seven days`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        store.enqueue(task(now = now), now)
        val afterAutomaticWindow = now + RecognitionTaskStore.AUTO_RETRY_WINDOW_MS + 1
        assertTrue(store.pending(afterAutomaticWindow).isEmpty())
        val failed = store.entries(afterAutomaticWindow).single()
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, failed.state)
        assertEquals(RecognitionFailure.EXPIRED, failed.failure)
        assertEquals(2, failed.evidenceVersion)
        assertEquals(now, failed.createdAt)
        assertEquals(failed, RecognitionTaskStore(file, cipher).entries(afterAutomaticWindow).single())
        assertTrue(store.entries(now + RecognitionTaskStore.RETENTION_MS + 1).isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `failed receipt is retained without resetting through repeated capture or stale retry`() {
        val store = RecognitionTaskStore(file, cipher)
        val original = task()
        store.enqueue(original)
        store.needsAttention("a", 1, RecognitionFailure.NETWORK)
        assertEquals(RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED, store.enqueue(original))
        assertTrue(store.pending().isEmpty())
        assertEquals(RecognitionFailure.NETWORK, store.get("a")!!.failure)
        assertNull(store.retry("a", 0))
        val retried = store.retry("a", 1)!!
        assertEquals(RecognitionTaskState.QUEUED, retried.state)
        assertEquals(2, retried.evidenceVersion)
        assertEquals(original.createdAt, retried.createdAt)
        assertNull(store.retry("a", 1))
        store.needsAttention("a", 1, RecognitionFailure.TIMEOUT)
        assertEquals(retried, store.get("a"))
    }

    @Test fun `retry does not extend retained sensitive evidence lifetime`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        store.enqueue(task(now = now), now)
        store.needsAttention("a", 1, RecognitionFailure.NETWORK)
        val retried = store.retry("a", 1, now + 6 * 24 * 60 * 60 * 1000L)!!
        assertEquals(now, retried.createdAt)
        assertTrue(store.entries(now + RecognitionTaskStore.RETENTION_MS + 1).isEmpty())
    }

    @Test fun `saved receipt enrichment failure never creates unrecognized entry`() {
        val store = RecognitionTaskStore(file, cipher)
        val saved = task().copy(receipt = CaptureSemanticReceipt(), semanticFingerprint = "verified")
        store.enqueue(saved)
        store.needsAttention("a", 1, RecognitionFailure.NETWORK)
        assertNull(store.get("a"))
        store.enqueue(saved)
        assertTrue(store.entries(saved.createdAt + RecognitionTaskStore.AUTO_RETRY_WINDOW_MS + 1).isEmpty())
    }

    @Test fun `fresh capture under new grant replaces all old material and invalidates in flight response`() {
        val store = RecognitionTaskStore(file, cipher)
        val old = task().copy(text = "支付成功18元旧商户", structure = "old-node-tree", authorizationId = "0:0:0")
        store.enqueue(old)
        store.incrementAttempts(old.id)
        val fresh = task().copy(text = "支付成功20元新商户", authorizationId = "1:0:0", configurationId = "new-model")
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED, store.enqueue(fresh))
        val replacement = RecognitionTaskStore(file, cipher).get(old.id)!!
        assertEquals(fresh.text, replacement.text)
        assertNull(replacement.structure)
        assertEquals(fresh.configurationId, replacement.configurationId)
        assertEquals("1:0:0", replacement.authorizationId)
        assertEquals(fresh.createdAt, replacement.createdAt)
        assertEquals(0, replacement.attempts)
        assertEquals(2, replacement.evidenceVersion)
        store.remove(old.id, old.evidenceVersion)
        assertNotNull(store.get(old.id))
    }

    @Test fun `new image frame supersedes old pixels without resetting attempts or expiry`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        val original = task(now = now).copy(images = listOf("data:image/jpeg;base64,b2xk"),
            receiptImageMode = ReceiptImageMode.IMAGE_ONLY)
        store.enqueue(original, now)
        store.incrementAttempts(original.id)
        store.incrementAttempts(original.id)
        assertEquals(RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED, store.enqueue(original, now + 100))
        val fresh = original.copy(images = listOf("data:image/jpeg;base64,bmV3"), createdAt = now + 200)
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED, store.enqueue(fresh, now + 200))
        val replaced = RecognitionTaskStore(file, cipher).entries(now + 200).single()
        assertEquals(fresh.images, replaced.images)
        assertEquals(2, replaced.evidenceVersion)
        assertEquals(2, replaced.attempts)
        assertEquals(now, replaced.createdAt)
        assertEquals(now, replaced.queuedAt)
        store.remove(original.id, original.evidenceVersion)
        assertEquals(replaced, store.entries(now + 200).single())
        val expired = store.entries(now + RecognitionTaskStore.AUTO_RETRY_WINDOW_MS + 1).single()
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, expired.state)
        assertEquals(RecognitionFailure.EXPIRED, expired.failure)
    }

    @Test fun `new frames update paused preview but require explicit retry to resume cloud calls`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        val original = task(now = now).copy(images = listOf("data:image/jpeg;base64,b2xk"),
            receiptImageMode = ReceiptImageMode.IMAGE_ONLY)
        store.enqueue(original, now)
        repeat(3) { store.incrementAttempts(original.id) }
        store.needsAttention(original.id, original.evidenceVersion, RecognitionFailure.NETWORK)
        listOf("bmV3", "bW9yZQ==", "bGFzdA==").forEachIndexed { index, pixels ->
            assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED,
                store.enqueue(original.copy(images = listOf("data:image/jpeg;base64,$pixels")), now + index + 1))
            val paused = store.entries(now + index + 1).single()
            assertEquals(3, paused.attempts)
            assertEquals(RecognitionTaskState.NEEDS_ATTENTION, paused.state)
            assertEquals(RecognitionFailure.NETWORK, paused.failure)
            assertEquals(now, paused.createdAt)
            assertEquals(now, paused.queuedAt)
            assertTrue(store.pending(now + index + 1).isEmpty())
        }
        assertNull(store.retry(original.id, original.evidenceVersion, now + 10))
        val retried = store.retry(original.id, 4, now + 10)!!
        assertEquals(0, retried.attempts)
        assertEquals(RecognitionTaskState.QUEUED, retried.state)
        assertEquals(now + 10, retried.queuedAt)
        assertEquals(now, retried.createdAt)
        assertEquals(listOf("data:image/jpeg;base64,bGFzdA=="), retried.images)
    }

    @Test fun `new image can complete an incomplete frame within the original retry budget`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        val original = task(now = now).copy(images = listOf("data:image/jpeg;base64,b2xk"),
            receiptImageMode = ReceiptImageMode.IMAGE_ONLY)
        store.enqueue(original, now)
        store.incrementAttempts(original.id)
        store.needsAttention(original.id, 1, RecognitionFailure.INCOMPLETE)
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED,
            store.enqueue(original.copy(images = listOf("data:image/jpeg;base64,bmV3")), now + 100))
        val resumed = store.entries(now + 100).single()
        assertEquals(RecognitionTaskState.QUEUED, resumed.state)
        assertNull(resumed.failure)
        assertEquals(1, resumed.attempts)
        assertEquals(now, resumed.createdAt)
        assertEquals(now, resumed.queuedAt)
        repeat(2) { store.incrementAttempts(original.id, resumed.evidenceVersion) }
        store.needsAttention(original.id, resumed.evidenceVersion, RecognitionFailure.INCOMPLETE)
        store.enqueue(original.copy(images = listOf("data:image/jpeg;base64,bGFzdA==")), now + 200)
        val exhausted = store.entries(now + 200).single()
        assertEquals(3, exhausted.attempts)
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, exhausted.state)
        assertEquals(RecognitionFailure.INCOMPLETE, exhausted.failure)
    }

    @Test fun `new image cannot resume incomplete recognition beyond original automatic window`() {
        val store = RecognitionTaskStore(file, cipher)
        val now = System.currentTimeMillis()
        val original = task(now = now).copy(images = listOf("data:image/jpeg;base64,b2xk"),
            receiptImageMode = ReceiptImageMode.IMAGE_ONLY)
        store.enqueue(original, now)
        store.incrementAttempts(original.id)
        store.needsAttention(original.id, 1, RecognitionFailure.INCOMPLETE)
        val later = now + RecognitionTaskStore.AUTO_RETRY_WINDOW_MS + 1
        store.enqueue(original.copy(images = listOf("data:image/jpeg;base64,bmV3")), later)
        val expired = store.entries(later).single()
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, expired.state)
        assertEquals(1, expired.attempts)
        assertEquals(now, expired.queuedAt)
        assertTrue(store.pending(later).isEmpty())
    }

}

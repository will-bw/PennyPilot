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

}

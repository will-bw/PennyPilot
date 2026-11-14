package com.pennypilot.app.data.recognition

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
enum class ReceiptImageMode { VERIFIED_RECEIPT, IMAGE_ONLY }

@Serializable
enum class RecognitionTaskState { QUEUED, NEEDS_ATTENTION }

@Serializable
enum class RecognitionFailure { NETWORK, TIMEOUT, INCOMPLETE, EXPIRED, SAVE_FAILED }

@Serializable
data class RecognitionTask(
    val id: String,
    val eventKey: String,
    val packageName: String,
    val text: String,
    val observedAt: Long,
    val ledgerRevision: Long,
    val configurationId: String,
    val source: String,
    val notificationKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val images: List<String> = emptyList(),
    val evidenceVersion: Int = 1,
    val structure: String? = null,
    val receipt: CaptureSemanticReceipt? = null,
    val semanticFingerprint: String? = null,
    val receiptImageMode: ReceiptImageMode? = null,
    val state: RecognitionTaskState = RecognitionTaskState.QUEUED,
    val failure: RecognitionFailure? = null,
    val queuedAt: Long = createdAt,
    val authorizationId: String = "0:0:0"
) {
    /** Already saved receipts do not become a second, apparently missing transaction. */
    val isUnrecognized: Boolean get() = semanticFingerprint == null && receiptImageMode != ReceiptImageMode.VERIFIED_RECEIPT
}

internal interface TaskCipher { fun encrypt(bytes: ByteArray): ByteArray; fun decrypt(bytes: ByteArray): ByteArray }
private class AndroidTaskCipher : TaskCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("pennypilot_recognition_queue", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pennypilot_recognition_queue", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(bytes)
    }
    override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(bytes.size > 28)
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

/** Bounded, authenticated, atomic, no-backup storage. WorkManager only receives opaque IDs. */
class RecognitionTaskStore internal constructor(private val file: File, private val cipher: TaskCipher) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "recognition-tasks.enc"), AndroidTaskCipher())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(RecognitionTask.serializer())
    enum class EnqueueResult { ENQUEUED, UPDATED, ALREADY_QUEUED, FULL }

    fun enqueue(task: RecognitionTask, now: Long = System.currentTimeMillis()): EnqueueResult = synchronized(lock) {
        require(task.text.length in 1..12_000 && task.eventKey.length in 1..512 && task.configurationId.isNotBlank() && (task.structure?.length ?: 0) <= 32_768)
        require(task.receipt == CaptureSemanticContext(receipt = task.receipt).bounded().receipt &&
            (task.semanticFingerprint?.length ?: 0) <= 128)
        com.pennypilot.app.data.llm.MultimodalMessages.validate(listOf(com.pennypilot.app.data.llm.Message("user", task.text, imageDataUrls = task.images)))
        require(task.receiptImageMode == null || task.images.size == 1 &&
            task.structure == null && task.semanticFingerprint == null && task.receipt == null)
        val tasks = retained(now)
        val existing = tasks.firstOrNull { it.id == task.id }
        if (existing != null) {
            if (existing.source != task.source || existing.ledgerRevision != task.ledgerRevision)
                return@synchronized EnqueueResult.ALREADY_QUEUED
        }
        if (existing != null && existing.authorizationId == task.authorizationId) {
            val upgradesToImage = task.images.isNotEmpty() && (existing.images.isEmpty() ||
                existing.receiptImageMode == null && task.receiptImageMode != null)
            if (existing.configurationId != task.configurationId && !upgradesToImage) return@synchronized EnqueueResult.ALREADY_QUEUED
            if (existing.receiptImageMode == ReceiptImageMode.VERIFIED_RECEIPT ||
                existing.receiptImageMode == ReceiptImageMode.IMAGE_ONLY && task.receiptImageMode == null)
                return@synchronized EnqueueResult.ALREADY_QUEUED
            if (existing.receiptImageMode == ReceiptImageMode.IMAGE_ONLY && task.receiptImageMode == ReceiptImageMode.IMAGE_ONLY &&
                existing.images == task.images) return@synchronized EnqueueResult.ALREADY_QUEUED
            if (existing.text == task.text && existing.images == task.images && existing.structure == task.structure &&
                existing.semanticFingerprint == task.semanticFingerprint && existing.receipt == task.receipt &&
                existing.receiptImageMode == task.receiptImageMode) return@synchronized EnqueueResult.ALREADY_QUEUED
        }
        val others = tasks.filterNot { it.id == task.id }
        if (others.size >= MAX_TASKS || others.sumOf(::characterCount) + characterCount(task) > MAX_CACHE_CHARACTERS) return@synchronized EnqueueResult.FULL
        if (existing != null) {
            if (existing.authorizationId != task.authorizationId) {
                // This is freshly captured material under the new grant, never a reauthorization
                // of the old image/text. In-flight responses cannot use this replacement's identity.
                write(others + task.copy(evidenceVersion = existing.evidenceVersion + 1, attempts = 0,
                    state = RecognitionTaskState.QUEUED, failure = null, queuedAt = now))
                return@synchronized EnqueueResult.UPDATED
            }
            // New evidence shares one logical event and original TTL; it cannot reset a live request's identity.
            val imageRefresh = existing.receiptImageMode == ReceiptImageMode.IMAGE_ONLY &&
                task.receiptImageMode == ReceiptImageMode.IMAGE_ONLY
            val resumeIncomplete = imageRefresh && existing.failure == RecognitionFailure.INCOMPLETE &&
                existing.attempts < MAX_AUTOMATIC_ATTEMPTS && now - existing.queuedAt in 0..AUTO_RETRY_WINDOW_MS
            write(others + existing.copy(text = task.text, images = task.images, structure = task.structure,
                receipt = task.receipt, semanticFingerprint = task.semanticFingerprint,
                receiptImageMode = task.receiptImageMode, configurationId = task.configurationId,
                authorizationId = task.authorizationId,
                evidenceVersion = existing.evidenceVersion + 1,
                // Successive frames replace blurry pixels, not the event's retry budget or consent.
                attempts = if (imageRefresh) existing.attempts else 0,
                state = if (imageRefresh && !resumeIncomplete) existing.state else RecognitionTaskState.QUEUED,
                failure = if (imageRefresh && !resumeIncomplete) existing.failure else null,
                queuedAt = if (imageRefresh) existing.queuedAt else now))
            EnqueueResult.UPDATED
        } else { write(tasks + task); EnqueueResult.ENQUEUED }
    }
    fun entries(now: Long = System.currentTimeMillis()): List<RecognitionTask> = synchronized(lock) { retained(now) }
    fun pending(now: Long = System.currentTimeMillis()): List<RecognitionTask> =
        entries(now).filter { it.state == RecognitionTaskState.QUEUED }
    fun get(id: String): RecognitionTask? = entries().firstOrNull { it.id == id }
    fun remove(id: String, expectedVersion: Int? = null) = synchronized(lock) { write(read().filterNot { it.id == id && (expectedVersion == null || it.evidenceVersion == expectedVersion) }) }
    fun incrementAttempts(id: String, expectedVersion: Int? = null) = synchronized(lock) { write(read().map { if (it.id == id && (expectedVersion == null || it.evidenceVersion == expectedVersion)) it.copy(attempts = it.attempts + 1) else it }) }
    fun needsAttention(id: String, expectedVersion: Int, failure: RecognitionFailure) = synchronized(lock) {
        write(read().mapNotNull { task ->
            if (task.id != id || task.evidenceVersion != expectedVersion) task
            else if (!task.isUnrecognized) null
            else task.copy(state = RecognitionTaskState.NEEDS_ATTENTION, failure = failure)
        })
    }
    /** Only a deliberate user retry resets the budget; a repeated page event cannot do so. */
    fun retry(id: String, expectedVersion: Int, now: Long = System.currentTimeMillis()): RecognitionTask? = synchronized(lock) {
        val tasks = retained(now)
        val task = tasks.firstOrNull { it.id == id && it.evidenceVersion == expectedVersion &&
            it.state == RecognitionTaskState.NEEDS_ATTENTION && it.isUnrecognized } ?: return@synchronized null
        val retried = task.copy(state = RecognitionTaskState.QUEUED, failure = null, attempts = 0,
            queuedAt = now, evidenceVersion = task.evidenceVersion + 1)
        write(tasks.map { if (it.id == id) retried else it })
        retried
    }
    fun clear() = synchronized(lock) { AtomicFile(file).delete() }
    private fun characterCount(task: RecognitionTask): Int = task.text.length + task.images.sumOf(String::length) +
        (task.structure?.length ?: 0) + (task.receipt?.let { json.encodeToString(CaptureSemanticReceipt.serializer(), it).length } ?: 0)
    private fun retained(now: Long): List<RecognitionTask> {
        val all = read()
        val tasks = all.mapNotNull { task ->
            when {
                now - task.createdAt !in 0..RETENTION_MS -> null
                task.state == RecognitionTaskState.QUEUED && now - task.queuedAt > AUTO_RETRY_WINDOW_MS ->
                    if (task.isUnrecognized) task.copy(state = RecognitionTaskState.NEEDS_ATTENTION,
                        failure = RecognitionFailure.EXPIRED, evidenceVersion = task.evidenceVersion + 1) else null
                else -> task
            }
        }
        if (all != tasks) write(tasks)
        return tasks
    }
    private fun read(): List<RecognitionTask> {
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        require(file.length() <= 20 * 1024 * 1024) { "识别任务缓存损坏" }
        return json.decodeFromString(serializer, cipher.decrypt(AtomicFile(file).readFully()).toString(Charsets.UTF_8))
    }
    private fun write(tasks: List<RecognitionTask>) {
        if (tasks.isEmpty()) { AtomicFile(file).delete(); return }
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val bytes = cipher.encrypt(json.encodeToString(serializer, tasks).toByteArray())
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    companion object {
        private val lock = Any()
        const val MAX_TASKS = 64
        const val MAX_CACHE_CHARACTERS = 12 * 1024 * 1024
        const val MAX_AUTOMATIC_ATTEMPTS = 3
        const val AUTO_RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
        fun id(eventKey: String): String = MessageDigest.getInstance("SHA-256").digest(eventKey.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

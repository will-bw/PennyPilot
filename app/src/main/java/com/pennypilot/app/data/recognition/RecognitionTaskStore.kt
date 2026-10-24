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
    val structure: String? = null
)

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
        com.pennypilot.app.data.llm.MultimodalMessages.validate(listOf(com.pennypilot.app.data.llm.Message("user", task.text, imageDataUrls = task.images)))
        val tasks = read().filter { fresh(it, now) }
        val existing = tasks.firstOrNull { it.id == task.id }
        if (existing != null && ((existing.configurationId != task.configurationId && !(existing.images.isEmpty() && task.images.isNotEmpty())) || existing.ledgerRevision != task.ledgerRevision ||
                existing.source != task.source || existing.text == task.text && existing.images == task.images && existing.structure == task.structure)) return@synchronized EnqueueResult.ALREADY_QUEUED
        val others = tasks.filterNot { it.id == task.id }
        if (others.size >= MAX_TASKS || others.sumOf { it.text.length + it.images.sumOf(String::length) + (it.structure?.length ?: 0) } + task.text.length + task.images.sumOf(String::length) + (task.structure?.length ?: 0) > MAX_CACHE_CHARACTERS) return@synchronized EnqueueResult.FULL
        if (existing != null) {
            // New evidence shares one logical event and original TTL; it cannot reset a live request's identity.
            write(others + existing.copy(text = task.text, images = task.images, structure = task.structure, configurationId = task.configurationId, evidenceVersion = existing.evidenceVersion + 1, attempts = 0))
            EnqueueResult.UPDATED
        } else { write(tasks + task); EnqueueResult.ENQUEUED }
    }
    fun pending(now: Long = System.currentTimeMillis()): List<RecognitionTask> = synchronized(lock) {
        val all = read(); val tasks = all.filter { fresh(it, now) }
        if (all.size != tasks.size) write(tasks)
        tasks
    }
    fun get(id: String): RecognitionTask? = pending().firstOrNull { it.id == id }
    fun remove(id: String, expectedVersion: Int? = null) = synchronized(lock) { write(read().filterNot { it.id == id && (expectedVersion == null || it.evidenceVersion == expectedVersion) }) }
    fun incrementAttempts(id: String, expectedVersion: Int? = null) = synchronized(lock) { write(read().map { if (it.id == id && (expectedVersion == null || it.evidenceVersion == expectedVersion)) it.copy(attempts = it.attempts + 1) else it }) }
    fun clear() = synchronized(lock) { AtomicFile(file).delete() }
    private fun fresh(task: RecognitionTask, now: Long): Boolean = now - task.createdAt in 0..RETENTION_MS
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
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        fun id(eventKey: String): String = MessageDigest.getInstance("SHA-256").digest(eventKey.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

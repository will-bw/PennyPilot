package com.pennypilot.app.domain.usecase

import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupUseCase @Inject constructor(private val database: AppDatabase) {
    private val operationMutex = Mutex()

    suspend fun exportDb(context: Context, destination: Uri): Result<Unit> = onIo {
        operationMutex.withLock {
            val file = temporaryFile(context)
            try {
                exportToFile(context, file)
                val output = context.contentResolver.openOutputStream(destination, "wt")
                    ?: error("无法打开备份目标")
                output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            } finally {
                deleteTemporaryDatabase(file)
            }
        }
    }

    /** 旧版备份可同时选择 .db 和同名 -wal；新备份为独立的单个 .db。 */
    suspend fun restoreDb(context: Context, documents: List<Uri>): Result<Unit> = onIo {
        operationMutex.withLock {
            require(documents.isNotEmpty() && documents.size <= 3) { "请选择一个数据库备份及可选的同名日志" }
            val named = documents.map { uri -> documentName(context, uri) to uri }
            val databases = named.filter { (name, _) ->
                name.endsWith(".db", true) || name.endsWith(".sqlite", true) || name.endsWith(".sqlite3", true)
            }
            require(databases.size == 1) { "请选择一个 .db 数据库备份" }
            val (name, uri) = databases.single()
            require(named.map { it.first }.distinct().size == named.size &&
                named.all { it.first in setOf(name, "$name-wal", "$name-shm") }) {
                "日志必须与所选数据库同名"
            }
            val file = temporaryFile(context)
            try {
                copyDocument(context, uri, file)
                named.firstOrNull { it.first == "$name-wal" }?.let {
                    copyDocument(context, it.second, File(file.path + "-wal"))
                }
                // SHM 是可重建的索引，不导入其他进程留下的共享内存文件。
                restoreFromFile(context, file)
            } finally {
                deleteTemporaryDatabase(file)
            }
        }
    }

    internal suspend fun exportToFile(context: Context, destination: File) {
        val snapshot = database.backupDao().snapshot()
        val backup = AppDatabase.builder(context, destination.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            backup.backupDao().replace(snapshot)
        } finally {
            backup.close()
        }
    }

    /** source 必须是隔离临时副本；迁移和 WAL 回放只作用于该副本。 */
    internal suspend fun restoreFromFile(context: Context, source: File) {
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "备份文件损坏" }
            }
            require(sqlite.version in 1..AppDatabase.VERSION) { "不支持此备份版本，请升级应用或选择有效备份" }
        }
        val backup = AppDatabase.builder(context, source.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        val snapshot = try {
            // Room 打开时验证结构，并在副本上迁移旧版本；失败时原账本完全不变。
            backup.backupDao().snapshot()
        } finally {
            backup.close()
        }
        withContext(NonCancellable) {
            database.withTransaction {
                database.backupDao().replace(snapshot)
                LedgerRevision.advance()
            }
            // 即使发起页面随后销毁，提交后的旧通知也必须失效。
            runCatching { context.getSystemService(NotificationManager::class.java).cancelAll() }
            com.pennypilot.app.data.recognition.RecognitionTaskStore(context).clear()
            LedgerRevision.publish()
        }
    }

    private fun temporaryFile(context: Context): File =
        File(context.cacheDir, "backup-${UUID.randomUUID()}.db")

    private fun deleteTemporaryDatabase(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    private fun copyDocument(context: Context, uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
        input.use { stream -> destination.outputStream().use { stream.copyTo(it) } }
    }

    private fun documentName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment.orEmpty()
    }

    private suspend fun onIo(block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

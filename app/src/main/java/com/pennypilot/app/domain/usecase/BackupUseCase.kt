package com.pennypilot.app.domain.usecase

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class BackupUseCase @Inject constructor() {

    fun exportDb(context: Context): Result<String> {
        return try {
            val dbFile = context.getDatabasePath("pennypilot.db")
            if (!dbFile.exists()) return Result.failure(Exception("数据库不存在"))

            val backupDir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "PennyPilot")
            backupDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val backupFile = File(backupDir, "backup-$dateStr.db")

            dbFile.copyTo(backupFile, overwrite = true)
            // Also copy WAL and SHM if exist
            File(dbFile.path + "-wal").let { if (it.exists()) it.copyTo(File(backupFile.path + "-wal"), true) }
            File(dbFile.path + "-shm").let { if (it.exists()) it.copyTo(File(backupFile.path + "-shm"), true) }

            Result.success(backupFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun restoreDb(context: Context, backupPath: String): Result<Unit> {
        return try {
            val backupFile = File(backupPath)
            if (!backupFile.exists()) return Result.failure(Exception("备份文件不存在"))

            val dbFile = context.getDatabasePath("pennypilot.db")
            backupFile.copyTo(dbFile, overwrite = true)
            // 清理旧的 WAL / SHM，避免残留日志覆盖恢复的数据（需重启 App 重新打开数据库）
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

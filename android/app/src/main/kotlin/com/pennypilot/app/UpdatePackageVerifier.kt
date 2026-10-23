package com.pennypilot.app

import java.io.File
import java.security.MessageDigest

/** Android-independent checks shared by the install bridge and JVM tests. */
internal object UpdatePackageVerifier {
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    fun cachedFile(cacheDirectory: File, path: String, hash: String, code: Long, size: Long): File {
        if (!Regex("[0-9a-f]{64}").matches(hash) || code !in 1..2_100_000_000 || size !in 1..MAX_APK_BYTES) {
            fail("INVALID_APK", "更新包信息无效，请重新检查更新。")
        }
        val directory = File(cacheDirectory, "updates").canonicalFile
        val apk = File(path).canonicalFile
        if (apk.parentFile != directory || apk.name != "PennyPilot-$code.apk" || !apk.isFile || apk.length() != size) {
            fail("INVALID_APK", "更新包已过期或不完整，请重新下载。")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var count = stream.read(buffer)
            var total = 0L
            while (count >= 0) {
                total += count
                if (total > size) fail("INVALID_APK", "更新包大小校验失败，请重新下载。")
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
            if (total != size) fail("INVALID_APK", "更新包大小校验失败，请重新下载。")
        }
        if (digest.digest().toHex() != hash) {
            fail("HASH_MISMATCH", "更新包完整性校验失败，请重新下载。")
        }
        return apk
    }

    fun identity(
        expectedPackage: String,
        expectedVersion: String,
        expectedCode: Long,
        archivePackage: String,
        archiveVersion: String?,
        archiveCode: Long,
        installedCode: Long,
        installedSigners: Set<String>,
        archiveSigners: Set<String>,
    ) {
        if (archivePackage != expectedPackage || archiveVersion != expectedVersion || archiveCode != expectedCode) {
            fail("INVALID_APK", "更新包的应用标识或版本与发布信息不一致。")
        }
        if (archiveCode <= installedCode) {
            fail("OLD_VERSION", "该更新包不比当前版本新，请重新检查更新。")
        }
        if (installedSigners.isEmpty() || installedSigners != archiveSigners) {
            fail("SIGNATURE_MISMATCH", "更新包签名校验失败，无法安装。请从 PennyPilot 官方发布页面下载签名一致的更新包。")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun fail(code: String, message: String): Nothing = throw UpdateFailure(code, message)
}

internal class UpdateFailure(val code: String, message: String) : Exception(message)

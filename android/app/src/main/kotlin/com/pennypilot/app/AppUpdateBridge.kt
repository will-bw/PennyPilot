package com.pennypilot.app

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Verifies private-cache APKs again immediately before handing them to Android. */
class AppUpdateBridge(private val activity: MainActivity, messenger: BinaryMessenger) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "pennypilot/updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var busy = false

    init { channel.setMethodCallHandler(this) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "install") { result.notImplemented(); return }
        if (busy) { result.error("BUSY", "正在准备安装，请稍候。", null); return }
        busy = true
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { validateApk(call) }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    result.success("permissionRequired")
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri("PennyPilot update", uri)
                    })
                    result.success("installerOpened")
                }
            } catch (error: UpdateFailure) {
                result.error(error.code, error.message, null)
            } catch (_: CancellationException) {
                result.error("CANCELLED", "已取消安装。", null)
            } catch (_: Exception) {
                result.error("INSTALL_ERROR", "无法打开安装程序或授权页面，请重试或手动打开系统设置。", null)
            } finally { busy = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(call: MethodCall): File {
        val path = call.argument<String>("path") ?: fail("INVALID_APK", "更新包路径无效，请重新下载。")
        val hash = call.argument<String>("sha256") ?: ""
        val version = call.argument<String>("version") ?: ""
        val code = call.argument<Number>("versionCode")?.toLong() ?: 0
        val size = call.argument<Number>("size")?.toLong() ?: 0
        val apk = UpdatePackageVerifier.cachedFile(activity.cacheDir, path, hash, code, size)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = activity.packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: fail("INVALID_APK", "无法读取更新包，请重新下载。")
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        UpdatePackageVerifier.identity(
            expectedPackage = activity.packageName,
            expectedVersion = version,
            expectedCode = code,
            archivePackage = archive.packageName,
            archiveVersion = archive.versionName,
            archiveCode = versionCode(archive),
            installedCode = versionCode(installed),
            installedSigners = signers(installed),
            archiveSigners = signers(archive),
        )
        return apk
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun dispose() { channel.setMethodCallHandler(null); scope.cancel() }

    private fun fail(code: String, message: String): Nothing = throw UpdateFailure(code, message)
}

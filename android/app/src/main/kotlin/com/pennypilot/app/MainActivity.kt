package com.pennypilot.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject

/** Flutter owns the UI; this activity keeps Android identity and system integration. */
@AndroidEntryPoint
class MainActivity : FlutterFragmentActivity() {
    @Inject lateinit var ledgerBridge: NativeLedgerBridge
    private var attached = false
    private var exportRequest: CompletableDeferred<Uri?>? = null
    private var restoreRequest: CompletableDeferred<List<Uri>>? = null
    private var permissionRequest: CompletableDeferred<Boolean>? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        exportRequest?.complete(it)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        restoreRequest?.complete(it)
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRequest?.complete(it)
    }

    companion object { const val EXTRA_OPEN_ROUTE = "open_route" }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ledgerBridge.attach(this, flutterEngine.dartExecutor.binaryMessenger)
        attached = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ledgerBridge.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ledgerBridge.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (attached) ledgerBridge.onResume()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        if (attached) ledgerBridge.detach()
        attached = false
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        exportRequest?.cancel()
        restoreRequest?.cancel()
        permissionRequest?.cancel()
        if (attached) ledgerBridge.detach()
        attached = false
        super.onDestroy()
    }

    suspend fun chooseExportDocument(): Uri? {
        check(exportRequest == null && restoreRequest == null) { "文件选择器已打开，请先完成当前操作。" }
        val request = CompletableDeferred<Uri?>()
        exportRequest = request
        return try {
            exportLauncher.launch("PennyPilot-${java.time.LocalDate.now()}.db")
            request.await()
        } finally { exportRequest = null }
    }

    suspend fun chooseRestoreDocuments(): List<Uri> {
        check(exportRequest == null && restoreRequest == null) { "文件选择器已打开，请先完成当前操作。" }
        val request = CompletableDeferred<List<Uri>>()
        restoreRequest = request
        return try {
            restoreLauncher.launch(arrayOf("*/*"))
            request.await()
        } finally { restoreRequest = null }
    }

    suspend fun askPermission(permission: String): Boolean {
        check(permissionRequest == null) { "请先完成当前权限申请。" }
        val request = CompletableDeferred<Boolean>()
        permissionRequest = request
        return try {
            permissionLauncher.launch(permission)
            request.await()
        } finally { permissionRequest = null }
    }
}

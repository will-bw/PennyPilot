package com.pennypilot.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.ui.navigation.PennyPilotNavGraph
import com.pennypilot.app.ui.onboarding.OnboardingScreen
import com.pennypilot.app.ui.theme.PennyPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var database: AppDatabase

    /** Android 13+ 通知权限申请；拒绝后不再主动打扰 */
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not */ }

    companion object {
        /** 通知深链：启动后跳转的 App 内路由（如开机自检提醒 → coloros_guide） */
        const val EXTRA_OPEN_ROUTE = "open_route"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchRevision = LedgerRevision.current
        // 通知「编辑」动作携带的账单 id / 系统提醒携带的路由
        //（仅 savedInstanceState 为空的首次启动处理，避免重建重复跳转）
        val editTxId = if (savedInstanceState == null) {
            intent.getLongExtra("edit_tx_id", -1L)
        } else -1L
        val captureEventKey = if (savedInstanceState == null) {
            intent.getStringExtra("capture_event_key")
        } else null
        val openRoute = if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_OPEN_ROUTE)
        } else null
        val onboardingState = appPreferences.onboardingDone.map<Boolean, Boolean?> { it }

        setContent {
            PennyPilotTheme {
                // null = DataStore 首次读盘中；false = 展示首次启动引导；true = 进入主界面
                val onboardingDone by onboardingState.collectAsState(initial = null)
                val revision by LedgerRevision.changes.collectAsState()
                // 恢复后自增 ID 可能指向另一笔账目；通知必须同时匹配稳定事件标识。
                var validatedEditTxId by remember(editTxId, captureEventKey, revision) {
                    mutableLongStateOf(-1L)
                }
                LaunchedEffect(editTxId, captureEventKey, revision) {
                    validatedEditTxId = -1L
                    if (revision == launchRevision && editTxId > 0 && !captureEventKey.isNullOrBlank()) {
                        validatedEditTxId = try {
                            val tx = database.transactionDao().getById(editTxId)
                            if (tx?.captureEventKey == captureEventKey) editTxId else -1L
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            -1L
                        }
                    }
                }
                LaunchedEffect(revision) {
                    if (revision > launchRevision) {
                        Toast.makeText(this@MainActivity, "账本已恢复", Toast.LENGTH_SHORT).show()
                    }
                }
                when (onboardingDone) {
                    null -> Box(Modifier.fillMaxSize())
                    false -> OnboardingScreen(onFinish = {
                        lifecycleScope.launch { appPreferences.setOnboardingDone() }
                    })
                    true -> {
                        EnsureNotificationPermission(notifPermissionLauncher::launch)
                        // 恢复账本后丢弃旧导航及草稿，避免它们引用恢复前的交易/分类 ID。
                        key(revision) {
                            PennyPilotNavGraph(
                                startEditTxId = if (revision == launchRevision) validatedEditTxId else -1L,
                                startRoute = if (revision == launchRevision) openRoute else null
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Android 13+ 首次进入主界面时申请 POST_NOTIFICATIONS（确认/报告通知必需） */
@Composable
private fun EnsureNotificationPermission(launch: (String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!nm.areNotificationsEnabled() && !granted) {
                launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

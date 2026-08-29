package com.pennypilot.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.ui.navigation.PennyPilotNavGraph
import com.pennypilot.app.ui.onboarding.OnboardingScreen
import com.pennypilot.app.ui.theme.PennyPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences

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
        // 通知「编辑」动作携带的账单 id / 系统提醒携带的路由
        //（仅 savedInstanceState 为空的首次启动处理，避免重建重复跳转）
        val editTxId = if (savedInstanceState == null) {
            intent.getLongExtra("edit_tx_id", -1L)
        } else -1L
        val openRoute = if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_OPEN_ROUTE)
        } else null

        setContent {
            PennyPilotTheme {
                // null = DataStore 首次读盘中；false = 展示首次启动引导；true = 进入主界面
                val onboardingDone by appPreferences.onboardingDone
                    .map<Boolean, Boolean?> { it }
                    .collectAsState(initial = null)
                when (onboardingDone) {
                    null -> Box(Modifier.fillMaxSize())
                    false -> OnboardingScreen(onFinish = {
                        lifecycleScope.launch { appPreferences.setOnboardingDone() }
                    })
                    true -> {
                        EnsureNotificationPermission(notifPermissionLauncher::launch)
                        PennyPilotNavGraph(startEditTxId = editTxId, startRoute = openRoute)
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

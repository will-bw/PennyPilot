package com.pennypilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pennypilot.app.ui.navigation.PennyPilotNavGraph
import com.pennypilot.app.ui.theme.PennyPilotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 通知「编辑」动作携带的账单 id（仅 savedInstanceState 为空的首次启动处理，避免重建重复跳转）
        val editTxId = if (savedInstanceState == null) {
            intent.getLongExtra("edit_tx_id", -1L)
        } else -1L
        setContent {
            PennyPilotTheme {
                PennyPilotNavGraph(startEditTxId = editTxId)
            }
        }
    }
}

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
        setContent {
            PennyPilotTheme {
                PennyPilotNavGraph()
            }
        }
    }
}

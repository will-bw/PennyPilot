package com.pennypilot.app.ui.settings

import androidx.lifecycle.ViewModel
import com.pennypilot.app.service.CaptureServiceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val serviceMonitor: CaptureServiceMonitor
) : ViewModel()

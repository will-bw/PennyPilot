package com.pennypilot.app.domain.model

enum class TxSource(val label: String) {
    MANUAL("手动记账"),
    NOTIFICATION("通知自动捕获"),
    AI_TEXT("AI 文字录入"),
    AI_VOICE("AI 语音录入")
}

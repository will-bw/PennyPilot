package com.pennypilot.app.domain.model

enum class TxSource(val label: String) {
    MANUAL("手动记账"),
    NOTIFICATION("通知自动捕获"),
    ACCESSIBILITY("支付页面捕获"),
    AI_TEXT("AI 文字录入"),
    AI_IMAGE("图片识别"),
    BILL_IMPORT("账单导入"),
    AI_VOICE("AI 语音录入")
}

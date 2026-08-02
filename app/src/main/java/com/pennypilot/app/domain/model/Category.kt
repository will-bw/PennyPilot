package com.pennypilot.app.domain.model

data class Category(
    val id: Long = 0,
    val name: String,
    val icon: String,          // emoji
    val colorIndex: Int,       // 0-6
    val type: TxType,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val isPreset: Boolean = false
)

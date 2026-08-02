package com.pennypilot.app.domain.model

data class Transaction(
    val id: Long = 0,
    val amount: Long,          // 分，支出正/收入负
    val type: TxType,
    val categoryId: Long,
    val note: String? = null,
    val merchant: String? = null,
    val accountTag: String? = null,
    val occurredAt: Long,      // ms
    val createdAt: Long = System.currentTimeMillis(),
    val source: TxSource = TxSource.MANUAL,
    val status: TxStatus = TxStatus.CONFIRMED,
    val rawPayload: String? = null
)

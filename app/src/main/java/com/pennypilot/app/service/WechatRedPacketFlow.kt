package com.pennypilot.app.service

import java.util.UUID

/** An input-page visit alone is never payment proof. Use a verified completion before details. */
internal class WechatRedPacketFlow {
    private data class Flow(val startedAt: Long, val key: String, var paidAmount: Long? = null)
    private var flow: Flow? = null
    @Synchronized fun start(now: Long) {
        flow = Flow(now, CaptureEventIdentity.pageKey("com.tencent.mm", UUID.randomUUID().toString(), "red-packet"))
    }
    private fun current(now: Long): Flow? = flow?.takeIf { now - it.startedAt in 0..10 * 60_000L }
    @Synchronized fun isPreparing(now: Long): Boolean = current(now)?.paidAmount == null && current(now) != null
    @Synchronized fun hasPaid(now: Long): Boolean = current(now)?.paidAmount != null
    @Synchronized fun confirm(amount: Long, now: Long): String? {
        val current = current(now) ?: return null
        if (current.paidAmount != null && current.paidAmount != amount) return null
        current.paidAmount = amount
        return current.key
    }
    @Synchronized fun keyFor(amount: Long, now: Long): String? = current(now)?.takeIf { it.paidAmount == amount }?.key
    @Synchronized fun leaveUnpaid() { if (flow?.paidAmount == null) flow = null }
    @Synchronized fun reset() { flow = null }
}

package com.pennypilot.app.service

object CaptureEventIdentity {
    /**
     * 同一 package/key/postTime 的重复回调是同一事件，跨服务重建也保持一致。
     * key 或 postTime 变化视为新事件，避免同商户同金额的独立交易相互吞单。
     * 上游复用 key 且更新 postTime 的通知无法仅凭系统元数据认定为同一交易；
     * 不按文本猜测合并，保留为待确认记录供用户处理。
     */
    fun key(packageName: String, notificationKey: String, postTime: Long): String =
        "v1:$packageName:${notificationKey.length}:$notificationKey:$postTime"
}

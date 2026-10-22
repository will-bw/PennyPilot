package com.pennypilot.app.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** 仅失效本进程中的异步操作；进程退出时这些操作也随之消失，无需持久化。 */
object LedgerRevision {
    private val revision = AtomicLong(0)
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    val current: Long get() = revision.get()

    /** 在恢复写事务内失效旧快照。 */
    fun advance() { revision.incrementAndGet() }

    /** 事务提交后才重建 UI，避免旧 ViewModel 被销毁而取消尚未提交的恢复。 */
    fun publish() { _changes.value = current }
}

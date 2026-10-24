package com.pennypilot.app.data.db

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Durable generation invalidates queued inference across restores and process recreation. */
object LedgerRevision {
    private val revision = AtomicLong(0)
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    private var storage: SharedPreferences? = null
    val current: Long get() = revision.get()

    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val prefs = context.getSharedPreferences("ledger_generation", Context.MODE_PRIVATE)
        val initial = prefs.getLong("revision", System.currentTimeMillis())
        check(prefs.edit().putLong("revision", initial).commit()) { "账本版本未能保存。" }
        storage = prefs
        revision.set(initial)
        _changes.value = initial
    }

    /** Persist before accepting restored data; a crash can invalidate work but cannot revive it. */
    @Synchronized fun advance() {
        val next = revision.get() + 1
        storage?.let { check(it.edit().putLong("revision", next).commit()) { "账本版本未能保存。" } }
        revision.set(next)
    }
    fun publish() { _changes.value = current }
}

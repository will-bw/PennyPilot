package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import java.util.UUID

/** Bounded per-window identities shared by rules, model candidates and late evidence. */
class PaymentScreenSession {
    private data class Entry(
        var lastSeen: Long,
        var key: String? = null,
        var receipt: NotificationParseResult? = null,
        var handledReceipt: NotificationParseResult? = null,
        var candidateText: String? = null,
        var handledCandidate: String? = null,
        var absentSince: Long? = null,
        var boundary: Boolean = false
    )
    private val windows = linkedMapOf<String, Entry>()

    private fun entry(packageName: String, windowId: Int, now: Long): Entry {
        val id = "$packageName:$windowId"
        windows.entries.removeAll { it.key != id && now - it.value.lastSeen > SESSION_TTL_MS }
        val entry = windows.getOrPut(id) { Entry(now) }
        entry.lastSeen = now
        while (windows.size > MAX_WINDOWS) {
            val oldest = windows.entries.filter { it.key != id }.minByOrNull { it.value.lastSeen }
            if (oldest == null) break
            windows.remove(oldest.key)
        }
        return entry
    }

    private fun newPayment(entry: Entry, packageName: String) {
        entry.key = CaptureEventIdentity.pageKey(packageName, UUID.randomUUID().toString(), "payment")
        entry.receipt = null
        entry.handledReceipt = null
        entry.candidateText = null
        entry.handledCandidate = null
        entry.absentSince = null
        entry.boundary = false
    }

    fun observe(packageName: String, windowId: Int, parsed: NotificationParseResult?, now: Long): String? {
        val entry = entry(packageName, windowId, now)
        if (parsed == null) {
            if (entry.absentSince == null) entry.absentSince = now
            return null
        }
        val previous = entry.receipt
        val compatible = previous == null || previous.direction == parsed.direction &&
            previous.amountCents == parsed.amountCents &&
            (previous.occurredAt == null || parsed.occurredAt == null || previous.occurredAt == parsed.occurredAt) &&
            (previous.orderIdKind == null || parsed.orderIdKind == null || previous.orderIdKind == parsed.orderIdKind) &&
            (previous.orderId == null || parsed.orderId == null || previous.orderId == parsed.orderId) &&
            (previous.merchant.isNullOrBlank() || parsed.merchant.isNullOrBlank() || previous.merchant == parsed.merchant)
        if (entry.key == null || entry.boundary ||
            entry.absentSince?.let { now - it >= ABSENCE_MS } == true || !compatible) newPayment(entry, packageName)
        entry.absentSince = null
        val known = entry.receipt
        entry.receipt = parsed.copy(orderId = parsed.orderId ?: known?.orderId,
            orderIdKind = parsed.orderIdKind ?: known?.orderIdKind,
            merchant = parsed.merchant?.takeIf(String::isNotBlank) ?: known?.merchant,
            paymentMethod = parsed.paymentMethod ?: known?.paymentMethod)
        return entry.key?.takeIf { entry.receipt != entry.handledReceipt }
    }

    /** A completed but unknown layout gets an identity before its async model result exists. */
    fun observeCandidate(packageName: String, windowId: Int, text: String, now: Long): String? {
        val entry = entry(packageName, windowId, now)
        if (entry.key == null || entry.boundary || entry.absentSince?.let { now - it >= ABSENCE_MS } == true) {
            newPayment(entry, packageName)
        }
        entry.absentSince = null
        entry.candidateText = text
        // Once rules have safely recognized this payment, a less complete redraw adds no value.
        if (entry.handledReceipt != null) return null
        return entry.key?.takeIf { entry.handledCandidate != text }
    }

    /** Explicit input is a real navigation boundary, even for two fast identical transfers. */
    fun onInputPage(packageName: String, windowId: Int, now: Long) {
        entry(packageName, windowId, now).apply { boundary = true; absentSince = now }
    }

    fun evidence(eventKey: String): NotificationParseResult? = windows.values.firstOrNull { it.key == eventKey }?.receipt

    fun markHandled(eventKey: String) {
        windows.values.firstOrNull { it.key == eventKey }?.apply {
            handledReceipt = receipt
            handledCandidate = candidateText
        }
    }

    fun reset() = windows.clear()

    private companion object {
        const val MAX_WINDOWS = 8
        const val SESSION_TTL_MS = 10 * 60 * 1_000L
        const val ABSENCE_MS = 1_500L
    }
}

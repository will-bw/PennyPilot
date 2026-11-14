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
        var candidateVisualVersion: Long? = null,
        var handledVisualVersion: Long? = null,
        var checkoutDraft: Boolean = false,
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
        entry.candidateVisualVersion = null
        entry.handledVisualVersion = null
        entry.checkoutDraft = false
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
        val sameOrderWithDifferentTimeSources = previous?.orderId != null && previous.orderId == parsed.orderId &&
            previous.occurredAtIsCreationTime != parsed.occurredAtIsCreationTime
        val compatible = previous == null || previous.direction == parsed.direction &&
            previous.amountCents == parsed.amountCents &&
            (previous.occurredAt == null || parsed.occurredAt == null || previous.occurredAt == parsed.occurredAt ||
                sameOrderWithDifferentTimeSources) &&
            (previous.orderIdKind == null || parsed.orderIdKind == null || previous.orderIdKind == parsed.orderIdKind) &&
            (previous.orderId == null || parsed.orderId == null || previous.orderId == parsed.orderId) &&
            (previous.merchant.isNullOrBlank() || parsed.merchant.isNullOrBlank() || previous.merchant == parsed.merchant)
        if (entry.key == null || entry.boundary ||
            entry.absentSince?.let { now - it >= ABSENCE_MS } == true || !compatible) newPayment(entry, packageName)
        entry.absentSince = null
        val known = entry.receipt
        val time = if (known?.occurredAt != null && (parsed.occurredAt == null ||
                !known.occurredAtIsCreationTime && parsed.occurredAtIsCreationTime)) known else parsed
        val fields = known?.fieldEvidence.orEmpty() + parsed.fieldEvidence.filterValues(String::isNotBlank)
        val timeEvidence = time.fieldEvidence["occurredAt"]?.takeIf(String::isNotBlank)
        entry.receipt = parsed.copy(orderId = parsed.orderId ?: known?.orderId,
            orderIdKind = parsed.orderIdKind ?: known?.orderIdKind,
            merchant = parsed.merchant?.takeIf(String::isNotBlank) ?: known?.merchant,
            paymentMethod = parsed.paymentMethod?.takeIf(String::isNotBlank) ?: known?.paymentMethod,
            occurredAt = time.occurredAt,
            occurredAtIsCreationTime = time.occurredAtIsCreationTime,
            details = known?.details?.mergeMissing(parsed.details) ?: parsed.details,
            // A partially rendered receipt must not erase facts already read in this session.
            fieldEvidence = if (timeEvidence != null) fields + ("occurredAt" to timeEvidence)
                else if (known?.occurredAt != null && time.occurredAt != null &&
                    known.occurredAtIsCreationTime != time.occurredAtIsCreationTime) fields - "occurredAt"
                else fields)
        return entry.key?.takeIf { entry.receipt != entry.handledReceipt }
    }

    /** A completed but unknown layout gets an identity before its async model result exists. */
    fun observeCandidate(packageName: String, windowId: Int, text: String, now: Long,
        visualVersion: Long? = null): String? {
        val entry = entry(packageName, windowId, now)
        if (entry.key == null || entry.boundary || entry.absentSince?.let { now - it >= ABSENCE_MS } == true) {
            newPayment(entry, packageName)
        }
        entry.absentSince = null
        entry.candidateText = text
        entry.candidateVisualVersion = visualVersion
        // Once rules have safely recognized this payment, a less complete redraw adds no value.
        if (entry.handledReceipt != null) return null
        return entry.key?.takeIf { entry.handledCandidate != text ||
            visualVersion != null && entry.handledVisualVersion != visualVersion }
    }

    fun hasCheckoutDraft(packageName: String, windowId: Int): Boolean =
        windows["$packageName:$windowId"]?.let { it.checkoutDraft && !it.boundary && it.absentSince == null } == true

    fun markCheckoutDraft(eventKey: String) {
        windows.values.firstOrNull { it.key == eventKey }?.checkoutDraft = true
    }

    /** Explicit input is a real navigation boundary, even for two fast identical transfers. */
    fun onInputPage(packageName: String, windowId: Int, now: Long) {
        entry(packageName, windowId, now).apply { boundary = true; absentSince = now }
    }

    fun evidence(eventKey: String): NotificationParseResult? = windows.values.firstOrNull { it.key == eventKey }?.receipt

    fun handledReceiptKey(packageName: String, windowId: Int): String? =
        windows["$packageName:$windowId"]?.takeIf { it.handledReceipt != null }?.key

    fun markHandled(eventKey: String) {
        windows.values.firstOrNull { it.key == eventKey }?.apply {
            handledReceipt = receipt
            handledCandidate = candidateText
            handledVisualVersion = candidateVisualVersion
        }
    }

    fun reset() = windows.clear()

    private companion object {
        const val MAX_WINDOWS = 8
        const val SESSION_TTL_MS = 10 * 60 * 1_000L
        const val ABSENCE_MS = 1_500L
    }
}

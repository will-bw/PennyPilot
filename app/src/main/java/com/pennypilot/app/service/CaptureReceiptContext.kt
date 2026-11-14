package com.pennypilot.app.service

import com.pennypilot.app.data.db.entity.CaptureEvidenceEntity
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.recognition.CaptureSemanticReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Builds semantic context from source-specific evidence, never the merged ledger row. */
internal object CaptureReceiptContext {

    fun mergeContext(fields: List<PaymentReceiptContext>): List<PaymentReceiptContext> =
        PaymentReceiptDetails.normalizeContext(fields.sortedBy { if (it.label == "交易对方") 0 else 1 })

    fun readContext(metadata: JsonObject?): List<PaymentReceiptContext> =
        (metadata?.get("receiptContext") as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val label = string(item, "label") ?: return@mapNotNull null
            val value = string(item, "value") ?: return@mapNotNull null
            PaymentReceiptContext(label, value, string(item, "source").orEmpty())
        }.let(PaymentReceiptDetails::normalizeContext)

    fun fromEvidence(evidence: List<CaptureEvidenceEntity>, includeLocalCheckout: Boolean = true): CaptureSemanticReceipt {
        val details = linkedMapOf<String, String>()
        val context = mutableListOf<PaymentReceiptContext>()
        var occurredAt: String? = null
        var scene: String? = null
        evidence.sortedWith(compareBy<CaptureEvidenceEntity> { it.firstObservedAt }.thenBy { it.eventKey }).forEach { row ->
            val metadata = row.metadataJson?.let { Json.parseToJsonElement(it) as? JsonObject } ?: return@forEach
            val receiptDetails = metadata["receiptDetails"] as? JsonObject
            PaymentReceiptDetails.TEXT_FIELDS.forEach { field -> string(receiptDetails, field)?.let { details.putIfAbsent(field, it) } }
            PaymentReceiptDetails.MONEY_FIELDS.forEach { field ->
                (receiptDetails?.get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?.takeIf { it >= 0 }?.let { details.putIfAbsent(field, it.toString()) }
            }
            context += readContext(metadata).filter { includeLocalCheckout || it.source != CheckoutContext.LOCAL_SOURCE }
            occurredAt = occurredAt ?: string(metadata, "receiptOccurredAt")
            scene = scene ?: string(metadata, "scene")
        }
        return CaptureSemanticReceipt(details, mergeContext(context), occurredAt, scene)
    }

    fun string(metadata: JsonObject?, key: String): String? =
        (metadata?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
}

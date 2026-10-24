package com.pennypilot.app.data.db

import com.pennypilot.app.data.db.dao.BackupSnapshot
import com.pennypilot.app.data.db.entity.*
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PortableBackupCodecTest {
    private val snapshot = BackupSnapshot(
        categories = listOf(CategoryEntity(7, "餐饮", "🍜", 0, TxType.EXPENSE, 0, true, true)),
        transactions = listOf(TransactionEntity(id = 41, amount = 1234, type = TxType.EXPENSE,
            categoryId = 7, occurredAt = 1720000000000, createdAt = 1720000000001,
            source = TxSource.NOTIFICATION, status = TxStatus.PENDING,
            rawPayload = "原始支付证据", captureEventKey = "event", duplicateOf = 99)),
        rules = listOf(ParseRuleEntity(9, "test.payment", "金额([0-9.]+)")),
        reports = listOf(MonthlyReportEntity(5, "2026-09", 1234, 0, "[]", "月报", 1720000000000)),
        captureEvents = listOf(CaptureEvidenceEntity("event", 41, "test.payment", TxSource.NOTIFICATION,
            1720000000000, 1720000000000, "notification", "fingerprint", null, "domain", 1234,
            TxType.EXPENSE, null))
    )

    @Test fun `portable round trip retains complete rows and capture evidence`() {
        val encoded = PortableBackupCodec.encode(snapshot)
        assertEquals(snapshot, PortableBackupCodec.decode(encoded))
        assertFalse(encoded.containsKey("settings"))
        assertFalse(encoded.containsKey("auth"))
    }

    @Test fun `recognition metadata identities and deletion markers survive portable round trip`() {
        val enriched = snapshot.copy(
            transactions = snapshot.transactions.map { it.copy(businessKind = "unknown", metadataJson = "{\"source\":\"page\"}", operationKey = "input-1", editVersion = 3) },
            captureEvents = snapshot.captureEvents.map { it.copy(metadataJson = "{\"scene\":\"wechat_transfer\"}") },
            captureTombstones = listOf(CaptureTombstoneEntity("operation:deleted-input", 999, 1720000000000))
        )
        assertEquals(enriched, PortableBackupCodec.decode(PortableBackupCodec.encode(enriched)))
        val duplicate = enriched.copy(transactions = enriched.transactions + enriched.transactions.single().copy(id = 42))
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(duplicate)) }
    }

    @Test fun `refund only month preserves negative net expense in report backup`() {
        val refund = snapshot.copy(
            categories = snapshot.categories.map { it.copy(type = TxType.INCOME) },
            transactions = snapshot.transactions.map { it.copy(type = TxType.INCOME, amount = -1234, businessKind = "refund") },
            captureEvents = snapshot.captureEvents.map { it.copy(type = TxType.INCOME, amount = -1234) },
            reports = snapshot.reports.map { it.copy(totalExpense = -1234, byCategoryJson = "[{\"name\":\"退款\",\"total\":-1234,\"count\":1}]") }
        )
        assertEquals(refund, PortableBackupCodec.decode(PortableBackupCodec.encode(refund)))
    }

    @Test fun `income sign and category reference are validated`() {
        val bad = snapshot.copy(transactions = snapshot.transactions.map { it.copy(amount = -1234) })
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(bad)) }
        val dangling = snapshot.copy(transactions = snapshot.transactions.map { it.copy(categoryId = 99) })
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(dangling)) }
        for (kind in listOf("income", "refund")) {
            val incompatible = snapshot.copy(transactions = snapshot.transactions.map { it.copy(businessKind = kind) })
            assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(incompatible)) }
        }
    }

    @Test fun `unknown versions and fractional integer values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot) + ("version" to 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot) + ("version" to 1.0))
        }
    }

    @Test fun `capture events must reference included transactions`() {
        val bad = snapshot.copy(captureEvents = snapshot.captureEvents.map { it.copy(transactionId = 1000) })
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(bad)) }
    }

    @Test fun `duplicate identities are rejected before any database mutation`() {
        val bad = snapshot.copy(transactions = snapshot.transactions + snapshot.transactions)
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(PortableBackupCodec.encode(bad)) }
    }

    @Test fun `newly generated report accepts zero id but restored rows do not`() {
        val encoded = PortableBackupCodec.encode(snapshot)
        @Suppress("UNCHECKED_CAST")
        val report = (encoded["reports"] as List<Map<String, Any?>>).single() + ("id" to 0)
        assertEquals(0L, PortableBackupCodec.decodeReport(report).id)
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decode(encoded + ("reports" to listOf(report))) }
    }

    @Test fun `shared Android iOS fixture and actual SQLite export retain the same wire contract`() {
        val relativePath = "test/core/storage/fixtures/portable_backup_v1.json"
        val fixture = listOf(File(relativePath), File("../$relativePath")).firstOrNull { it.isFile }
            ?: error("Shared portable backup fixture is missing")
        val sources = listOf(fixture) + listOfNotNull(System.getenv("PENNYPILOT_BACKUP_CONTRACT_EXPORT")?.let(::File))
        for (source in sources) {
            val expected = Json.parseToJsonElement(source.readText())
            @Suppress("UNCHECKED_CAST")
            val decoded = PortableBackupCodec.decode(unwire(expected) as Map<String, Any?>)
            // Legacy fixtures acquire explicit defaults; existing values from newer SQLite exports must survive.
            val expectedObject = expected.jsonObject
            val canonical = JsonObject(expectedObject + mapOf(
                "transactions" to JsonArray(expectedObject.getValue("transactions").jsonArray.map { item ->
                    JsonObject(mapOf("businessKind" to JsonPrimitive("standard"), "metadataJson" to JsonNull,
                        "operationKey" to JsonNull, "editVersion" to JsonPrimitive(0)) + item.jsonObject)
                }),
                "captureEvents" to JsonArray(expectedObject.getValue("captureEvents").jsonArray.map { item ->
                    JsonObject(mapOf("metadataJson" to JsonNull) + item.jsonObject)
                }),
                "captureTombstones" to (expectedObject["captureTombstones"] ?: JsonArray(emptyList()))
            ))
            assertEquals(canonical, wire(PortableBackupCodec.encode(decoded)))
            assertEquals(-90000L, decoded.transactions.single { it.id == 42L }.amount)
            assertEquals(TxStatus.IGNORED, decoded.transactions.single { it.id == 43L }.status)
            assertEquals(99L, decoded.transactions.single { it.id == 41L }.duplicateOf)
            assertEquals("(?i)paid (\\d++)", decoded.rules.single().regexAmount)
            assertEquals(2, decoded.captureEvents.size)
        }
    }

    @Test fun `unsafe portable values and unsupported report shapes are rejected`() {
        val encoded = PortableBackupCodec.encode(snapshot)
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decode(encoded + ("futureSchema" to emptyMap<String, Any?>()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot.copy(categories = snapshot.categories.map { it.copy(sortOrder = -1) })))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot.copy(transactions = snapshot.transactions.map { it.copy(id = 9007199254740992L) })))
        }
        for (breakdown in listOf("{}", "[{\"name\":\"餐饮\",\"total\":1.5,\"count\":1}]", "[{\"name\":\"餐饮\",\"total\":\"1\",\"count\":1}]")) {
            assertThrows(IllegalArgumentException::class.java) {
                PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot.copy(reports = snapshot.reports.map { it.copy(byCategoryJson = breakdown) })))
            }
        }
    }

    private fun unwire(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { unwire(it.value) }
        is JsonArray -> value.map(::unwire)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            else -> value.long
        }
    }

    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::wire))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to wire(it.value) })
        else -> error("Unsupported wire value")
    }
}

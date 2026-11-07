package com.pennypilot.app.data.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Immutable, bounded facts; never holds an Android node or an editable value. */
@Serializable
data class PaymentScreenNode(
    val id: Int,
    val parentId: Int?,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val bounds: List<Int> = emptyList(),
    val visible: Boolean = true
) {
    val labels: List<String> get() = listOfNotNull(text, description).distinct()
}

@Serializable
data class PaymentScreenStructure(val nodes: List<PaymentScreenNode>) {
    fun toJson(): String = Json.encodeToString(this)

    /** Field values may precede their label in accessibility traversal order. */
    internal fun labelledTextFields(labels: Set<String>): Map<String, List<String>> {
        val siblings = nodes.filter { it.visible }.groupBy { it.parentId }
        fun fieldLabel(value: String): String? = value.trimEnd(':', '：').trimEnd().takeIf(labels::contains)
        val webRows = webViewRows(siblings, ::fieldLabel)
        val fields = siblings.flatMap { (parentId, row) ->
            if (parentId == null || row.size !in 2..4 || parentId in webRows) return@flatMap emptyList()
            val branches = row.map { node ->
                // A field can wrap its text in one view. Do not search arbitrary descendants.
                node.labels.ifEmpty { siblings[node.id]?.singleOrNull()?.labels.orEmpty() }
                    .map(String::trim).filter(String::isNotEmpty)
            }
            val rowLabels = branches.flatten().mapNotNull(::fieldLabel).distinct()
            val values = if (rowLabels.size == 1) branches.filter { branch -> branch.none { fieldLabel(it) != null } }
                .flatten().distinct() else emptyList()
            rowLabels.map { it to values }
        }
        return (fields + webRows.values.flatten())
            .groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.flatten().distinct() }
    }

    private data class WebCell(val node: PaymentScreenNode, val texts: List<String>) {
        val left get() = node.bounds[0]
        val right get() = node.bounds[2]
        private val top get() = node.bounds[1].toLong()
        private val bottom get() = node.bounds[3].toLong()

        fun sharesLine(other: WebCell): Boolean =
            kotlin.math.abs(top + bottom - other.top - other.bottom) <= minOf(bottom - top, other.bottom - other.top)
    }

    /** WebView may flatten a field table into siblings, in an order unrelated to its layout. */
    private fun webViewRows(siblings: Map<Int?, List<PaymentScreenNode>>,
        fieldLabel: (String) -> String?): Map<Int, List<Pair<String, List<String>>>> {
        val byId = nodes.filter { it.visible }.associateBy { it.id }
        if (byId.values.none { it.className == "android.webkit.WebView" }) return emptyMap()

        fun insideWebView(parentId: Int): Boolean {
            var parent = byId[parentId]
            // Restrict coordinate pairing to a nearby WebView ancestor in the same panel.
            repeat(6) {
                val current = parent ?: return false
                if (current.className == "android.webkit.WebView") return true
                parent = byId[current.parentId]
            }
            return false
        }

        return siblings.mapNotNull panel@{ (parentId, children) ->
            if (parentId == null || children.size !in 4..64 || !insideWebView(parentId)) return@panel null
            val cells = children.mapNotNull { child ->
                val node = if (child.labels.isEmpty()) siblings[child.id]?.singleOrNull() ?: return@mapNotNull null else child
                if (!siblings[node.id].isNullOrEmpty()) return@mapNotNull null
                val texts = node.labels.map(String::trim).filter(String::isNotEmpty).distinct()
                if (texts.isEmpty()) return@mapNotNull null
                val box = node.bounds
                if (box.size != 4 || box[2] <= box[0] || box[3] <= box[1]) return@panel null
                WebCell(node, texts)
            }
            val rightByLeft = cells.mapNotNull { cell ->
                val neighbour = cells.singleOrNull { it !== cell && cell.sharesLine(it) }
                // A third cell, a different field role, or an overlapping column is ambiguous.
                neighbour?.takeIf { it.left >= cell.right }?.let { cell to it }
            }.toMap()
            // Keep the small sibling heuristic unless all four cells form two separate rows.
            if (children.size == 4 && (cells.any { it.texts.size != 1 } || rightByLeft.size != 2 ||
                    (rightByLeft.keys + rightByLeft.values).size != 4)) return@panel null
            parentId to cells.mapNotNull { cell ->
                val label = cell.texts.singleOrNull()?.let(fieldLabel) ?: return@mapNotNull null
                val value = rightByLeft[cell]?.texts?.singleOrNull()
                    ?.takeIf { it.length <= 300 && fieldLabel(it) == null }
                label to listOfNotNull(value)
            }
        }.toMap()
    }

    /** A semantic resource ID on a verified receipt is evidence; an unlabelled heading alone is not. */
    internal fun namedReceiptField(resourceNames: Set<String>): String? = nodes.asSequence()
        .filter { it.visible && it.resourceId?.substringAfterLast('/')?.let(resourceNames::contains) == true }
        .flatMap { it.labels.asSequence() }.map(String::trim)
        .filter { it.length in 1..120 && !it.all(Char::isDigit) }.distinct().toList().singleOrNull()

    /** Labels in the same small row may arrive amount-first or through contentDescription. */
    fun labelledAmounts(): List<String> = PaymentTextParser.structuredAmounts(this)

    internal fun standaloneActionLabels(): Set<String> = nodes.asSequence()
        .filter { it.visible && it.className == "android.widget.Button" }
        .mapNotNull { it.labels.map(String::trim).distinct().singleOrNull() }.toSet()
}

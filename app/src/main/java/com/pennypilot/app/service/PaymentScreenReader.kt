package com.pennypilot.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.pennypilot.app.data.parser.PaymentScreenNode
import com.pennypilot.app.data.parser.PaymentSceneAdapters
import com.pennypilot.app.data.parser.PaymentScreenStructure

/** 有界读取可见文本，跳过密码和所有输入控件；不截屏、不操作节点。 */
object PaymentScreenReader {
    private const val MAX_VISITED_NODES = 2_000
    private const val MAX_READABLE_NODES = 1_000
    private const val MAX_DEPTH = 60
    private const val MAX_CHARS = 12_000
    private const val MAX_OPAQUE_TREE_NODES = 8

    data class Snapshot(
        val text: String?,
        val hasInput: Boolean,
        val status: String,
        val diagnostics: Map<String, Int>,
        val structure: PaymentScreenStructure? = null,
        val windowBounds: List<Int> = emptyList(),
        val receiptBounds: List<Int>? = null
    ) {
        /** A small, fully traversed tree can be opaque even when its wrapper nodes are visible. */
        internal val isBoundedOpaqueTree: Boolean
            get() = status == "empty_page" && text == null && !hasInput &&
                diagnostics["visitedNodes"]?.let { it in 1..MAX_OPAQUE_TREE_NODES } == true &&
                listOf("textNodes", "hiddenTextNodes", "editableNodes", "passwordNodes", "hiddenInputNodes", "missingChildren",
                    "foreignNodes", "unknownOwnerNodes").all { diagnostics[it] == 0 }
    }

    fun read(root: AccessibilityNodeInfo, packageName: String): String? = inspect(root, packageName).text

    @Suppress("DEPRECATION")
    fun inspect(root: AccessibilityNodeInfo, packageName: String, activityName: String? = null): Snapshot {
        var visited = 0
        var readable = 0
        var textNodes = 0
        var hiddenTextNodes = 0
        var editableNodes = 0
        var passwordNodes = 0
        var hiddenInputNodes = 0
        var missingChildren = 0
        var hiddenNodes = 0
        var foreignNodes = 0
        var unknownOwnerNodes = 0
        var length = 0
        var refusalStatus: String? = null
        val lines = mutableListOf<String>()
        val facts = mutableListOf<PaymentScreenNode>()
        val inputParents = mutableSetOf<Int>()
        val rootBounds = Rect().also(root::getBoundsInScreen).let { listOf(it.left, it.top, it.right, it.bottom) }
        fun visit(node: AccessibilityNodeInfo, depth: Int, parentId: Int? = null) {
            if (refusalStatus != null) return
            if (++visited > MAX_VISITED_NODES || depth > MAX_DEPTH) {
                refusalStatus = "traversal_limit"
                return
            }
            // 先确认归属；其他应用的输入框和密码节点不能污染当前应用的判断。
            val owner = node.packageName?.toString()
            if (owner != null && owner != packageName) {
                foreignNodes++
                return
            }
            if (owner == null) unknownOwnerNodes++
            val nodeId = visited
            val editable = node.isEditable
            val inputClass = node.className?.toString() == "android.widget.EditText"
            val visible = node.isVisibleToUser
            if (!visible) {
                hiddenNodes++
                // 隐藏的输入/密码子树整体跳过，不能从其子节点泄漏内容。
                if (node.isPassword || editable || inputClass) {
                    hiddenInputNodes++
                    return
                }
                // A hidden label is not a receipt field and does not belong to an opaque tree.
                if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) hiddenTextNodes++
                // 普通隐藏容器仍可能包含可见结果页，继续有界遍历其后代。
            } else {
                if (++readable > MAX_READABLE_NODES) {
                    refusalStatus = "traversal_limit"
                    return
                }
                if (node.isPassword) passwordNodes++
                if (editable || inputClass) editableNodes++
                // Input contents and descendants are never read, even when a separate receipt panel is accepted.
                if (node.isPassword || editable || inputClass) {
                    parentId?.let(inputParents::add)
                    return
                }
                // 部分支付页把状态放 text、金额放 contentDescription；两者都需要读取。
                val nodeTexts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .map(String::trim).filter(String::isNotEmpty).distinct()
                if (nodeTexts.isNotEmpty()) textNodes++
                for (text in nodeTexts) {
                    val separatorLength = if (lines.isEmpty()) 0 else 1
                    if (text.length > MAX_CHARS - length - separatorLength) {
                        refusalStatus = "text_limit"
                        return
                    }
                    length += text.length + separatorLength
                    lines.add(text)
                }
            }
            // Preserve hierarchy even for hidden containers; their own text is never retained.
            val bounds = Rect().also(node::getBoundsInScreen)
            facts.add(PaymentScreenNode(nodeId, parentId,
                text = if (visible) node.text?.toString()?.trim()?.takeIf(String::isNotEmpty) else null,
                description = if (visible) node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty) else null,
                className = node.className?.toString()?.take(200),
                resourceId = node.viewIdResourceName?.take(200),
                bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                visible = visible))
            for (i in 0 until node.childCount) {
                // 无障碍树切换时可能瞬时返回 null；只重取一次，仍缺失则拒绝整页。
                val child = node.getChild(i) ?: node.getChild(i)
                if (child == null) {
                    missingChildren++
                    refusalStatus = "incomplete_tree"
                    break
                }
                try { visit(child, depth + 1, nodeId) } finally { child.recycle() }
                if (refusalStatus != null) break
            }
        }
        visit(root, 0)
        // A complete, explicitly identified receipt panel can coexist with an unrelated search
        // or feedback box. Unknown/chat layouts and any password page still reject the entire read.
        val panel = if (editableNodes > 0 && passwordNodes == 0 && refusalStatus == null)
            receiptPanel(facts, inputParents, packageName, activityName) else null
        val selectedFacts = panel?.second ?: facts
        val selectedText = if (panel == null) lines.joinToString("\n") else
            selectedFacts.flatMap { it.labels }.joinToString("\n")
        val hasInput = (editableNodes > 0 || passwordNodes > 0) && panel == null
        val status = when {
            passwordNodes > 0 -> "password_page"
            editableNodes > 0 && panel == null -> "input_page"
            refusalStatus != null -> refusalStatus!!
            selectedText.isEmpty() -> "empty_page"
            else -> "ready"
        }
        return Snapshot(
            text = if (status == "ready") selectedText else null,
            hasInput = hasInput,
            status = status,
            // 只记录数量，不把页面正文、输入内容或包名放入诊断。
            diagnostics = mapOf(
                "visitedNodes" to visited,
                "textNodes" to textNodes,
                "hiddenTextNodes" to hiddenTextNodes,
                "editableNodes" to editableNodes,
                "passwordNodes" to passwordNodes,
                "hiddenInputNodes" to hiddenInputNodes,
                "missingChildren" to missingChildren,
                "hiddenNodes" to hiddenNodes,
                "foreignNodes" to foreignNodes,
                "unknownOwnerNodes" to unknownOwnerNodes,
                "excludedInputNodes" to if (panel != null) editableNodes else 0
            ),
            // Refused pages must expose neither partial text nor partial structured facts.
            structure = if (status == "ready") PaymentScreenStructure(selectedFacts) else null,
            windowBounds = rootBounds,
            receiptBounds = panel?.first?.bounds
        )
    }

    private fun receiptPanel(nodes: List<PaymentScreenNode>, inputParents: Set<Int>, packageName: String,
        activityName: String?): Pair<PaymentScreenNode, List<PaymentScreenNode>>? {
        if (activityName?.contains(Regex("chat|conversation|message", RegexOption.IGNORE_CASE)) == true) return null
        val paymentActivity = activityName?.contains(Regex("wallet|payment|receipt|billdetail|tradedetail|remittance", RegexOption.IGNORE_CASE)) == true
        val children = nodes.groupBy { it.parentId }
        fun subtree(root: PaymentScreenNode): List<PaymentScreenNode> {
            val result = mutableListOf<PaymentScreenNode>()
            fun walk(node: PaymentScreenNode) { result += node; children[node.id].orEmpty().forEach(::walk) }
            walk(root)
            return result
        }
        val candidates = nodes.asSequence().filter { it.parentId != null && it.visible }.mapNotNull { node ->
            val namedPanel = node.resourceId?.substringAfterLast('/')?.matches(
                Regex("(?:payment_result|receipt|bill_detail|trade_detail|transaction_detail)(?:_panel|_container|_content)?")) == true
            if (!paymentActivity && !namedPanel) return@mapNotNull null
            val descendants = subtree(node)
            if (descendants.any { it.id in inputParents }) return@mapNotNull null
            val text = descendants.flatMap { it.labels }.joinToString("\n")
            val match = PaymentSceneAdapters.match(packageName, text, activityName, PaymentScreenStructure(descendants))
            if (match == null || match.kind == PaymentSceneAdapters.Kind.OPAQUE_RECEIPT) return@mapNotNull null
            node to descendants
        }.toList()
        // Nested candidates are the same receipt; two unrelated receipt panels are ambiguous.
        val outermost = candidates.filter { candidate -> candidates.none { other ->
            other.first.id != candidate.first.id && other.second.any { it.id == candidate.first.id }
        } }
        return outermost.singleOrNull()?.let { (node, descendants) ->
            node to descendants.map { if (it.id == node.id) it.copy(parentId = null) else it }
        }
    }

}

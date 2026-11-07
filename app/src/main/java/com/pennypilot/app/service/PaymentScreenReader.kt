package com.pennypilot.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.pennypilot.app.data.parser.PaymentScreenNode
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
        val structure: PaymentScreenStructure? = null
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
    fun inspect(root: AccessibilityNodeInfo, packageName: String): Snapshot {
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
                // 同包可见输入框即使为空或未聚焦也整页拒绝，防止把聊天伪凭证入账。
                if (node.isPassword || editable || inputClass) return
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
        // 完整性不足时不能丢弃尾部“未支付/退款中”等上下文再入账。
        val hasInput = editableNodes > 0 || passwordNodes > 0
        val status = when {
            passwordNodes > 0 -> "password_page"
            editableNodes > 0 -> "input_page"
            refusalStatus != null -> refusalStatus!!
            lines.isEmpty() -> "empty_page"
            else -> "ready"
        }
        return Snapshot(
            text = if (status == "ready") lines.joinToString("\n") else null,
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
                "unknownOwnerNodes" to unknownOwnerNodes
            ),
            // Refused pages must expose neither partial text nor partial structured facts.
            structure = if (status == "ready") PaymentScreenStructure(facts) else null
        )
    }
}

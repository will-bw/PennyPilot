package com.pennypilot.app.service

import android.view.accessibility.AccessibilityNodeInfo

/** 有界读取可见文本，跳过密码和所有输入控件；不截屏、不操作节点。 */
object PaymentScreenReader {
    private const val MAX_NODES = 250
    private const val MAX_CHARS = 12_000

    data class Snapshot(val text: String?, val hasInput: Boolean)

    fun read(root: AccessibilityNodeInfo, packageName: String): String? = inspect(root, packageName).text

    @Suppress("DEPRECATION")
    fun inspect(root: AccessibilityNodeInfo, packageName: String): Snapshot {
        var visited = 0
        var length = 0
        var incomplete = false
        var hasInput = false
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (incomplete) return
            if (++visited > MAX_NODES || depth > 30) { incomplete = true; return }
            if (!node.isVisibleToUser) return
            // 有输入框的聊天、评论、密码页整页拒绝，不能把消息或输入内容当支付结果。
            if (node.isPassword || node.isEditable) { hasInput = true; return }
            val owner = node.packageName?.toString()
            if (owner != null && owner != packageName) return
            // 部分支付页把状态放 text、金额放 contentDescription；两者都需要读取。
            val nodeTexts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map(String::trim).filter(String::isNotEmpty).distinct()
            for (text in nodeTexts) {
                length += text.length
                if (length > MAX_CHARS) { incomplete = true; return }
                lines.add(text)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child == null) { incomplete = true; break }
                try { visit(child, depth + 1) } finally { child.recycle() }
                if (incomplete) break
            }
        }
        visit(root, 0)
        // 完整性不足时不能丢弃尾部“未支付/退款中”等上下文再入账。
        val text = if (incomplete || hasInput) null else lines.joinToString("\n").takeIf { it.isNotBlank() }
        return Snapshot(text, hasInput)
    }
}

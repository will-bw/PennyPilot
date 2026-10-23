package com.pennypilot.app.service

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.pennypilot.app.data.parser.PaymentTextParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class PaymentScreenReaderTest {
    private val packageName = "com.sankuai.meituan"
    private val receipt = "支付成功\n￥18.00"

    private fun node(label: String? = "支付成功 实付18.00元") = AccessibilityNodeInfo.obtain().apply {
        this.packageName = this@PaymentScreenReaderTest.packageName
        isVisibleToUser = true
        text = label
    }

    private fun AccessibilityNodeInfo.add(child: AccessibilityNodeInfo) {
        shadowOf(this).addChild(child)
    }

    private fun inspect(root: AccessibilityNodeInfo) = PaymentScreenReader.inspect(root, packageName)

    @Test fun `visible readonly text is available only to matching app`() {
        val root = node()
        val matching = inspect(root)
        assertEquals("支付成功 实付18.00元", matching.text)
        assertEquals("ready", matching.status)
        assertFalse(matching.hasInput)
        val foreign = PaymentScreenReader.inspect(root, "com.tencent.mm")
        assertNull(foreign.text)
        assertEquals("empty_page", foreign.status)
        assertEquals(1, foreign.diagnostics["foreignNodes"])
    }

    @Test fun `password and editable pages report protection without exposing text`() {
        val root = node().apply { isPassword = true }
        val password = inspect(root)
        assertNull(password.text)
        assertTrue(password.hasInput)
        assertEquals("password_page", password.status)
        assertEquals(1, password.diagnostics["passwordNodes"])
        assertEquals(0, password.diagnostics["textNodes"])
        root.isPassword = false
        root.isEditable = true
        val editable = inspect(root)
        assertNull(editable.text)
        assertTrue(editable.hasInput)
        assertEquals("input_page", editable.status)
        assertEquals(1, editable.diagnostics["editableNodes"])
    }

    @Test fun `password reason takes priority when a page also has editable inputs`() {
        val root = node(receipt)
        root.add(node(null).apply { isEditable = true })
        root.add(node("秘密").apply { isPassword = true; isEditable = true })
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertEquals("password_page", snapshot.status)
        assertEquals(2, snapshot.diagnostics["editableNodes"])
        assertEquals(1, snapshot.diagnostics["passwordNodes"])
    }

    @Test fun `empty and unfocused same app input still rejects a receipt`() {
        val root = node(receipt)
        root.add(node(null).apply { isEditable = true; isFocused = false })
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertTrue(snapshot.hasInput)
        assertEquals("input_page", snapshot.status)
    }

    @Test fun `empty and unfocused same app password still rejects a receipt`() {
        val root = node(receipt)
        root.add(node(null).apply { isPassword = true; isFocused = false })
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertTrue(snapshot.hasInput)
        assertEquals("password_page", snapshot.status)
    }

    @Test fun `foreign editable and password subtrees cannot poison or supply a receipt`() {
        val root = node(receipt)
        root.add(node("外包输入").apply {
            this.packageName = "other.app"
            isEditable = true
            add(node("支付失败"))
        })
        root.add(node("外包密码").apply {
            this.packageName = "other.app"
            isPassword = true
            add(node("支付失败"))
        })
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals("ready", snapshot.status)
        assertFalse(snapshot.hasInput)
        assertEquals(2, snapshot.diagnostics["foreignNodes"])
        assertEquals(0, snapshot.diagnostics["editableNodes"])
        assertEquals(0, snapshot.diagnostics["passwordNodes"])
        assertEquals(3, snapshot.diagnostics["visitedNodes"])
    }

    @Test fun `text and content description are both preserved without duplicating same label`() {
        val root = node("支付成功").apply { contentDescription = "￥18.00" }
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals(1, snapshot.diagnostics["textNodes"])
        root.contentDescription = "支付成功"
        assertEquals("支付成功", inspect(root).text)
    }

    @Test fun `chat with editable child rejects even complete payment text`() {
        val root = node(receipt)
        root.add(node("发送消息").apply { isEditable = true })
        assertNull(PaymentScreenReader.read(root, packageName))
    }

    @Test fun `hidden input and password subtrees never expose visible descendants`() {
        val root = node(receipt)
        root.add(node("隐藏密码").apply {
            isPassword = true
            isVisibleToUser = false
            add(node("密码子节点秘密"))
        })
        root.add(node("隐藏输入").apply {
            isEditable = true
            isVisibleToUser = false
            add(node("输入子节点秘密"))
        })
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals("ready", snapshot.status)
        assertFalse(snapshot.hasInput)
        assertEquals(2, snapshot.diagnostics["hiddenNodes"])
        assertEquals(3, snapshot.diagnostics["visitedNodes"])
    }

    @Test fun `hidden ordinary container preserves visible payment completion descendants`() {
        val root = node("不可见正文").apply { isVisibleToUser = false }
        root.add(node("隐藏失败词 支付失败").apply {
            isVisibleToUser = false
            add(node(receipt))
        })
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals("ready", snapshot.status)
        assertEquals(2, snapshot.diagnostics["hiddenNodes"])
        assertNotNull(PaymentTextParser.parseScreen(packageName, snapshot.text!!))
    }

    @Test fun `more than 250 hidden nodes do not exhaust readable node budget`() {
        val root = node(receipt)
        repeat(1_200) { root.add(node("隐藏正文").apply { isVisibleToUser = false }) }
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals("ready", snapshot.status)
        assertEquals(1_200, snapshot.diagnostics["hiddenNodes"])
        assertEquals(1_201, snapshot.diagnostics["visitedNodes"])
        assertEquals(1, snapshot.diagnostics["textNodes"])
    }

    @Test fun `more than 250 visible nodes are read completely`() {
        val root = node(receipt)
        repeat(300) { root.add(node("正文$it")) }
        val snapshot = inspect(root)
        assertEquals("ready", snapshot.status)
        assertEquals(301, snapshot.diagnostics["textNodes"])
        assertTrue(snapshot.text!!.endsWith("正文299"))
        assertNotNull(PaymentTextParser.parseScreen(packageName, snapshot.text!!))
    }

    @Test fun `failed state beyond old limit is preserved for parser to reject`() {
        val root = node(receipt)
        repeat(300) { root.add(node("正文")) }
        root.add(node("支付失败"))
        val snapshot = inspect(root)
        assertEquals("ready", snapshot.status)
        assertTrue(snapshot.text!!.endsWith("支付失败"))
        assertNull(PaymentTextParser.parseScreen(packageName, snapshot.text!!))
    }

    @Test fun `readable node limit rejects whole page with a traversal reason`() {
        val root = node(receipt)
        repeat(999) { root.add(node(null)) }
        root.add(node("支付失败"))
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("traversal_limit", snapshot.status)
        assertEquals(1_001, snapshot.diagnostics["visitedNodes"])
    }

    @Test fun `hard node limit also bounds hidden nodes and rejects whole page`() {
        val root = node(receipt)
        repeat(2_000) { root.add(node(null).apply { isVisibleToUser = false }) }
        root.add(node("支付失败"))
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("traversal_limit", snapshot.status)
        assertEquals(2_001, snapshot.diagnostics["visitedNodes"])
    }

    @Test fun `depth limit rejects whole page with a traversal reason`() {
        val root = node(receipt)
        var current = root
        repeat(61) {
            val child = node(null)
            current.add(child)
            current = child
        }
        current.text = "支付失败"
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("traversal_limit", snapshot.status)
        assertEquals(62, snapshot.diagnostics["visitedNodes"])
    }

    @Test fun `depth 60 remains readable`() {
        val root = node(null)
        var current = root
        repeat(60) {
            val child = node(null)
            current.add(child)
            current = child
        }
        current.text = receipt
        val snapshot = inspect(root)
        assertEquals(receipt, snapshot.text)
        assertEquals("ready", snapshot.status)
    }

    @Test fun `character limit rejects whole page with text reason`() {
        val root = node("字".repeat(12_001))
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("text_limit", snapshot.status)
    }

    @Test fun `character limit includes separators between text nodes`() {
        val root = node("字".repeat(11_999))
        root.add(node("尾"))
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertEquals("text_limit", snapshot.status)
    }

    @Test fun `exact character limit remains readable`() {
        val root = node("字".repeat(12_000))
        val snapshot = inspect(root)
        assertEquals(12_000, snapshot.text!!.length)
        assertEquals("ready", snapshot.status)
    }

    @Test
    @Config(shadows = [PaymentScreenReaderChildShadow::class])
    fun `permanently missing child rejects whole page with incomplete tree reason`() {
        val root = node(receipt)
        root.add(node("支付失败"))
        val shadow = Shadow.extract<PaymentScreenReaderChildShadow>(root).apply { nullReadsRemaining = 2 }
        val snapshot = inspect(root)
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("incomplete_tree", snapshot.status)
        assertEquals(1, snapshot.diagnostics["missingChildren"])
        assertEquals(2, shadow.childReads)
    }

    @Test
    @Config(shadows = [PaymentScreenReaderChildShadow::class])
    fun `one child retry recovers transient null without losing tail failure`() {
        val root = node(receipt)
        root.add(node("支付失败"))
        val shadow = Shadow.extract<PaymentScreenReaderChildShadow>(root).apply { nullReadsRemaining = 1 }
        val snapshot = inspect(root)
        assertEquals("ready", snapshot.status)
        assertEquals("$receipt\n支付失败", snapshot.text)
        assertEquals(0, snapshot.diagnostics["missingChildren"])
        assertEquals(2, shadow.childReads)
        assertNull(PaymentTextParser.parseScreen(packageName, snapshot.text!!))
    }

    @Test fun `empty page has distinct reason and numeric diagnostics only`() {
        val snapshot = inspect(node("  "))
        assertNull(snapshot.text)
        assertFalse(snapshot.hasInput)
        assertEquals("empty_page", snapshot.status)
        assertEquals(
            mapOf(
                "visitedNodes" to 1, "textNodes" to 0, "editableNodes" to 0,
                "passwordNodes" to 0, "missingChildren" to 0, "hiddenNodes" to 0,
                "foreignNodes" to 0
            ),
            snapshot.diagnostics
        )
    }
}

/** 模拟树更新期间的短暂或持续缺失，不改动生产读取接口。 */
@Implements(AccessibilityNodeInfo::class)
class PaymentScreenReaderChildShadow : ShadowAccessibilityNodeInfo() {
    var nullReadsRemaining = 0
    var childReads = 0

    @Implementation
    protected override fun getChild(index: Int): AccessibilityNodeInfo? {
        childReads++
        if (nullReadsRemaining > 0) {
            nullReadsRemaining--
            return null
        }
        return super.getChild(index)
    }
}

package com.pennypilot.app.data.llm

import org.junit.Assert.*
import org.junit.Test

class ModelRoleRoutingTest {
    @Test fun `explicit visual provider is independent of default text provider`() {
        assertEquals("deepseek", ModelRoleRouting.provider("deepseek", ""))
        assertEquals("qwen", ModelRoleRouting.provider("deepseek", "qwen"))
        assertEquals("qwen", ModelRoleRouting.provider("chatgpt", "qwen"))
        assertEquals("chatgpt", ModelRoleRouting.provider("deepseek", "chatgpt"))
    }
    @Test fun `unknown explicit recipient is rejected without fallback`() {
        try { ModelRoleRouting.provider("qwen", "unknown"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `provider model account and key changes invalidate pinned identity`() {
        val initial = ModelRoleRouting.identity(listOf("qwen", "key-A", "default", "https://example.com"), ModelRole.VISION, "qwen-vl")
        listOf(listOf("deepseek", "key-A", "default", "https://example.com"),
            listOf("qwen", "key-B", "default", "https://example.com"),
            listOf("qwen", "key-A", "default", "https://other.example.com")).forEach {
            assertNotEquals(initial, ModelRoleRouting.identity(it, ModelRole.VISION, "qwen-vl"))
        }
        assertNotEquals(initial, ModelRoleRouting.identity(listOf("qwen", "key-A", "default", "https://example.com"), ModelRole.VISION, "other-vision"))
        assertNotEquals(ModelRoleRouting.identity(listOf("chatgpt", "account-A"), ModelRole.TEXT, "model"),
            ModelRoleRouting.identity(listOf("chatgpt", "account-B"), ModelRole.TEXT, "model"))
        assertFalse(initial.contains("key-A")); assertEquals(64, initial.length)
    }
}

package com.pennypilot.app.data.llm

import java.security.MessageDigest

/** Explicit role providers never silently fall back to a different data recipient. */
object ModelRoleRouting {
    fun provider(active: String, override: String): String {
        require(override in setOf("", "chatgpt", "deepseek", "qwen")) { "识别服务商无效" }
        return override.ifBlank { active }
    }
    internal fun identity(configuration: List<String>, role: ModelRole, overrideModel: String): String {
        val value = (configuration + role.name + overrideModel).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

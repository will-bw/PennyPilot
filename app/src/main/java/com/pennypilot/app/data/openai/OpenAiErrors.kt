package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.UserFacingLlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only curated messages and bounded diagnostic identifiers may reach the UI. */
internal object OpenAiErrors {
    fun notSignedIn() = UserFacingLlmException("请先到「AI 设置」登录 ChatGPT 账号。")
    fun planNotGranted() = UserFacingLlmException("当前账号尚未授权使用 ChatGPT 套餐，请到「AI 设置」开启套餐授权。")
    fun accountChanged() = UserFacingLlmException("ChatGPT 账号或登录状态已变化，本次结果已取消，请重新发起请求。")
    fun noModel() = UserFacingLlmException("请先到「AI 设置」刷新并选择当前 ChatGPT 账号的模型。")
    fun protocol(detail: String, requestId: String? = null) = UserFacingLlmException(
        "ChatGPT 返回的数据未通过完整性检查（$detail），请稍后重试。" + diagnostic(requestId = requestId)
    )
    fun network() = UserFacingLlmException("连接 ChatGPT 中断或超时，请检查网络后重试。本次请求不会自动重发。")

    fun unexpectedResponse(status: Int, contentType: String?, body: String, requestId: String?): UserFacingLlmException {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (root?.get("error") is JsonObject) return http(status, body, requestId)
        val shape = when {
            body.contains("cf-chl-", ignoreCase = true) || body.contains("challenge-platform", ignoreCase = true) -> "html_challenge"
            body.trimStart().startsWith("<") -> "html"
            root?.containsKey("detail") == true -> "detail"
            root != null -> "json"
            body.startsWith("data:") || body.startsWith("event:") -> "sse_wrong_content_type"
            body.isEmpty() -> "empty_or_oversized"
            else -> "other"
        }
        val safeType = contentType?.takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) } ?: "missing"
        return UserFacingLlmException("ChatGPT 未返回预期的事件流，请检查服务连接。" +
            diagnostic(status = status, requestId = requestId, shape = shape) + " · content_type=$safeType")
    }

    fun http(status: Int, body: String, requestId: String?): UserFacingLlmException {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val shape = when {
            error != null -> "error"
            root?.containsKey("detail") == true -> "detail"
            root != null -> "json"
            else -> "other"
        }
        return service(status, error?.string("code"), error?.string("param"), requestId, shape)
    }

    fun stream(error: JsonObject?, requestId: String?): UserFacingLlmException =
        service(null, error?.string("code"), error?.string("param"), requestId, "stream")

    private fun service(
        status: Int?, code: String?, param: String?, requestId: String?, shape: String
    ): UserFacingLlmException {
        val message = when (code) {
            "subscription_sharing_user_not_eligible" ->
                "当前 ChatGPT 账号、工作区或政策不支持套餐共享，请检查账号和工作区权限。"
            "subscription_sharing_usage_limit_exceeded" ->
                "当前应用可用的 ChatGPT 套餐额度已达到限制，请到 ChatGPT「设置 → 用量」查看后再试。"
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                "ChatGPT 暂时无法确认套餐用量或账号状态，请稍后重试。登录信息已保留。"
            "subscription_sharing_unsupported_capability" ->
                "当前 ChatGPT 模型或功能不支持此请求，请检查所选模型和请求功能。"
            "subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" ->
                "当前 ChatGPT 授权或接入配置不支持此操作，请检查套餐授权与应用配置。"
            "subscription_sharing_invalid_user" ->
                "ChatGPT 无法验证当前账号，请到「AI 设置」检查登录和套餐授权。"
            else -> when (status) {
                401 -> "ChatGPT 未接受当前登录授权，请到「AI 设置」检查账号并重新登录。"
                403 -> "当前账号、工作区或服务地区不允许此 ChatGPT 请求，请检查权限。"
                429 -> "ChatGPT 请求频率或可用额度已达限制，请稍后重试或查看套餐用量。"
                in 500..599 -> "ChatGPT 服务暂时不可用，请稍后重试。登录信息已保留。"
                else -> "ChatGPT 未能完成此次请求，请检查模型与 AI 设置后重试。"
            }
        }
        return UserFacingLlmException(message + diagnostic(status, code, param, requestId, shape))
    }

    private fun diagnostic(
        status: Int? = null, code: String? = null, param: String? = null,
        requestId: String? = null, shape: String? = null
    ): String {
        val fields = buildList {
            status?.let { add("HTTP $it") }
            code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,95}")) }?.let { add("code=$it") }
            param?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,96}")) }?.let { add("param=$it") }
            requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) }?.let { add("request_id=$it") }
            shape?.let { add("response=$it") }
        }
        return if (fields.isEmpty()) "" else "\n" + fields.joinToString(" · ")
    }
}

internal fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

package com.pennypilot.app.data.llm

import kotlinx.serialization.json.*

/** Only user-selected local pixels are accepted. Remote URLs are never fetched implicitly. */
object MultimodalMessages {
    private const val MAX_TOTAL_CHARACTERS = 3 * 1024 * 1024
    fun validate(messages: List<Message>) {
        val images = messages.flatMap { it.imageDataUrls }
        require(images.size <= 4 && images.sumOf { it.length.toLong() } <= MAX_TOTAL_CHARACTERS) { "图片过大，请裁剪或压缩后重试" }
        messages.filter { it.imageDataUrls.isNotEmpty() }.forEach { message ->
            require(message.role == "user") { "图片只能作为用户凭证输入" }
            message.imageDataUrls.forEach { value ->
                require(Regex("^data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$").matches(value)) { "图片格式无效" }
            }
        }
    }

    fun compatibleMessage(json: Json, message: Message): JsonElement {
        if (message.imageDataUrls.isEmpty()) return json.encodeToJsonElement(Message.serializer(), message)
        return buildJsonObject {
            put("role", message.role)
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", message.content) })
                message.imageDataUrls.forEach { image -> add(buildJsonObject {
                    put("type", "image_url"); putJsonObject("image_url") { put("url", image) }
                }) }
            }
        }
    }

    fun request(json: Json, model: String, messages: List<Message>, jsonMode: Boolean): String {
        validate(messages)
        return buildJsonObject {
            put("model", model); put("temperature", 0.1)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") { messages.forEach { add(compatibleMessage(json, it)) } }
        }.toString()
    }
}

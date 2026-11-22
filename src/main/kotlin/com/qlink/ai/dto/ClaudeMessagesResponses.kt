@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class ClaudeErrorResponse(
    val error: Error? = null,
) {
    @Serializable
    data class Error(
        val type: String? = null,
        val details: Details? = null,
    )

    @Serializable
    data class Details(
        @SerialName("error_code") val errorCode: String? = null,
    )
}

@Serializable
internal data class ClaudeMessagesResponse(
    @SerialName("stop_reason") val stopReason: String? = null,
    val content: List<ContentBlock>,
    val usage: JsonElement? = null,
) {
    @Serializable
    data class ContentBlock(
        val type: JsonElement? = null,
        val name: JsonElement? = null,
        val id: JsonElement? = null,
        val input: JsonElement? = null,
        @SerialName("tool_use_id") val toolUseId: JsonElement? = null,
        val content: JsonElement? = null,
        val text: JsonElement? = null,
    )

    @Serializable
    data class FetchResult(
        val type: String,
        val url: JsonElement? = null,
        val content: JsonElement? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("input_tokens") val inputTokens: JsonPrimitive,
        @SerialName("output_tokens") val outputTokens: JsonPrimitive,
        @SerialName("cache_creation_input_tokens") val cacheCreation: JsonPrimitive? = null,
        @SerialName("cache_read_input_tokens") val cacheRead: JsonPrimitive? = null,
    )
}

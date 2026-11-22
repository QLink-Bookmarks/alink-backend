@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ClaudeMessagesRequest(
    val model: String,
    val system: String,
    @SerialName("max_tokens") val maxTokens: Long,
    val messages: List<Message>,
    val tools: List<WebFetch>,
    @SerialName("output_config") val outputConfig: OutputConfig,
) {
    @Serializable
    data class Message(
        val role: String,
        val content: String,
    )

    @Serializable
    data class WebFetch(
        val type: String,
        val name: String,
        @SerialName("max_uses") val maxUses: Long,
        @SerialName("allowed_callers") val allowedCallers: List<String>,
        @SerialName("allowed_domains") val allowedDomains: List<String>,
        val citations: Citations,
    )

    @Serializable
    data class Citations(
        val enabled: Boolean,
    )

    @Serializable
    data class OutputConfig(
        val format: OutputFormat,
    )

    @Serializable
    data class OutputFormat(
        val type: String,
        val schema: JsonObject,
    )
}

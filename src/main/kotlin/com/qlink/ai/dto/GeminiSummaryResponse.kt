package com.qlink.ai.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class GeminiSummaryResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
    val usageMetadata: JsonElement? = null,
    val error: JsonElement? = null,
) {
    @Serializable
    data class PromptFeedback(
        val blockReason: String? = null,
    )

    @Serializable
    data class Candidate(
        val content: Content? = null,
        val finishReason: String? = null,
    )

    @Serializable
    data class Content(
        val parts: List<Part> = emptyList(),
    )

    @Serializable
    data class Part(
        val text: String? = null,
        val thought: Boolean = false,
    )
}

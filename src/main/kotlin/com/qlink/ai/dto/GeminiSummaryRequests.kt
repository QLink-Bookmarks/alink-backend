package com.qlink.ai.dto

import com.qlink.ai.client.AiSummarySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class GeminiGenerateContentRequest(
    @SerialName("system_instruction")
    val systemInstruction: GeminiContent,
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>,
    @SerialName("generationConfig")
    val generationConfig: GeminiGenerationConfig,
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart>,
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
)

@Serializable
internal data class GeminiTool(
    @SerialName("url_context")
    val urlContext: Map<String, String>,
)

@Serializable
internal data class GeminiGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseJsonSchema: JsonObject = AiSummarySpec.geminiResponseSchema,
)

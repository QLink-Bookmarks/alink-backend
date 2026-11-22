package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType

data class AiSummaryPrompt(
    val linkId: Long,
    val url: String,
)

data class AiSummaryClientRequest(
    val providerType: AiProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val prompt: String,
    val requestedUrl: String? = null,
)

data class AiApiKeyValidationRequest(
    val baseUrl: String,
    val apiKey: String,
)

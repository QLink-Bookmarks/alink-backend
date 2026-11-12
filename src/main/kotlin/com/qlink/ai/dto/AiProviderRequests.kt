@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType
import kotlinx.serialization.Serializable

@Serializable
data class CreateAiProviderRequest(
    val type: AiProviderType,
    val baseUrl: String,
)

@Serializable
data class UpdateAiProviderRequest(
    val baseUrl: String,
)

@Serializable
data class UpdateAiActiveRequest(
    val isActive: Boolean,
)

@Serializable
data class PutAiUserProviderRequest(
    val providerId: Long,
    val apiKey: String,
)

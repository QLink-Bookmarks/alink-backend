@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AiProviderResponse(
    val id: Long,
    val type: AiProviderType,
    val isActive: Boolean,
    val isExecutable: Boolean,
    val modelCount: Int,
    val activeModelCount: Int,
    val updatedAt: Instant,
)

@Serializable
data class AiProviderDetailResponse(
    val id: Long,
    val type: AiProviderType,
    val baseUrl: String,
    val isActive: Boolean,
    val isExecutable: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val models: List<AdminAiModelResponse>,
)

@Serializable
data class AdminAiModelResponse(
    val id: Long,
    val model: String,
    val priority: Int,
    val rpdLimit: Int?,
    val tpdLimit: Int?,
    val isActive: Boolean,
    val updatedAt: Instant,
)

@Serializable
data class CreateAiResourceResponse(
    val id: Long,
)

@Serializable
data class UpdateAiProviderResponse(
    val id: Long,
    val baseUrl: String,
    val updatedAt: Instant,
)

@Serializable
data class UpdateAiActiveResponse(
    val id: Long,
    val isActive: Boolean,
    val updatedAt: Instant,
)

@Serializable
data class PutAiUserProviderResponse(
    val id: Long,
)

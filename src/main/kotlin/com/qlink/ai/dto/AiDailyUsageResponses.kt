@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType
import kotlinx.serialization.Serializable

@Serializable
data class AiDailyUsageResponse(
    val date: String,
    val items: List<AiDailyUsageItemResponse>,
)

@Serializable
data class AiDailyUsageItemResponse(
    val providerId: Long,
    val providerType: AiProviderType,
    val modelId: Long,
    val model: String,
    val requests: Long,
    val tokens: Long,
)

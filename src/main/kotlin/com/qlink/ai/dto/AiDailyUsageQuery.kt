package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.repository.table.AiProviders
import com.qlink.ai.repository.table.AvailableModels
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow

data class AiDailyUsageQuery(
    val providerId: Long,
    val providerType: AiProviderType,
    val modelId: Long,
    val model: String,
    val requests: Long,
    val tokens: Long,
)

fun ResultRow.toAiDailyUsageQuery(
    requests: Expression<Long?>,
    tokens: Expression<Long?>,
): AiDailyUsageQuery =
    AiDailyUsageQuery(
        providerId = this[AiProviders.id],
        providerType = this[AiProviders.type],
        modelId = this[AvailableModels.id],
        model = this[AvailableModels.model],
        requests = this[requests] ?: 0L,
        tokens = this[tokens] ?: 0L,
    )

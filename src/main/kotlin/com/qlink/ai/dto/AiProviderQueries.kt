@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.repository.table.AiProviders
import com.qlink.ai.repository.table.AvailableModels
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class AiProviderQuery(
    val id: Long,
    val type: AiProviderType,
    val baseUrl: String,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AiModelQuery(
    val id: Long,
    val model: String,
    val priority: Int,
    val rpdLimit: Int?,
    val tpdLimit: Int?,
    val isActive: Boolean,
    val updatedAt: Instant,
)

fun ResultRow.toAiProviderQuery(): AiProviderQuery =
    AiProviderQuery(
        id = this[AiProviders.id],
        type = this[AiProviders.type],
        baseUrl = this[AiProviders.baseUrl],
        isActive = this[AiProviders.isActive],
        createdAt = this[AiProviders.createdAt].toKotlinInstant(),
        updatedAt = this[AiProviders.updatedAt].toKotlinInstant(),
    )

fun ResultRow.toAiModelQuery(): AiModelQuery =
    AiModelQuery(
        id = this[AvailableModels.id],
        model = this[AvailableModels.model],
        priority = this[AvailableModels.priority],
        rpdLimit = this[AvailableModels.rpdLimit],
        tpdLimit = this[AvailableModels.tpdLimit],
        isActive = this[AvailableModels.isActive],
        updatedAt = this[AvailableModels.updatedAt].toKotlinInstant(),
    )

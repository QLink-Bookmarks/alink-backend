package com.qlink.ai.domain

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import kotlin.time.Instant

data class AiProvider(
    val id: Long? = null,
    val type: AiProviderType,
    val baseUrl: String,
    val isActive: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun changeBaseUrl(
        baseUrl: String,
        now: Instant,
    ): AiProvider {
        type.isAllowedBaseUrl(baseUrl).requireTrue(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        return copy(baseUrl = baseUrl.trimEnd('/'), updatedAt = now)
    }

    fun changeActive(
        isActive: Boolean,
        isExecutable: Boolean,
        now: Instant,
    ): AiProvider {
        if (this.isActive == isActive) return this
        (!isActive || isExecutable).requireTrue(ErrorCode.AI_PROVIDER_CLIENT_MISSING)
        return copy(isActive = isActive, updatedAt = now)
    }

    fun validateActive() {
        isActive.requireTrue(ErrorCode.AI_PROVIDER_INACTIVE)
    }

    companion object {
        fun create(
            type: AiProviderType,
            baseUrl: String,
        ): AiProvider {
            type.isAllowedBaseUrl(baseUrl).requireTrue(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
            return AiProvider(type = type, baseUrl = baseUrl.trimEnd('/'), isActive = false)
        }
    }
}

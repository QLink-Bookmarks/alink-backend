package com.qlink.ai.service

import com.qlink.ai.client.AiApiKeyValidationException
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.aiVendorErrorCode
import com.qlink.ai.domain.AiProvider
import com.qlink.common.coroutines.catchingFailure
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode

class AiApiKeyValidator(
    private val aiClientRouter: AiClientRouter,
) {
    suspend fun validate(
        provider: AiProvider,
        apiKey: String,
    ) {
        catchingFailure {
            aiClientRouter.validateApiKey(
                providerType = provider.type,
                baseUrl = provider.baseUrl,
                apiKey = apiKey,
            )
        }.onFailure {
            throw when (it) {
                is BusinessException -> it
                is AiApiKeyValidationException -> BusinessException(aiVendorErrorCode(it.statusCode))
                else -> BusinessException(ErrorCode.COMMON_INTERNAL_SERVER_ERROR)
            }
        }
    }
}

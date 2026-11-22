package com.qlink.ai.service

import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.PutAiUserProviderResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.user.repository.UserRepository

class PutAiUserProviderService(
    private val tx: TransactionRunner,
    private val userRepository: UserRepository,
    private val aiProviderRepository: AiProviderRepository,
    private val userProviderRepository: UserProviderRepository,
    private val apiKeyValidator: AiApiKeyValidator,
    private val apiKeyCipher: ApiKeyCipher,
) {
    suspend fun putAiUserProvider(
        loginId: Long,
        request: PutAiUserProviderRequest,
    ): PutAiUserProviderResponse =
        tx.required {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.USER_NOT_FOUND)
            val provider =
                aiProviderRepository.findById(request.providerId)
                    ?: throw BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)

            provider.validateActive()
            apiKeyValidator.validate(provider = provider, apiKey = request.apiKey)

            val userProvider =
                UserProvider(
                    userId = loginId,
                    providerId = provider.id!!,
                    apiKey = apiKeyCipher.encrypt(request.apiKey),
                )

            val saved = userProviderRepository.save(userProvider)
            PutAiUserProviderResponse(id = saved.id!!)
        }
}

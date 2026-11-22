package com.qlink.ai.service

import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.PutAiSharedKeyRequest
import com.qlink.ai.dto.PutAiSharedKeyResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner

class PutAiSharedKeyService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val aiProviderRepository: AiProviderRepository,
    private val userProviderRepository: UserProviderRepository,
    private val apiKeyValidator: AiApiKeyValidator,
    private val apiKeyCipher: ApiKeyCipher,
) {
    suspend fun putAiSharedKey(
        loginId: Long,
        providerId: Long,
        request: PutAiSharedKeyRequest,
    ): PutAiSharedKeyResponse =
        tx.required {
            adminValidator.validate(loginId)
            val provider =
                aiProviderRepository.findByIdForUpdate(providerId) ?: throw BusinessException(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND)

            (request.apiKey.length > 4 && request.apiKey.isNotBlank()).requireTrue(ErrorCode.AI_API_KEY_INVALID)
            apiKeyValidator.validate(provider = provider, apiKey = request.apiKey)
            val encryptedKey = apiKeyCipher.encrypt(request.apiKey)

            val existing = userProviderRepository.findDefaultByProviderId(providerId)
            if (existing == null) {
                userProviderRepository.insert(
                    UserProvider(
                        userId = null,
                        providerId = providerId,
                        userRole = Role.SUPER_ADMIN,
                        apiKey = encryptedKey,
                    ),
                )
            } else {
                userProviderRepository.update(existing.replaceApiKey(encryptedKey))
            }
            PutAiSharedKeyResponse(request.apiKey.takeLast(4))
        }
}

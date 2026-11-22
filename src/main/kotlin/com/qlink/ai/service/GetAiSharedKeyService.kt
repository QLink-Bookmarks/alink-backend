package com.qlink.ai.service

import com.qlink.ai.dto.AiSharedKeyResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner

class GetAiSharedKeyService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val aiProviderRepository: AiProviderRepository,
    private val userProviderRepository: UserProviderRepository,
    private val apiKeyCipher: ApiKeyCipher,
) {
    suspend fun getAiSharedKey(
        loginId: Long,
        providerId: Long,
    ): AiSharedKeyResponse =
        tx.readOnly {
            adminValidator.validate(loginId)
            aiProviderRepository.findQuery(providerId) ?: throw BusinessException(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND)

            val lastFour =
                userProviderRepository.findDefaultByProviderId(providerId)?.let { credential ->
                    val key =
                        apiKeyCipher.decrypt(credential.apiKey)?.takeIf { it.length > 4 }
                            ?: throw BusinessException(ErrorCode.AI_API_KEY_MISSING)
                    key.takeLast(4)
                }
            AiSharedKeyResponse(lastFour)
        }
}

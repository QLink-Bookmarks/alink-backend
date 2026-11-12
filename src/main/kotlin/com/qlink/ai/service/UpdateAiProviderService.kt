package com.qlink.ai.service

import com.qlink.ai.domain.AiAdminPolicy
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAiProviderResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class UpdateAiProviderService(
    private val tx: TransactionRunner,
    private val adminUserLoader: AiAdminUserLoader,
    private val aiProviderRepository: AiProviderRepository,
) {
    suspend fun updateAiProvider(
        loginId: Long,
        id: Long,
        request: UpdateAiProviderRequest,
    ): UpdateAiProviderResponse =
        tx.required {
            AiAdminPolicy.validate(adminUserLoader.load(loginId).role.isAdmin)
            val provider = aiProviderRepository.findById(id) ?: throw BusinessException(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND)
            val saved = aiProviderRepository.update(provider.changeBaseUrl(baseUrl = request.baseUrl, now = Clock.System.now()))
            UpdateAiProviderResponse(id = saved.id!!, baseUrl = saved.baseUrl, updatedAt = saved.updatedAt!!)
        }
}

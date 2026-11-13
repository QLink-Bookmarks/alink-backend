package com.qlink.ai.service

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAiResourceResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner

class CreateAiProviderService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val aiProviderRepository: AiProviderRepository,
) {
    suspend fun createAiProvider(
        loginId: Long,
        request: CreateAiProviderRequest,
    ): CreateAiResourceResponse =
        tx.required {
            adminValidator.validate(loginId)
            if (aiProviderRepository.findByType(request.type) != null) throw BusinessException(ErrorCode.AI_PROVIDER_DUPLICATE_TYPE)
            val saved = aiProviderRepository.insert(AiProvider.create(type = request.type, baseUrl = request.baseUrl))
            CreateAiResourceResponse(id = saved.id!!)
        }
}

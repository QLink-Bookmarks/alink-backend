package com.qlink.ai.service

import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.dto.CreateAiResourceResponse
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class CreateAvailableModelService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val aiProviderRepository: AiProviderRepository,
    private val availableModelRepository: AvailableModelRepository,
) {
    suspend fun createAvailableModel(
        loginId: Long,
        request: CreateAvailableModelRequest,
    ): CreateAiResourceResponse =
        tx.required {
            adminValidator.validate(loginId)
            aiProviderRepository.findById(request.providerId) ?: throw BusinessException(ErrorCode.AI_ADMIN_MODEL_PROVIDER_NOT_FOUND)
            val model =
                AvailableModel.create(
                    providerId = request.providerId,
                    model = request.model,
                    priority = request.priority,
                    rpdLimit = request.rpdLimit,
                    tpdLimit = request.tpdLimit,
                    now = Clock.System.now(),
                )
            if (availableModelRepository.findAllByProviderId(request.providerId).any {
                    it.model == request.model
                }
            ) {
                throw BusinessException(ErrorCode.AI_MODEL_DUPLICATE)
            }
            val saved = availableModelRepository.insert(model)
            CreateAiResourceResponse(id = saved.id!!)
        }
}

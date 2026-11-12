package com.qlink.ai.service

import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.domain.AiAdminPolicy
import com.qlink.ai.dto.AdminAiModelResponse
import com.qlink.ai.dto.AiProviderDetailResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner

class GetAiProviderService(
    private val tx: TransactionRunner,
    private val adminUserLoader: AiAdminUserLoader,
    private val aiProviderRepository: AiProviderRepository,
    private val availableModelRepository: AvailableModelRepository,
    private val aiClientRouter: AiClientRouter,
) {
    suspend fun getAiProvider(
        loginId: Long,
        id: Long,
    ): AiProviderDetailResponse =
        tx.readOnly {
            AiAdminPolicy.validate(adminUserLoader.load(loginId).role.isAdmin)
            val row = aiProviderRepository.findQuery(id) ?: throw BusinessException(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND)
            AiProviderDetailResponse(
                id = row.id,
                type = row.type,
                baseUrl = row.baseUrl,
                isActive = row.isActive,
                isExecutable = aiClientRouter.isExecutable(row.type),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                models =
                    availableModelRepository.findQueries(id).map {
                        AdminAiModelResponse(
                            id = it.id,
                            model = it.model,
                            priority = it.priority,
                            rpdLimit = it.rpdLimit,
                            tpdLimit = it.tpdLimit,
                            isActive = it.isActive,
                            updatedAt = it.updatedAt,
                        )
                    },
            )
        }
}

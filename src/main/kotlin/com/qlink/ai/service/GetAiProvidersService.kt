package com.qlink.ai.service

import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.domain.AiAdminPolicy
import com.qlink.ai.dto.AiProviderResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.transaction.TransactionRunner

class GetAiProvidersService(
    private val tx: TransactionRunner,
    private val aiProviderRepository: AiProviderRepository,
    private val availableModelRepository: AvailableModelRepository,
    private val adminUserLoader: AiAdminUserLoader,
    private val aiClientRouter: AiClientRouter,
) {
    suspend fun getAiProviders(
        loginId: Long? = null,
        includesInactive: Boolean = false,
    ): List<AiProviderResponse> =
        tx.readOnly {
            if (includesInactive) AiAdminPolicy.validate(adminUserLoader.load(loginId).role.isAdmin)
            aiProviderRepository
                .findQueries(includesInactive)
                .map {
                    val models = availableModelRepository.findQueries(it.id)
                    AiProviderResponse(
                        id = it.id,
                        type = it.type,
                        isActive = it.isActive,
                        isExecutable = aiClientRouter.isExecutable(it.type),
                        modelCount = models.size,
                        activeModelCount = models.count { model -> model.isActive },
                        updatedAt = it.updatedAt,
                    )
                }
        }
}

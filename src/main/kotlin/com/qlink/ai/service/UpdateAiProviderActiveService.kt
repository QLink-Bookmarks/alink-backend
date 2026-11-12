package com.qlink.ai.service

import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.domain.AiAdminPolicy
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiActiveResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class UpdateAiProviderActiveService(
    private val tx: TransactionRunner,
    private val adminUserLoader: AiAdminUserLoader,
    private val aiProviderRepository: AiProviderRepository,
    private val aiClientRouter: AiClientRouter,
) {
    suspend fun updateAiProviderActive(
        loginId: Long,
        id: Long,
        request: UpdateAiActiveRequest,
    ): UpdateAiActiveResponse =
        tx.required {
            AiAdminPolicy.validate(adminUserLoader.load(loginId).role.isAdmin)
            val provider = aiProviderRepository.findById(id) ?: throw BusinessException(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND)
            val changed =
                provider.changeActive(
                    isActive = request.isActive,
                    isExecutable = aiClientRouter.isExecutable(provider.type),
                    now = Clock.System.now(),
                )
            val saved = if (changed === provider) provider else aiProviderRepository.update(changed)
            UpdateAiActiveResponse(id = saved.id!!, isActive = saved.isActive, updatedAt = saved.updatedAt!!)
        }
}

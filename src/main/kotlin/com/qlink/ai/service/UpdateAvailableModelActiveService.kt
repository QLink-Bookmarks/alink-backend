package com.qlink.ai.service

import com.qlink.ai.domain.AiAdminPolicy
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiActiveResponse
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class UpdateAvailableModelActiveService(
    private val tx: TransactionRunner,
    private val adminUserLoader: AiAdminUserLoader,
    private val availableModelRepository: AvailableModelRepository,
) {
    suspend fun updateAvailableModelActive(
        loginId: Long,
        id: Long,
        request: UpdateAiActiveRequest,
    ): UpdateAiActiveResponse =
        tx.required {
            AiAdminPolicy.validate(adminUserLoader.load(loginId).role.isAdmin)
            val model = availableModelRepository.findById(id) ?: throw BusinessException(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND)
            val changed = model.changeActive(isActive = request.isActive, now = Clock.System.now())
            val saved = if (changed === model) model else availableModelRepository.update(changed)
            UpdateAiActiveResponse(id = saved.id!!, isActive = saved.isActive, updatedAt = saved.updatedAt!!)
        }
}

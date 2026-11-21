package com.qlink.notification.worker.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.notification.domain.Notification
import com.qlink.notification.repository.NotificationRepository
import kotlin.time.Clock

class MarkNotificationScheduledService(
    private val tx: TransactionRunner,
    private val notificationRepository: NotificationRepository,
) {
    suspend fun markScheduled(notificationId: Long): Notification =
        tx.required {
            val pending =
                notificationRepository.findPendingById(notificationId)
                    ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
            notificationRepository.update(pending.markScheduled(Clock.System.now()))
        }
}

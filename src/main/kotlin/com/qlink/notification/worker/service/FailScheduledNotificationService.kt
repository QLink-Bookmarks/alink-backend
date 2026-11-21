package com.qlink.notification.worker.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.notification.repository.NotificationRepository
import kotlin.time.Clock

class FailScheduledNotificationService(
    private val tx: TransactionRunner,
    private val notificationRepository: NotificationRepository,
) {
    suspend fun fail(notificationId: Long) {
        tx.required {
            val notification = notificationRepository.findPendingById(notificationId) ?: return@required
            notificationRepository.update(notification.markFailed(Clock.System.now()))
        }
    }
}

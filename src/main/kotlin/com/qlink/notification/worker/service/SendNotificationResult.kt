package com.qlink.notification.worker.service

data class SendNotificationResult(
    val notificationId: Long,
    val successCount: Int,
    val failureCount: Int,
)

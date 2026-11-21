package com.qlink.notification.dto

data class SendNotificationResult(
    val notificationId: Long,
    val successCount: Int,
    val failureCount: Int,
)

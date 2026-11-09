@file:Suppress("ktlint:standard:filename")

package com.qlink.notification.dto

import com.qlink.common.search.SortCursor
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.domain.NotificationOrderKey
import kotlinx.serialization.Serializable
import kotlin.time.Instant

const val DEFAULT_NOTIFICATION_SCROLL_SIZE = 30

typealias NotificationSearchCursor = SortCursor<NotificationOrderKey, NotificationSearchCursorValue>

@Serializable
data class NotificationSearchCursorValue(
    val id: Long? = null,
    val type: NotificationContext? = null,
    val firedAt: Instant? = null,
)

data class SearchNotificationsQuery(
    val id: Long,
    val title: String,
    val message: String,
    val firedAt: Instant,
    val readAt: Instant?,
    val context: NotificationContext,
    val contextId: Long,
)

package com.qlink.notification.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireFalse
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.transaction.TransactionRunner
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.domain.NotificationOrderKey
import com.qlink.notification.domain.NotificationSort
import com.qlink.notification.dto.DEFAULT_NOTIFICATION_SCROLL_SIZE
import com.qlink.notification.dto.GetNotificationsContentResponse
import com.qlink.notification.dto.NotificationSearchCursor
import com.qlink.notification.dto.NotificationSearchCursorValue
import com.qlink.notification.dto.SearchNotificationsQuery
import com.qlink.notification.repository.NotificationRepository
import com.qlink.user.repository.UserRepository

class GetNotificationsService(
    private val tx: TransactionRunner,
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
) {
    suspend fun getNotifications(
        loginId: Long,
        query: String?,
        type: String? = null,
        orderKey: String? = null,
        order: String? = null,
        scrollRequest: ScrollRequest,
    ): ScrollResponse<GetNotificationsContentResponse> =
        tx.readOnly {
            userRepository.emptyById(loginId).requireFalse(ErrorCode.USER_NOT_FOUND)

            val typeFilter = type?.let { NotificationContext.from(it) ?: throw BusinessException(ErrorCode.COMMON_INVALID_FILTER) }
            val sort = NotificationSort.of(orderKey = orderKey, order = order)
            val cursor = scrollRequest.cursor?.let { decodeCursor(encoded = it, sort = sort) }
            val size = scrollRequest.size.takeIf { it > 0 } ?: DEFAULT_NOTIFICATION_SCROLL_SIZE
            val queries =
                notificationRepository.search(
                    userId = loginId,
                    query = query,
                    type = typeFilter,
                    sort = sort,
                    cursor = cursor,
                    size = size,
                )
            val hasNext = queries.size > size
            val contents = queries.take(size)

            ScrollResponse(
                isEmpty = contents.isEmpty(),
                contents = contents.map { it.toResponse() },
                nextCursor = contents.lastOrNull()?.takeIf { hasNext }?.let { encodeCursor(query = it, type = typeFilter, sort = sort) },
                hasNext = hasNext,
            )
        }

    private suspend fun decodeCursor(
        encoded: String,
        sort: NotificationSort,
    ): NotificationSearchCursor {
        val cursor = Base64CursorCodec.decode<NotificationSearchCursor>(encoded)
        sort.validateCursor(orderKey = cursor.orderKey, direction = cursor.direction, order = cursor.order)
        val id = cursor.value.id ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)

        if (sort.orderKey == NotificationOrderKey.FIRED_AT) {
            cursor.value.firedAt ?: throw BusinessException(ErrorCode.COMMON_CURSOR_FIELD_MISSING)
            return cursor
        }

        if (cursor.value.createdAt != null) {
            return cursor
        }

        val createdAt = notificationRepository.findCreatedAtById(id) ?: throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED)

        return cursor.copy(value = cursor.value.copy(createdAt = createdAt))
    }

    private fun encodeCursor(
        query: SearchNotificationsQuery,
        type: NotificationContext?,
        sort: NotificationSort,
    ): String =
        Base64CursorCodec.encode(
            NotificationSearchCursor(
                orderKey = sort.orderKey,
                direction = sort.direction,
                value =
                    NotificationSearchCursorValue(
                        id = query.id,
                        type = type,
                        createdAt = query.createdAt,
                        firedAt = query.firedAt,
                    ),
            ),
        )

    private fun SearchNotificationsQuery.toResponse(): GetNotificationsContentResponse =
        GetNotificationsContentResponse(
            id = id,
            title = title,
            message = message,
            firedAt = firedAt,
            readAt = readAt,
            context = context,
            contextId = contextId,
        )
}

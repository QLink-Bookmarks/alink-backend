package com.qlink.notification.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection

class NotificationSort private constructor(
    val orderKey: NotificationOrderKey,
    val direction: SortDirection,
) {
    fun validateCursor(
        orderKey: NotificationOrderKey?,
        direction: SortDirection?,
        order: SearchOrder?,
    ) {
        val cursorSort =
            if (orderKey != null && direction != null) {
                NotificationSort(orderKey = orderKey, direction = direction)
            } else {
                order?.let { legacyOf(it) }
            }

        (cursorSort != null && cursorSort.orderKey == this.orderKey && cursorSort.direction == this.direction)
            .requireTrue(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH)
    }

    companion object {
        private val DEFAULT = NotificationSort(orderKey = NotificationOrderKey.CREATED_AT, direction = SortDirection.DESC)

        fun of(
            orderKey: String?,
            order: String?,
        ): NotificationSort {
            val requestedKey = orderKey?.takeIf { it.isNotBlank() }
            val requestedOrder = order?.takeIf { it.isNotBlank() }

            return when {
                requestedKey != null -> ofKey(orderKey = requestedKey, order = requestedOrder)
                requestedOrder != null -> ofOrder(requestedOrder)
                else -> DEFAULT
            }
        }

        private fun ofKey(
            orderKey: String,
            order: String?,
        ): NotificationSort {
            val key = NotificationOrderKey.from(orderKey) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)
            val direction =
                order
                    ?.let { SortDirection.from(it) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER) }
                    ?: key.defaultDirection

            return NotificationSort(orderKey = key, direction = direction)
        }

        private fun ofOrder(order: String): NotificationSort =
            SortDirection.from(order)?.let { NotificationSort(orderKey = NotificationOrderKey.CREATED_AT, direction = it) }
                ?: SearchOrder.from(order)?.let { legacyOf(it) }
                ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)

        private fun legacyOf(order: SearchOrder): NotificationSort? = DEFAULT.takeIf { order == SearchOrder.LATEST }
    }
}

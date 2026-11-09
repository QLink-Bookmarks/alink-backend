package com.qlink.post.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection

class PostSort private constructor(
    val orderKey: PostOrderKey,
    val direction: SortDirection,
) {
    fun validateCursor(
        orderKey: PostOrderKey?,
        direction: SortDirection?,
        order: SearchOrder?,
    ) {
        val cursorSort =
            if (orderKey != null && direction != null) {
                PostSort(orderKey = orderKey, direction = direction)
            } else {
                order?.let { legacyOf(it) }
            }

        (cursorSort != null && cursorSort.orderKey == this.orderKey && cursorSort.direction == this.direction)
            .requireTrue(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH)
    }

    companion object {
        private val DEFAULT = PostSort(orderKey = PostOrderKey.CREATED_AT, direction = SortDirection.DESC)

        fun of(
            orderKey: String?,
            order: String?,
        ): PostSort {
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
        ): PostSort {
            val key = PostOrderKey.from(orderKey) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)
            val direction =
                order
                    ?.let { SortDirection.from(it) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER) }
                    ?: key.defaultDirection

            return PostSort(orderKey = key, direction = direction)
        }

        private fun ofOrder(order: String): PostSort =
            SortDirection.from(order)?.let { PostSort(orderKey = PostOrderKey.CREATED_AT, direction = it) }
                ?: SearchOrder.from(order)?.let { legacyOf(it) }
                ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)

        private fun legacyOf(order: SearchOrder): PostSort? = DEFAULT.takeIf { order == SearchOrder.LATEST }
    }
}

package com.qlink.folder.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection

class FolderSort private constructor(
    val orderKey: FolderOrderKey,
    val direction: SortDirection,
) {
    fun validateCursor(
        orderKey: FolderOrderKey?,
        direction: SortDirection?,
        order: SearchOrder?,
    ) {
        val cursorSort =
            if (orderKey != null && direction != null) {
                FolderSort(orderKey = orderKey, direction = direction)
            } else {
                order?.let { legacyOf(it) }
            }

        (cursorSort != null && cursorSort.orderKey == this.orderKey && cursorSort.direction == this.direction)
            .requireTrue(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH)
    }

    companion object {
        private val DEFAULT = FolderSort(orderKey = FolderOrderKey.CREATED_AT, direction = SortDirection.DESC)

        fun of(
            orderKey: String?,
            order: String?,
        ): FolderSort {
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
        ): FolderSort {
            val key = FolderOrderKey.from(orderKey) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)
            val direction =
                order
                    ?.let { SortDirection.from(it) ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER) }
                    ?: key.defaultDirection
            (direction in key.directions).requireTrue(ErrorCode.COMMON_INVALID_SORT_ORDER)

            return FolderSort(orderKey = key, direction = direction)
        }

        private fun ofOrder(order: String): FolderSort =
            SortDirection.from(order)?.let { FolderSort(orderKey = FolderOrderKey.CREATED_AT, direction = it) }
                ?: SearchOrder.from(order)?.let { legacyOf(it) }
                ?: throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)

        private fun legacyOf(order: SearchOrder): FolderSort =
            when (order) {
                SearchOrder.LATEST -> DEFAULT
                SearchOrder.EARLIEST -> FolderSort(orderKey = FolderOrderKey.CREATED_AT, direction = SortDirection.ASC)
                SearchOrder.LAXICO -> FolderSort(orderKey = FolderOrderKey.NAME, direction = SortDirection.ASC)
                SearchOrder.SIMILAR -> FolderSort(orderKey = FolderOrderKey.SIMILARITY, direction = SortDirection.DESC)
            }
    }
}

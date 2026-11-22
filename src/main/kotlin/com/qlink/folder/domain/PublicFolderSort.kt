package com.qlink.folder.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection

class PublicFolderSort private constructor(
    val direction: SortDirection,
) {
    companion object {
        fun from(
            orderKey: String?,
            order: String?,
        ): PublicFolderSort {
            (orderKey == null || FolderOrderKey.from(orderKey) == FolderOrderKey.CREATED_AT)
                .requireTrue(ErrorCode.COMMON_INVALID_SORT_ORDER)

            if (order == null) return PublicFolderSort(SortDirection.DESC)
            SortDirection.from(order)?.let { return PublicFolderSort(it) }
            if (orderKey != null) throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)

            return when (SearchOrder.from(order)) {
                SearchOrder.LATEST -> PublicFolderSort(SortDirection.DESC)
                SearchOrder.EARLIEST -> PublicFolderSort(SortDirection.ASC)
                else -> throw BusinessException(ErrorCode.COMMON_INVALID_SORT_ORDER)
            }
        }
    }
}

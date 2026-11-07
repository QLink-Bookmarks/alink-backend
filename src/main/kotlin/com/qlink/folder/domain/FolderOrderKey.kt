package com.qlink.folder.domain

import com.qlink.common.search.SortDirection

enum class FolderOrderKey(
    val defaultDirection: SortDirection,
    val directions: Set<SortDirection>,
) {
    CREATED_AT(defaultDirection = SortDirection.DESC, directions = setOf(SortDirection.ASC, SortDirection.DESC)),
    NAME(defaultDirection = SortDirection.ASC, directions = setOf(SortDirection.ASC, SortDirection.DESC)),
    SIMILARITY(defaultDirection = SortDirection.DESC, directions = setOf(SortDirection.DESC)),
    ;

    companion object {
        fun from(value: String): FolderOrderKey? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

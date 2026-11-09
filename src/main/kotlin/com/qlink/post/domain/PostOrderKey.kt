package com.qlink.post.domain

import com.qlink.common.search.SortDirection

enum class PostOrderKey(
    val defaultDirection: SortDirection,
) {
    CREATED_AT(defaultDirection = SortDirection.DESC),
    ;

    companion object {
        fun from(value: String): PostOrderKey? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

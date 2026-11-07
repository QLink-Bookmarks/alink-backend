package com.qlink.notification.domain

import com.qlink.common.search.SortDirection

enum class NotificationOrderKey(
    val defaultDirection: SortDirection,
) {
    CREATED_AT(defaultDirection = SortDirection.DESC),
    FIRED_AT(defaultDirection = SortDirection.DESC),
    ;

    companion object {
        fun from(value: String): NotificationOrderKey? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

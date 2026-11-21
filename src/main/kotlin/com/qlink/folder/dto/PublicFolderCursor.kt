package com.qlink.folder.dto

import com.qlink.common.search.SortDirection
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class PublicFolderCursor(
    val publicId: String,
    val collection: String,
    val direction: SortDirection,
    val id: Long? = null,
    val createdAt: Instant? = null,
)

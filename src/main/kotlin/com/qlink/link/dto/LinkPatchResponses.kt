@file:Suppress("ktlint:standard:filename")

package com.qlink.link.dto

import com.qlink.todo.dto.LinkDetailTodoQuery
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class PatchLinkResponse(
    val folderId: Long?,
    val memo: String?,
    val tags: List<String>,
    val todos: List<LinkDetailTodoQuery>,
    val turnsPublicAt: Instant?,
)

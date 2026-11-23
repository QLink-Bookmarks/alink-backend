package com.qlink.ai.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AiSummaryResult(
    val id: Long? = null,
    val linkId: Long? = null,
    val folderId: Long? = null,
    val title: String,
    val summary: String,
    val tags: List<String> = emptyList(),
    val todos: List<AiSummaryTodoResult> = emptyList(),
)

@Serializable
internal data class AiSummaryTodoResult(
    val title: String,
    val reminderAt: String? = null,
)

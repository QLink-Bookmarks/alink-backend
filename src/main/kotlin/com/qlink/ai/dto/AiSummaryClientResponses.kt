package com.qlink.ai.dto

import kotlin.time.Instant

data class AiSummaryClientResponse(
    val linkId: Long?,
    val folderId: Long?,
    val rawResponse: String,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val todos: List<AiSummaryTodo>,
    val usedTokens: Int,
)

data class AiSummaryTodo(
    val title: String,
    val reminderAt: Instant?,
)

@file:Suppress("ktlint:standard:filename")

package com.qlink.ai.dto

import kotlinx.serialization.Serializable

@Serializable
data class AiSharedKeyResponse(
    val lastFour: String?,
)

@Serializable
data class PutAiSharedKeyResponse(
    val lastFour: String,
)

@file:Suppress("ktlint:standard:filename")

package com.qlink.post.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePostRequest(
    val title: String,
    val contents: String,
)

@Serializable
data class CreatePostRequest(
    val title: String,
    val contents: String,
    val type: String,
    val imageUrls: List<String> = emptyList(),
)

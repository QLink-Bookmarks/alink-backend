@file:Suppress("ktlint:standard:filename")

package com.qlink.auth.dto

data class SignInResult(
    val response: SignInResponse,
    val previewFillLinkIds: List<Long> = emptyList(),
    val hasPendingContent: Boolean = false,
)

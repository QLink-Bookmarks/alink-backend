@file:Suppress("ktlint:standard:filename")

package com.qlink.folder.dto

import com.qlink.common.serialization.StrictBooleanSerializer
import kotlinx.serialization.Serializable

@Serializable
data class CreateFolderRequest(
    val name: String,
    val emoji: String? = null,
    val isShared: Boolean? = null,
    @Serializable(with = StrictBooleanSerializer::class)
    val isPublic: Boolean? = null,
)

@Serializable
data class UpdateFolderRequest(
    val name: String,
    val emoji: String? = null,
    val isShared: Boolean? = null,
    @Serializable(with = StrictBooleanSerializer::class)
    val isPublic: Boolean? = null,
)

@Serializable
data class TransferFolderOwnerRequest(
    val ownerId: Long,
)

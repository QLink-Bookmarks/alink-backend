@file:Suppress("ktlint:standard:filename")

package com.qlink.folder.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateFolderResponse(
    val id: Long,
)

@Serializable
data class GetFoldersContentResponse(
    val id: Long,
    val ownerId: Long,
    val ownerNickname: String?,
    val name: String,
    val emoji: String?,
    val isShared: Boolean,
    val isPublic: Boolean,
    val shareCounts: Int,
    val linkCounts: Int,
    val myLinkCounts: Int,
)

@Serializable
data class GetFolderMembersResponse(
    val ownerId: Long,
    val ownerNickname: String,
    val members: List<GetFolderMemberResponse>,
)

@Serializable
data class GetFolderMemberResponse(
    val userId: Long,
    val role: String,
    val userNickname: String,
    val avatarUrl: String?,
    val avatarEmoji: String?,
)

@Serializable
data class UpdateFolderResponse(
    val id: Long,
    val isPublic: Boolean,
    val publicId: String?,
)

@Serializable
data class FolderPrivacyResponse(
    val isPublic: Boolean,
    val publicId: String?,
)

@Serializable
data class PublicFolderResponse(
    val name: String,
    val emoji: String?,
)

@Serializable
data class PublicFolderLinkResponse(
    val title: String,
    val url: String,
)

@Serializable
data class PublicFolderTodoResponse(
    val title: String,
    val isCompleted: Boolean,
)

@Serializable
data class PublicFolderScrollResponse<T>(
    val isEmpty: Boolean,
    val contents: List<T>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

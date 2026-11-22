package com.qlink.link.repository

import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkSort
import com.qlink.link.domain.LinkStatus
import com.qlink.link.domain.SearchKeyword
import com.qlink.link.dto.LinkDetailQuery
import com.qlink.link.dto.LinkSearchCursor
import com.qlink.link.dto.PublicLinkDetailQuery
import com.qlink.link.dto.PublicLinkQuery
import com.qlink.link.dto.SearchLinksQuery
import kotlin.time.Instant

interface LinkRepository {
    suspend fun clearFolderVisibility(folderId: Long)

    suspend fun updateVisibility(
        linkId: Long,
        turnsPublicAt: Instant?,
    ): Instant?

    suspend fun insert(link: Link): Link

    suspend fun insertAll(links: List<Link>): List<Link>

    suspend fun findById(linkId: Long): Link?

    suspend fun findByIdForUpdate(linkId: Long): Link?

    suspend fun findByIdForShare(linkId: Long): Link?

    suspend fun findAllByFolderIdForUpdate(folderId: Long): List<Link>

    suspend fun findPublicDetailById(linkId: Long): PublicLinkDetailQuery?

    suspend fun findPublicByFolderId(
        folderId: Long,
        sort: LinkSort,
        cursor: LinkSearchCursor?,
        limit: Int,
    ): List<PublicLinkQuery>

    suspend fun findAllByIdsForUpdate(linkIds: List<Long>): List<Link>

    suspend fun assignFolderByIds(
        linkIds: List<Long>,
        folderId: Long?,
        now: Instant,
        preservePublication: Boolean = false,
    )

    suspend fun findAllByIds(linkIds: List<Long>): List<Link>

    suspend fun findAllByFolderIds(folderIds: List<Long>): List<Link>

    suspend fun findAllUrlsByOwnerId(ownerId: Long): List<String>

    suspend fun findDetailById(
        linkId: Long,
        loginId: Long,
    ): LinkDetailQuery?

    suspend fun search(
        ownerId: Long,
        keyword: SearchKeyword?,
        folderId: Long?,
        isFavorite: Boolean?,
        sort: LinkSort,
        cursor: LinkSearchCursor?,
        size: Int,
        isPublic: Boolean? = null,
        linkId: Long? = null,
    ): List<SearchLinksQuery>

    suspend fun update(link: Link): Link

    suspend fun updateFavorite(
        linkId: Long,
        favoriteAt: Instant?,
    )

    suspend fun updatePatchFields(
        linkId: Long,
        folderId: Long?,
        memo: String?,
        tags: List<String>,
        turnsPublicAt: Instant?,
    ): Link

    suspend fun updateAiResult(
        linkId: Long,
        folderId: Long?,
        title: String,
        summary: String?,
        tags: List<String>,
        status: LinkStatus,
        workModelId: Long?,
        clearPublication: Boolean = false,
    )

    suspend fun updateAiFailure(
        linkId: Long,
        title: String,
        summary: String?,
        status: LinkStatus,
    )

    suspend fun updatePreview(link: Link): Boolean

    suspend fun detachFolder(folderId: Long)

    suspend fun detachFolders(folderIds: List<Long>)

    suspend fun deleteAllByFolderId(folderId: Long)

    suspend fun deleteById(linkId: Long)

    suspend fun deleteAllByIds(linkIds: List<Long>)
}

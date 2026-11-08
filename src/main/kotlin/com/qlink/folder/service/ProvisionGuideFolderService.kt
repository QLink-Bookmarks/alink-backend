package com.qlink.folder.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.ProvisionGuideFolderResult
import com.qlink.folder.repository.FolderRepository
import com.qlink.guidecache.domain.CachedGuidePage
import com.qlink.guidecache.domain.GuideDefinition
import com.qlink.guidecache.repository.GuideCacheRepository
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.CollectedPage
import com.qlink.linkcontent.domain.ContentPriority
import com.qlink.linkcontent.domain.LinkContent
import com.qlink.linkcontent.repository.LinkContentRepository
import kotlin.time.Instant

class ProvisionGuideFolderService(
    private val tx: TransactionRunner,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
    private val contentRepository: LinkContentRepository,
    private val cacheRepository: GuideCacheRepository,
) {
    suspend fun provision(
        ownerId: Long,
        now: Instant,
    ): ProvisionGuideFolderResult =
        tx.required {
            val caches = cacheRepository.findAllByUrls(GuideDefinition.entries.map { it.url }).associateBy { it.url }
            val folder = folderRepository.insert(Folder.createGuide(ownerId))
            val previewFillIds = mutableListOf<Long>()
            var hasPendingContent = false
            for (definition in GuideDefinition.entries.reversed()) {
                val cache = caches[definition.url]
                val preview = cache?.readyPreview()
                val link =
                    linkRepository.insert(
                        Link.createGuide(
                            ownerId = ownerId,
                            folderId = folder.id!!,
                            url = definition.url,
                            title = definition.title,
                            tags = definition.tags,
                            thumbnailUrl = preview?.thumbnailUrl,
                            faviconUrl = preview?.faviconUrl,
                            isVideo = preview?.isVideo ?: false,
                            now = now,
                        ),
                    )
                if (preview == null) previewFillIds += link.id!!
                val pending =
                    LinkContent.create(
                        linkId = link.id!!,
                        sourceUrl = link.url,
                        priority = ContentPriority.INTERACTIVE,
                        now = now,
                    )
                val page = cache?.readyContent()
                val content =
                    if (page != null) {
                        pending.complete(page = page.toCollectedPage(), now = cache.collectedAt!!)
                    } else {
                        hasPendingContent = true
                        pending
                    }
                contentRepository.insertAll(listOf(content))
            }
            ProvisionGuideFolderResult(previewFillLinkIds = previewFillIds, hasPendingContent = hasPendingContent)
        }

    private fun CachedGuidePage.toCollectedPage(): CollectedPage =
        CollectedPage(
            text = text,
            title = title,
            siteName = siteName,
            author = author,
            publishedAt = publishedAt,
            canonicalUrl = canonicalUrl,
            finalUrl = finalUrl,
            language = language,
            isTruncated = isTruncated,
        )
}

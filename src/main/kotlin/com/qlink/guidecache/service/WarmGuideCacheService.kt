package com.qlink.guidecache.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.guidecache.domain.CachedGuidePage
import com.qlink.guidecache.domain.CachedGuidePreview
import com.qlink.guidecache.domain.GuideCache
import com.qlink.guidecache.domain.GuideDefinition
import com.qlink.guidecache.repository.GuideCacheRepository
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.client.LinkPreviewCaller
import com.qlink.link.client.LinkPreviewExtraction
import com.qlink.link.client.LinkPreviewMode
import com.qlink.link.client.LinkPreviewSyncClient
import com.qlink.linkcontent.client.LinkContentExtraction
import com.qlink.linkcontent.client.LinkContentSyncClient
import com.qlink.linkcontent.domain.CollectedPage
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.util.UUID
import kotlin.time.Clock

class WarmGuideCacheService(
    private val tx: TransactionRunner,
    private val repository: GuideCacheRepository,
    private val previewClient: LinkPreviewSyncClient,
    private val contentClient: LinkContentSyncClient,
    private val urlResolver: PreviewImageUrlResolver,
    private val log: Logger,
    private val clock: Clock = Clock.System,
) {
    suspend fun warm() {
        for (definition in GuideDefinition.entries) {
            tx.required { repository.insertIfAbsent(GuideCache(url = definition.url)) }
            warmPreview(definition.url)
            warmContent(definition.url)
        }
    }

    private suspend fun warmPreview(url: String) {
        val claimed =
            tx.required {
                val current = repository.findByUrl(url) ?: return@required null
                val next = current.startPreview(clock.now()) ?: return@required null
                repository.updatePreview(expected = current, next = next)
            } ?: return
        val preview =
            attempt {
                val extraction = previewClient.extract(url = url, mode = LinkPreviewMode.PREVIEW, caller = LinkPreviewCaller.LINK_FILL)
                val result = (extraction as? LinkPreviewExtraction.Success)?.result ?: return@attempt null
                val thumbnail = result.thumbnailKey?.let(urlResolver::previewUrl)
                CachedGuidePreview(
                    thumbnailUrl = thumbnail,
                    faviconUrl = result.faviconKey?.takeIf { thumbnail == null }?.let(urlResolver::faviconUrl),
                    isVideo = result.isVideo,
                )
            }
        if (preview == null) log.warn("[GUIDE_CACHE] Preview warm-up failed. url={}", url)
        val next = preview?.let(claimed::completePreview) ?: claimed.failPreview()
        tx.required { repository.updatePreview(expected = claimed, next = next) }
    }

    private suspend fun warmContent(url: String) {
        val claimed =
            tx.required {
                val current = repository.findByUrl(url) ?: return@required null
                val next = current.startContent(clock.now()) ?: return@required null
                repository.updateContent(expected = current, next = next)
            } ?: return
        val page =
            attempt {
                val extraction = contentClient.extract(url = url, traceId = "guide-${UUID.randomUUID()}", deadlineMs = 20_000L)
                val result = (extraction as? LinkContentExtraction.Collected)?.result ?: return@attempt null
                CollectedPage
                    .ofOrNull(
                        text = result.text,
                        title = result.title,
                        siteName = result.siteName,
                        author = result.author,
                        publishedAt = result.publishedAt,
                        canonicalUrl = result.canonicalUrl,
                        finalUrl = result.finalUrl,
                        language = result.language,
                        isTruncated = result.isTruncated,
                    )?.toSnapshot()
            }
        if (page == null) log.warn("[GUIDE_CACHE] Content warm-up failed. url={}", url)
        val next = page?.let { claimed.completeContent(page = it, now = clock.now()) } ?: claimed.failContent()
        tx.required { repository.updateContent(expected = claimed, next = next) }
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn("[GUIDE_CACHE] Extraction failed. cause={}", error::class.simpleName)
            null
        }

    private fun CollectedPage.toSnapshot(): CachedGuidePage =
        CachedGuidePage(
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

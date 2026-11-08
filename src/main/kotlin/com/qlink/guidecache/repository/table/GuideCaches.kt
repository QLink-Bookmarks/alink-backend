package com.qlink.guidecache.repository.table

import com.qlink.guidecache.domain.CachedGuidePage
import com.qlink.guidecache.domain.CachedGuidePreview
import com.qlink.guidecache.domain.GuideCache
import com.qlink.guidecache.domain.GuideCacheStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.timestamp
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

object GuideCaches : Table("guide_caches") {
    val url = text("url")
    val previewStatus = enumerationByName<GuideCacheStatus>("preview_status", 20)
    val previewStartedAt = timestamp("preview_started_at").nullable()
    val thumbnailUrl = text("thumbnail_url").nullable()
    val faviconUrl = text("favicon_url").nullable()
    val isVideo = bool("is_video").default(false)
    val contentStatus = enumerationByName<GuideCacheStatus>("content_status", 20)
    val contentStartedAt = timestamp("content_started_at").nullable()
    val contentText = text("content_text").nullable()
    val title = text("title").nullable()
    val siteName = text("site_name").nullable()
    val author = text("author").nullable()
    val publishedAt = timestamp("published_at").nullable()
    val canonicalUrl = text("canonical_url").nullable()
    val finalUrl = text("final_url").nullable()
    val language = text("language").nullable()
    val isTruncated = bool("is_truncated").default(false)
    val collectedAt = timestamp("collected_at").nullable()

    override val primaryKey = PrimaryKey(url)
}

fun ResultRow.toGuideCacheDomain(): GuideCache =
    GuideCache(
        url = this[GuideCaches.url],
        previewStatus = this[GuideCaches.previewStatus],
        previewStartedAt = this[GuideCaches.previewStartedAt]?.toKotlinInstant(),
        preview =
            if (this[GuideCaches.previewStatus] == GuideCacheStatus.READY) {
                CachedGuidePreview(
                    thumbnailUrl = this[GuideCaches.thumbnailUrl],
                    faviconUrl = this[GuideCaches.faviconUrl],
                    isVideo = this[GuideCaches.isVideo],
                )
            } else {
                null
            },
        contentStatus = this[GuideCaches.contentStatus],
        contentStartedAt = this[GuideCaches.contentStartedAt]?.toKotlinInstant(),
        page =
            if (this[GuideCaches.contentStatus] == GuideCacheStatus.READY) {
                this[GuideCaches.contentText]?.let { text ->
                    CachedGuidePage(
                        text = text,
                        title = this[GuideCaches.title],
                        siteName = this[GuideCaches.siteName],
                        author = this[GuideCaches.author],
                        publishedAt = this[GuideCaches.publishedAt]?.toKotlinInstant(),
                        canonicalUrl = this[GuideCaches.canonicalUrl],
                        finalUrl = this[GuideCaches.finalUrl],
                        language = this[GuideCaches.language],
                        isTruncated = this[GuideCaches.isTruncated],
                    )
                }
            } else {
                null
            },
        collectedAt = this[GuideCaches.collectedAt]?.toKotlinInstant(),
    )

fun UpdateBuilder<*>.fromPreviewDomain(cache: GuideCache) {
    this[GuideCaches.previewStatus] = cache.previewStatus
    this[GuideCaches.previewStartedAt] = cache.previewStartedAt?.toJavaInstant()
    this[GuideCaches.thumbnailUrl] = cache.preview?.thumbnailUrl
    this[GuideCaches.faviconUrl] = cache.preview?.faviconUrl
    this[GuideCaches.isVideo] = cache.preview?.isVideo ?: false
}

fun UpdateBuilder<*>.fromContentDomain(cache: GuideCache) {
    this[GuideCaches.contentStatus] = cache.contentStatus
    this[GuideCaches.contentStartedAt] = cache.contentStartedAt?.toJavaInstant()
    this[GuideCaches.contentText] = cache.page?.text
    this[GuideCaches.title] = cache.page?.title
    this[GuideCaches.siteName] = cache.page?.siteName
    this[GuideCaches.author] = cache.page?.author
    this[GuideCaches.publishedAt] = cache.page?.publishedAt?.toJavaInstant()
    this[GuideCaches.canonicalUrl] = cache.page?.canonicalUrl
    this[GuideCaches.finalUrl] = cache.page?.finalUrl
    this[GuideCaches.language] = cache.page?.language
    this[GuideCaches.isTruncated] = cache.page?.isTruncated ?: false
    this[GuideCaches.collectedAt] = cache.collectedAt?.toJavaInstant()
}

fun UpdateBuilder<*>.fromDomain(cache: GuideCache) {
    this[GuideCaches.url] = cache.url
    fromPreviewDomain(cache)
    fromContentDomain(cache)
}

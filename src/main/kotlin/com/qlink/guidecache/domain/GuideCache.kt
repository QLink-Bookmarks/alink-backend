package com.qlink.guidecache.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val STALE_AFTER = 10.minutes

data class GuideCache(
    val url: String,
    val previewStatus: GuideCacheStatus = GuideCacheStatus.NOT_STARTED,
    val previewStartedAt: Instant? = null,
    val preview: CachedGuidePreview? = null,
    val contentStatus: GuideCacheStatus = GuideCacheStatus.NOT_STARTED,
    val contentStartedAt: Instant? = null,
    val page: CachedGuidePage? = null,
    val collectedAt: Instant? = null,
) {
    fun startPreview(now: Instant): GuideCache? =
        takeIf { canStart(status = previewStatus, startedAt = previewStartedAt, now = now) }
            ?.copy(previewStatus = GuideCacheStatus.STARTED, previewStartedAt = now)

    fun startContent(now: Instant): GuideCache? =
        takeIf { canStart(status = contentStatus, startedAt = contentStartedAt, now = now) }
            ?.copy(contentStatus = GuideCacheStatus.STARTED, contentStartedAt = now)

    fun completePreview(preview: CachedGuidePreview): GuideCache =
        when (previewStatus) {
            GuideCacheStatus.STARTED -> copy(previewStatus = GuideCacheStatus.READY, preview = preview)
            else -> this
        }

    fun completeContent(
        page: CachedGuidePage,
        now: Instant,
    ): GuideCache =
        when (contentStatus) {
            GuideCacheStatus.STARTED -> copy(contentStatus = GuideCacheStatus.READY, page = page, collectedAt = now)
            else -> this
        }

    fun failPreview(): GuideCache =
        when (previewStatus) {
            GuideCacheStatus.STARTED -> copy(previewStatus = GuideCacheStatus.FAILED)
            else -> this
        }

    fun failContent(): GuideCache =
        when (contentStatus) {
            GuideCacheStatus.STARTED -> copy(contentStatus = GuideCacheStatus.FAILED)
            else -> this
        }

    fun readyPreview(): CachedGuidePreview? = preview.takeIf { previewStatus == GuideCacheStatus.READY }

    fun readyContent(): CachedGuidePage? = page.takeIf { contentStatus == GuideCacheStatus.READY }

    private fun canStart(
        status: GuideCacheStatus,
        startedAt: Instant?,
        now: Instant,
    ): Boolean =
        when (status) {
            GuideCacheStatus.NOT_STARTED, GuideCacheStatus.FAILED -> true
            GuideCacheStatus.STARTED -> startedAt != null && startedAt < now - STALE_AFTER
            GuideCacheStatus.READY -> false
        }
}

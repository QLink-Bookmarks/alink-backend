package com.qlink.support.fixture

import com.qlink.guidecache.domain.CachedGuidePage
import com.qlink.guidecache.domain.CachedGuidePreview
import com.qlink.guidecache.domain.GuideCache
import com.qlink.guidecache.domain.GuideCacheStatus
import kotlin.time.Instant

object GuideCacheFixture {
    fun page(text: String = LinkContentFixture.randomBodyText()): CachedGuidePage =
        LinkContentFixture.createCollectedPage(text = text).let {
            CachedGuidePage(
                text = it.text,
                title = it.title,
                siteName = it.siteName,
                author = it.author,
                publishedAt = it.publishedAt,
                canonicalUrl = it.canonicalUrl,
                finalUrl = it.finalUrl,
                language = it.language,
                isTruncated = it.isTruncated,
            )
        }

    fun preview(): CachedGuidePreview =
        CachedGuidePreview(
            thumbnailUrl = RandomFixture.randomUrl(),
            faviconUrl = null,
            isVideo = RandomFixture.randomInt(0, 1) == 1,
        )

    fun cache(
        url: String = RandomFixture.randomUrl(),
        previewStatus: GuideCacheStatus = GuideCacheStatus.NOT_STARTED,
        contentStatus: GuideCacheStatus = GuideCacheStatus.NOT_STARTED,
        startedAt: Instant = RandomFixture.randomPastInstant(),
        page: CachedGuidePage = page(),
        collectedAt: Instant = RandomFixture.randomPastInstant(),
    ): GuideCache =
        GuideCache(
            url = url,
            previewStatus = previewStatus,
            previewStartedAt = startedAt.takeUnless { previewStatus == GuideCacheStatus.NOT_STARTED },
            preview = preview().takeIf { previewStatus == GuideCacheStatus.READY },
            contentStatus = contentStatus,
            contentStartedAt = startedAt.takeUnless { contentStatus == GuideCacheStatus.NOT_STARTED },
            page = page.takeIf { contentStatus == GuideCacheStatus.READY },
            collectedAt = collectedAt.takeIf { contentStatus == GuideCacheStatus.READY },
        )
}

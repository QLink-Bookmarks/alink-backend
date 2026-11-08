package com.qlink.guidecache.repository

import com.qlink.guidecache.domain.GuideCache

interface GuideCacheRepository {
    suspend fun insertIfAbsent(cache: GuideCache)

    suspend fun findByUrl(url: String): GuideCache?

    suspend fun findAllByUrls(urls: List<String>): List<GuideCache>

    suspend fun updatePreview(
        expected: GuideCache,
        next: GuideCache,
    ): GuideCache?

    suspend fun updateContent(
        expected: GuideCache,
        next: GuideCache,
    ): GuideCache?
}

package com.qlink.guidecache.repository

import com.qlink.guidecache.domain.GuideCache
import com.qlink.guidecache.repository.table.GuideCaches
import com.qlink.guidecache.repository.table.fromContentDomain
import com.qlink.guidecache.repository.table.fromDomain
import com.qlink.guidecache.repository.table.fromPreviewDomain
import com.qlink.guidecache.repository.table.toGuideCacheDomain
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.toJavaInstant

class DbGuideCacheRepository : GuideCacheRepository {
    override suspend fun insertIfAbsent(cache: GuideCache) {
        GuideCaches.insertIgnore { it.fromDomain(cache) }
    }

    override suspend fun findByUrl(url: String): GuideCache? =
        GuideCaches
            .selectAll()
            .where { GuideCaches.url eq url }
            .singleOrNull()
            ?.toGuideCacheDomain()

    override suspend fun findAllByUrls(urls: List<String>): List<GuideCache> =
        GuideCaches.selectAll().where { GuideCaches.url inList urls }.map { it.toGuideCacheDomain() }

    override suspend fun updatePreview(
        expected: GuideCache,
        next: GuideCache,
    ): GuideCache? =
        GuideCaches
            .updateReturning(where = {
                (GuideCaches.url eq expected.url) and
                    (GuideCaches.previewStatus eq expected.previewStatus) and
                    (
                        expected.previewStartedAt?.let { GuideCaches.previewStartedAt eq it.toJavaInstant() }
                            ?: GuideCaches.previewStartedAt.isNull()
                    )
            }) { it.fromPreviewDomain(next) }
            .singleOrNull()
            ?.toGuideCacheDomain()

    override suspend fun updateContent(
        expected: GuideCache,
        next: GuideCache,
    ): GuideCache? =
        GuideCaches
            .updateReturning(where = {
                (GuideCaches.url eq expected.url) and
                    (GuideCaches.contentStatus eq expected.contentStatus) and
                    (
                        expected.contentStartedAt?.let { GuideCaches.contentStartedAt eq it.toJavaInstant() }
                            ?: GuideCaches.contentStartedAt.isNull()
                    )
            }) { it.fromContentDomain(next) }
            .singleOrNull()
            ?.toGuideCacheDomain()
}

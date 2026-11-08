package com.qlink.guidecache.domain

import kotlin.time.Instant

data class CachedGuidePage(
    val text: String,
    val title: String?,
    val siteName: String?,
    val author: String?,
    val publishedAt: Instant?,
    val canonicalUrl: String?,
    val finalUrl: String?,
    val language: String?,
    val isTruncated: Boolean,
)

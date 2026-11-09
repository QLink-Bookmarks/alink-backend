package com.qlink.support.fixture

import com.qlink.guide.domain.GuidePage

object GuideFixture {
    fun createPage(): GuidePage =
        GuidePage(
            text = LinkContentFixture.randomBodyText(),
            title = RandomFixture.randomSentenceWithMax(100),
            siteName = RandomFixture.randomSentenceWithMax(50),
            author = RandomFixture.randomSentenceWithMax(50),
            publishedAt = RandomFixture.randomPastInstant(),
            canonicalUrl = RandomFixture.randomUrl(),
            finalUrl = RandomFixture.randomUrl(),
            language = RandomFixture.randomLanguage(),
            isTruncated = RandomFixture.randomInt(0, 1) == 1,
        )
}

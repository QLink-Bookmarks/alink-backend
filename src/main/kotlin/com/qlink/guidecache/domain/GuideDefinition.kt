package com.qlink.guidecache.domain

enum class GuideDefinition(
    val title: String,
    val url: String,
    val tags: List<String>,
) {
    TIPS(
        title = "ALink Tips",
        url = "https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374",
        tags = listOf("ALink Tips"),
    ),
    QUICK_SHARE(
        title = "ALink 빠른 공유하기 설정",
        url = "https://warp-raft-63c.notion.site/412d602d1254809a9b84c0b6c82c6dbf",
        tags = listOf("ALink Tips", "고정 설정"),
    ),
    SHARE_TO_SAVE(
        title = "공유하기로 간편 저장",
        url = "https://warp-raft-63c.notion.site/412d602d1254803dbca0deec90d418ee",
        tags = listOf("ALink Tips", "간편 저장"),
    ),
    IMPORT_BOOKMARKS(
        title = "웹 브라우저 북마크 가져오기",
        url = "https://warp-raft-63c.notion.site/412d602d1254803d8c41e9366c33f8fc",
        tags = listOf("ALink Tips", "북마크 가져오기"),
    ),
}

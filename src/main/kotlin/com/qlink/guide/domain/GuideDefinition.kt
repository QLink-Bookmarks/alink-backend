package com.qlink.guide.domain

enum class GuideDefinition(
    val title: String,
    val url: String,
    val tags: List<String>,
    val preview: GuidePreview,
    val page: GuidePage?,
) {
    TIPS(
        title = "ALink Tips",
        url = "https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374",
        tags = listOf("ALink Tips"),
        preview =
            GuidePreview(
                thumbnailUrl =
                    "https://images.archivelink.app/dev-preview-888a164807359e5c27ece15b8afd3242641aa822a78b50c1aaf1762e72b37a35.png",
                faviconUrl = null,
                isVideo = false,
            ),
        page =
            GuidePage(
                text = TIPS_TEXT,
                title = "ALink Tips",
                siteName = "warp-raft-63c on Notion",
                author = null,
                publishedAt = null,
                canonicalUrl = "https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374",
                finalUrl = "https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374",
                language = "en",
                isTruncated = false,
            ),
    ),
    QUICK_SHARE(
        title = "ALink 빠른 공유하기 설정",
        url = "https://warp-raft-63c.notion.site/412d602d1254809a9b84c0b6c82c6dbf",
        tags = listOf("ALink Tips", "고정 설정"),
        preview =
            GuidePreview(
                thumbnailUrl =
                    "https://images.archivelink.app/dev-preview-c6ccce5f515f0de82ea28a6fe44200b62be63a4c5730ae5077a07d016b11eeb3.png",
                faviconUrl = null,
                isVideo = false,
            ),
        page =
            GuidePage(
                text = QUICK_SHARE_TEXT,
                title = "ALink 빠른 공유하기 설정",
                siteName = "warp-raft-63c on Notion",
                author = null,
                publishedAt = null,
                canonicalUrl = "https://warp-raft-63c.notion.site/412d602d1254809a9b84c0b6c82c6dbf",
                finalUrl = "https://warp-raft-63c.notion.site/412d602d1254809a9b84c0b6c82c6dbf",
                language = "en",
                isTruncated = false,
            ),
    ),
    SHARE_TO_SAVE(
        title = "공유하기로 간편 저장",
        url = "https://warp-raft-63c.notion.site/412d602d1254803dbca0deec90d418ee",
        tags = listOf("ALink Tips", "간편 저장"),
        preview =
            GuidePreview(
                thumbnailUrl =
                    "https://images.archivelink.app/dev-preview-c821af3380ce41778e1f1acc3ca04b4ef795098b3c99a0433bc6442e7eb8fd0b.png",
                faviconUrl = null,
                isVideo = false,
            ),
        page =
            GuidePage(
                text = SHARE_TO_SAVE_TEXT,
                title = "공유하기로 간편 저장",
                siteName = "warp-raft-63c on Notion",
                author = null,
                publishedAt = null,
                canonicalUrl = "https://warp-raft-63c.notion.site/412d602d1254803dbca0deec90d418ee",
                finalUrl = "https://warp-raft-63c.notion.site/412d602d1254803dbca0deec90d418ee",
                language = "en",
                isTruncated = false,
            ),
    ),
    IMPORT_BOOKMARKS(
        title = "웹 브라우저 북마크 가져오기",
        url = "https://warp-raft-63c.notion.site/412d602d1254803d8c41e9366c33f8fc",
        tags = listOf("ALink Tips", "북마크 가져오기"),
        preview =
            GuidePreview(
                thumbnailUrl =
                    "https://images.archivelink.app/dev-preview-d8e356c99d7ad27e427bd6bb3db2aa2b2cdc437a358539b6dbb1839d7a565e83.png",
                faviconUrl = null,
                isVideo = false,
            ),
        page =
            GuidePage(
                text = IMPORT_BOOKMARKS_TEXT,
                title = "웹 브라우저 북마크 가져오기",
                siteName = "warp-raft-63c on Notion",
                author = null,
                publishedAt = null,
                canonicalUrl = "https://warp-raft-63c.notion.site/412d602d1254803d8c41e9366c33f8fc",
                finalUrl = "https://warp-raft-63c.notion.site/412d602d1254803d8c41e9366c33f8fc",
                language = "en",
                isTruncated = false,
            ),
    ),
}

private val TIPS_TEXT =
    """
    흩어진 북마크를 편리하게, 액션으로 이어지는 저장, 편리한 검색까지.
    북마크 아카이브 서비스 ALink의 팁 페이지입니다.
    문의사항 및 피드백 : 앱 내 설정 > 피드백

    이메일 문의 : [email protected]

    </aside>

    앱스토어 : https://apps.apple.com/kr/app/%EC%97%90%EC%9D%B4%EB%A7%81%ED%81%AC-alink-ai%EA%B0%80-%EB%8F%84%EC%99%80%EC%A3%BC%EB%8A%94-%EB%B6%81%EB%A7%88%ED%81%AC-%EC%95%84%EC%B9%B4%EC%9D%B4%EB%B8%8C/id6788230955

    웹 바로가기 : https://archivelink.app/

    문서를 클릭하면 더 자세히 볼 수 있어요

    가이드북
    """.trimIndent()

private val QUICK_SHARE_TEXT =
    """
    iOS : 브라우저/앱 공유 > 더보기 > 편집 > 에이링크 추가

    1. 모바일 브라우저 등에서 공유를 눌러주세요.

    2. 공유 화면에서 “더 보기”를 누르고 앱 화면에서 “편집”을 눌러주세요.

    3. 즐겨찾기에 에이링크를 추가해주세요.
    """.trimIndent()

private val SHARE_TO_SAVE_TEXT =
    """
    iOS : 공유 > 에이링크 > 폴더/AI 옵션 > 저장/AI 정리

    1. 공유하기에서 에이링크를 선택해주세요

    2. 폴더 및 AI 옵션 등을 선택해주세요

    3. 저장 / AI 정리를 선택해주세요

    AI 정리 시 AI가 자동 요약, 태그를 지정하고, 폴더가 “미분류”로 지정되어 있으면 자동으로 찾아줘요
    """.trimIndent()

private val IMPORT_BOOKMARKS_TEXT =
    """
    브라우저 (archivelink.app) : 설정 > 데이터 > 북마크 가져오기 > 새로 가져오기

    1. 설정의 북마크 가져오기에서 브라우저 별 북마크 내보내기 가이드를 따라 북마크 HTML 파일을 준비해주세요

    브라우저 북마크 형식이 아닌 파일이면 가져오기를 할 수 없어요

    2. 가져올 내용을 미리 확인해주세요

    링크를 제외하거나, 폴더를 옮길 수 있어요

    모든 링크가 빠진 폴더는 가져오기에서 제외돼요

    3. 가져온 북마크 내용을 확인해요

    가져온 북마크 내용을 확인할 수 있고, 되돌리기도 할 수 있어요
    """.trimIndent()

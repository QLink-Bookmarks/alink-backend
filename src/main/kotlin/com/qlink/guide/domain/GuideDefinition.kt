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
        url = "https://archivelink.app/folders/64",
        tags = listOf("ALink Tips"),
        preview =
            GuidePreview(
                thumbnailUrl =
                    "https://images.archivelink.app/dev-preview-7816d107fdce089c9f9d9fda5eee6476dadfeb82b0649f6a3309c32139ce53ea.png",
                faviconUrl = null,
                isVideo = false,
            ),
        page =
            GuidePage(
                text = TIPS_TEXT,
                title = "ALink Tips",
                siteName = null,
                author = null,
                publishedAt = null,
                canonicalUrl = null,
                finalUrl = "https://archivelink.app/folders/64",
                language = "ko",
                isTruncated = false,
            ),
    ),
}

private val TIPS_TEXT =
    """
    흩어진 북마크를 편리하게, 액션으로 이어지는 저장, 편리한 검색까지.
    북마크 아카이브 서비스 ALink의 팁을 모은 공개 폴더입니다.
    문의사항 및 피드백 : 앱 내 설정 > 피드백

    ALink 빠른 공유하기 설정

    iOS : 브라우저/앱 공유 > 더보기 > 편집 > 에이링크 추가

    1. 모바일 브라우저 등에서 공유를 눌러주세요.

    2. 공유 화면에서 “더 보기”를 누르고 앱 화면에서 “편집”을 눌러주세요.

    3. 즐겨찾기에 에이링크를 추가해주세요.

    공유하기로 간편 저장

    iOS : 공유 > 에이링크 > 폴더/AI 옵션 > 저장/AI 정리

    1. 공유하기에서 에이링크를 선택해주세요

    2. 폴더 및 AI 옵션 등을 선택해주세요

    3. 저장 / AI 정리를 선택해주세요

    AI 정리 시 AI가 자동 요약, 태그를 지정하고, 폴더가 “미분류”로 지정되어 있으면 자동으로 찾아줘요

    웹 브라우저 북마크 가져오기

    브라우저 (archivelink.app) : 설정 > 데이터 > 북마크 가져오기 > 새로 가져오기

    1. 설정의 북마크 가져오기에서 브라우저 별 북마크 내보내기 가이드를 따라 북마크 HTML 파일을 준비해주세요

    브라우저 북마크 형식이 아닌 파일이면 가져오기를 할 수 없어요

    2. 가져올 내용을 미리 확인해주세요

    링크를 제외하거나, 폴더를 옮길 수 있어요

    모든 링크가 빠진 폴더는 가져오기에서 제외돼요

    3. 가져온 북마크 내용을 확인해요

    가져온 북마크 내용을 확인할 수 있고, 되돌리기도 할 수 있어요
    """.trimIndent()

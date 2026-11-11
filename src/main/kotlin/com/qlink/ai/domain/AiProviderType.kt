package com.qlink.ai.domain

import java.net.URI

enum class AiProviderType {
    GEMINI,
    CLAUDE,
    OPENAI,
    ;

    fun isAllowedBaseUrl(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        val host =
            when (this) {
                GEMINI -> "generativelanguage.googleapis.com"
                OPENAI -> "api.openai.com"
                CLAUDE -> "api.anthropic.com"
            }
        return baseUrl.length <= 255 && uri.scheme == "https" && uri.host == host &&
            uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath
                .orEmpty()
                .split('/')
                .all { it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]*")) }
    }
}

package com.qlink.ai.client

class AiApiKeyValidationException(
    val statusCode: Int,
) : RuntimeException("AI API key validation failed. statusCode=$statusCode")

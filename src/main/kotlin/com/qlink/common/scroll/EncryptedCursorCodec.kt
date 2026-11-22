package com.qlink.common.scroll

import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class EncryptedCursorCodec(
    @PublishedApi internal val cipher: ApiKeyCipher,
) {
    inline fun <reified T> encode(value: T): String = cipher.encrypt(Json.encodeToString(value))

    inline fun <reified T> decode(
        encoded: String,
        errorCode: ErrorCode,
    ): T {
        val plaintext = cipher.decrypt(encoded) ?: throw BusinessException(errorCode)
        return try {
            Json.decodeFromString<T>(plaintext)
        } catch (e: SerializationException) {
            throw BusinessException(errorCode, e)
        }
    }
}

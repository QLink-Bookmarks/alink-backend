package com.qlink.folder.service

import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class PublicFolderCursorCodec(
    private val cipher: ApiKeyCipher,
) {
    fun encode(cursor: PublicFolderCursor): String = cipher.encrypt(Json.encodeToString(cursor))

    fun decode(
        encoded: String,
        folderId: Long,
        collection: String,
        sort: PublicFolderSort,
    ): PublicFolderCursor {
        val plaintext = cipher.decrypt(encoded) ?: throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED)
        val cursor =
            try {
                Json.decodeFromString<PublicFolderCursor>(plaintext)
            } catch (e: SerializationException) {
                throw BusinessException(ErrorCode.COMMON_CURSOR_MALFORMED, e)
            }

        (cursor.folderId == folderId && cursor.collection == collection).requireTrue(ErrorCode.COMMON_CURSOR_MALFORMED)
        sort.validateCursor(cursor.direction)

        return cursor
    }
}

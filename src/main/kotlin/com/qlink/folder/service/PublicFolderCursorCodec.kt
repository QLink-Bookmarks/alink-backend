package com.qlink.folder.service

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.scroll.EncryptedCursorCodec
import com.qlink.folder.domain.PublicFolderSort
import com.qlink.folder.dto.PublicFolderCursor

class PublicFolderCursorCodec(
    private val cursorCodec: EncryptedCursorCodec,
) {
    fun encode(cursor: PublicFolderCursor): String = cursorCodec.encode(cursor)

    fun decode(
        encoded: String,
        folderId: Long,
        collection: String,
        sort: PublicFolderSort,
        isPublic: Boolean? = null,
    ): PublicFolderCursor {
        val cursor = cursorCodec.decode<PublicFolderCursor>(encoded)
        (cursor.folderId == folderId && cursor.collection == collection && cursor.isPublic == isPublic)
            .requireTrue(ErrorCode.COMMON_CURSOR_MALFORMED)
        sort.validateCursor(cursor.direction)

        return cursor
    }
}

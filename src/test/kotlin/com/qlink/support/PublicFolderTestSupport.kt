package com.qlink.support

import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.ScrollRequest
import com.qlink.link.service.GetLinksService
import com.qlink.todo.service.GetTodosService
import io.kotest.matchers.shouldBe
import java.util.Base64

fun assertPublicCursorHidesIds(
    cursor: String,
    ids: Collection<Long>,
) {
    val segments = listOf(cursor) + cursor.split(':')
    val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
    val readable =
        segments +
            segments.flatMap { segment ->
                decoders.mapNotNull { decoder ->
                    runCatching { decoder.decode(segment).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                }
            }

    for (id in ids) {
        val idToken = Regex("""(?:^|["=:,\s])$id(?=$|["=:,\s])""")
        readable.any(idToken::containsMatchIn) shouldBe false
    }
}

suspend fun GetLinksService.getPublicLinks(
    folderId: Long,
    orderKey: String?,
    order: String?,
    scroll: ScrollRequest,
) = (
    getLinks(
        viewerId = null,
        query = null,
        folderId = folderId,
        isFavorite = null,
        orderKey = orderKey,
        order = order,
        scrollRequest = scroll,
    ) as ReadResult.Guest
).data

suspend fun GetTodosService.getPublicTodos(
    folderId: Long,
    orderKey: String?,
    order: String?,
    scroll: ScrollRequest,
) = (
    getTodos(
        viewerId = null,
        folderId = folderId,
        status = null,
        orderKey = orderKey,
        order = order,
        scrollRequest = scroll,
        isCompleted = null,
        reminderAt = null,
    ) as ReadResult.Guest
).data

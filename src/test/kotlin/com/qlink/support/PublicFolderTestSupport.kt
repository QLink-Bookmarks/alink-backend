package com.qlink.support

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

package com.qlink.support.fixture

import com.qlink.post.domain.Post
import com.qlink.post.domain.PostType

object PostFixture {
    fun createRandomPostOf(
        authorId: Long,
        type: PostType = PostType.ANNOUNCEMENT,
        title: String = RandomFixture.randomSentenceWithMax(100),
    ): Post =
        Post(
            title = title,
            contents = RandomFixture.randomSentenceWithMax(200),
            type = type,
            authorId = authorId,
        )
}

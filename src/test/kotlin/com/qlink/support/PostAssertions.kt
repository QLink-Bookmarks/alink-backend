package com.qlink.support

import com.qlink.post.domain.Post
import io.kotest.matchers.shouldBe

fun Post.shouldBeStoredAs(expected: Post) {
    id shouldBe expected.id
    title shouldBe expected.title
    contents shouldBe expected.contents
    type shouldBe expected.type
    authorId shouldBe expected.authorId
    updatedAt shouldBe expected.updatedAt
}

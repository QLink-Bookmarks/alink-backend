package com.qlink.post.repository

import com.qlink.post.domain.Post
import com.qlink.post.domain.PostImage
import com.qlink.post.domain.PostSort
import com.qlink.post.domain.PostType
import com.qlink.post.dto.PostSearchCursor
import com.qlink.post.dto.SearchPostsQuery
import kotlin.time.Instant

interface PostRepository {
    suspend fun insert(post: Post): Post

    suspend fun insertImages(images: List<PostImage>): List<PostImage>

    suspend fun findById(postId: Long): Post?

    suspend fun findCreatedAtById(id: Long): Instant?

    suspend fun findImagesByPostId(postId: Long): List<PostImage>

    suspend fun search(
        type: PostType,
        query: String?,
        sort: PostSort,
        cursor: PostSearchCursor?,
        size: Int,
    ): List<SearchPostsQuery>
}

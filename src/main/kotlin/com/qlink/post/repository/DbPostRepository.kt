package com.qlink.post.repository

import com.qlink.common.search.SortDirection
import com.qlink.post.domain.Post
import com.qlink.post.domain.PostImage
import com.qlink.post.domain.PostSort
import com.qlink.post.domain.PostType
import com.qlink.post.dto.PostSearchCursor
import com.qlink.post.dto.SearchPostsQuery
import com.qlink.post.repository.table.PostImages
import com.qlink.post.repository.table.Posts
import com.qlink.post.repository.table.fromDomain
import com.qlink.post.repository.table.toPostDomain
import com.qlink.post.repository.table.toPostImageDomain
import com.qlink.user.repository.table.Users
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.toKotlinInstant

class DbPostRepository : PostRepository {
    override suspend fun insert(post: Post): Post =
        Posts
            .insertReturning { it.fromDomain(post) }
            .single()
            .toPostDomain()

    override suspend fun insertImages(images: List<PostImage>): List<PostImage> =
        images.map { image ->
            PostImages
                .insertReturning { it.fromDomain(image) }
                .single()
                .toPostImageDomain()
        }

    override suspend fun findById(postId: Long): Post? =
        Posts
            .selectAll()
            .where { Posts.id eq postId }
            .singleOrNull()
            ?.toPostDomain()

    override suspend fun findImagesByPostId(postId: Long): List<PostImage> =
        PostImages
            .selectAll()
            .where { PostImages.postId eq postId }
            .orderBy(PostImages.id to SortOrder.ASC)
            .map { it.toPostImageDomain() }

    override suspend fun search(
        type: PostType,
        query: String?,
        sort: PostSort,
        cursor: PostSearchCursor?,
        size: Int,
    ): List<SearchPostsQuery> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val sortOrder = sort.direction.sortOrder()

        return Posts
            .join(
                otherTable = Users,
                joinType = JoinType.INNER,
                additionalConstraint = { Posts.authorId eq Users.id },
            ).select(Posts.id, Posts.title, Users.nickname, Posts.createdAt)
            .where { Posts.type eq type }
            .apply {
                normalizedQuery?.let { keyword ->
                    andWhere { Posts.title like "%$keyword%" }
                }
                cursor?.value?.let { cursorValue ->
                    andWhere { Posts.id.beyond(cursorValue.id!!, sort.direction) }
                }
            }.orderBy(Posts.id to sortOrder)
            .limit(size + 1)
            .map {
                SearchPostsQuery(
                    id = it[Posts.id],
                    title = it[Posts.title],
                    author = it[Users.nickname],
                    createdAt = it[Posts.createdAt].toKotlinInstant(),
                )
            }
    }

    private fun <T : Comparable<T>, S : T?> ExpressionWithColumnType<in S>.beyond(
        value: T,
        direction: SortDirection,
    ): Op<Boolean> =
        when (direction) {
            SortDirection.ASC -> this greater value
            SortDirection.DESC -> this less value
        }

    private fun SortDirection.sortOrder(): SortOrder =
        when (this) {
            SortDirection.ASC -> SortOrder.ASC
            SortDirection.DESC -> SortOrder.DESC
        }
}

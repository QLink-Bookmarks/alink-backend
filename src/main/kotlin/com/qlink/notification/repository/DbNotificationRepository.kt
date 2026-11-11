package com.qlink.notification.repository

import com.qlink.common.search.SortDirection
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.domain.NotificationOrderKey
import com.qlink.notification.domain.NotificationSort
import com.qlink.notification.dto.NotificationSearchCursor
import com.qlink.notification.dto.NotificationSearchCursorValue
import com.qlink.notification.dto.SearchNotificationsQuery
import com.qlink.notification.repository.table.Notifications
import com.qlink.notification.repository.table.fromDomain
import com.qlink.notification.repository.table.refreshNotificationUpdatedAt
import com.qlink.notification.repository.table.toNotificationDomain
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class DbNotificationRepository : NotificationRepository {
    override suspend fun deleteByContext(
        context: NotificationContext,
        contextId: Long,
    ) {
        Notifications.deleteWhere { (Notifications.context eq context) and (Notifications.contextId eq contextId) }
    }

    override suspend fun insert(notification: Notification): Notification =
        Notifications
            .insertReturning { it.fromDomain(notification) }
            .single()
            .toNotificationDomain()

    override suspend fun findById(notificationId: Long): Notification? =
        Notifications
            .selectAll()
            .where { Notifications.id eq notificationId }
            .singleOrNull()
            ?.toNotificationDomain()

    override suspend fun findPendingById(notificationId: Long): Notification? =
        Notifications
            .selectAll()
            .where {
                (Notifications.id eq notificationId) and
                    Notifications.firedAt.isNull() and
                    Notifications.failedAt.isNull()
            }.singleOrNull()
            ?.toNotificationDomain()

    override suspend fun findPendingBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Notification> =
        Notifications
            .selectAll()
            .where {
                (Notifications.willFireAt greaterEq startInclusive.toJavaInstant()) and
                    (Notifications.willFireAt less endExclusive.toJavaInstant()) and
                    Notifications.firedAt.isNull() and
                    Notifications.failedAt.isNull()
            }.map { it.toNotificationDomain() }

    override suspend fun findByContext(
        context: NotificationContext,
        contextId: Long,
    ): List<Notification> =
        Notifications
            .selectAll()
            .where {
                (Notifications.context eq context) and
                    (Notifications.contextId eq contextId)
            }.map { it.toNotificationDomain() }

    override suspend fun findPendingByContext(
        context: NotificationContext,
        contextId: Long,
    ): List<Notification> =
        Notifications
            .selectAll()
            .where {
                (Notifications.context eq context) and
                    (Notifications.contextId eq contextId) and
                    Notifications.firedAt.isNull() and
                    Notifications.failedAt.isNull()
            }.map { it.toNotificationDomain() }

    override suspend fun search(
        userId: Long,
        query: String?,
        type: NotificationContext?,
        sort: NotificationSort,
        cursor: NotificationSearchCursor?,
        size: Int,
    ): List<SearchNotificationsQuery> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }

        return Notifications
            .select(
                Notifications.id,
                Notifications.title,
                Notifications.message,
                Notifications.firedAt,
                Notifications.readAt,
                Notifications.context,
                Notifications.contextId,
            ).where {
                (Notifications.userId eq userId) and Notifications.firedAt.isNotNull()
            }.apply {
                type?.let { typeValue ->
                    andWhere { Notifications.context eq typeValue }
                }
                normalizedQuery?.let { keyword ->
                    andWhere {
                        (Notifications.title like "%$keyword%") or
                            (Notifications.message like "%$keyword%")
                    }
                }
                cursor?.value?.let { cursorValue ->
                    andWhere { cursorFilter(sort, cursorValue) }
                }
            }.orderBy(*orderBy(sort))
            .limit(size + 1)
            .map {
                SearchNotificationsQuery(
                    id = it[Notifications.id],
                    title = it[Notifications.title],
                    message = it[Notifications.message],
                    firedAt = it[Notifications.firedAt]!!.toKotlinInstant(),
                    readAt = it[Notifications.readAt]?.toKotlinInstant(),
                    context = it[Notifications.context],
                    contextId = it[Notifications.contextId],
                )
            }
    }

    private fun cursorFilter(
        sort: NotificationSort,
        cursorValue: NotificationSearchCursorValue,
    ): Op<Boolean> =
        when (sort.orderKey) {
            NotificationOrderKey.CREATED_AT -> {
                Notifications.id.beyond(cursorValue.id!!, sort.direction)
            }

            NotificationOrderKey.FIRED_AT -> {
                val firedAt = cursorValue.firedAt!!.toJavaInstant()

                Notifications.firedAt.beyond(firedAt, sort.direction) or
                    ((Notifications.firedAt eq firedAt) and Notifications.id.beyond(cursorValue.id!!, sort.direction))
            }
        }

    private fun orderBy(sort: NotificationSort): Array<Pair<Expression<*>, SortOrder>> {
        val sortOrder = sort.direction.sortOrder()

        return when (sort.orderKey) {
            NotificationOrderKey.CREATED_AT -> arrayOf(Notifications.id to sortOrder)
            NotificationOrderKey.FIRED_AT -> arrayOf(Notifications.firedAt to sortOrder, Notifications.id to sortOrder)
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

    override suspend fun countUnread(userId: Long): Int =
        Notifications
            .select(Notifications.id.count())
            .where {
                (Notifications.userId eq userId) and
                    Notifications.firedAt.isNotNull() and
                    Notifications.readAt.isNull()
            }.single()[Notifications.id.count()]
            .toInt()

    override suspend fun update(notification: Notification): Notification =
        Notifications
            .updateReturning(where = { Notifications.id eq notification.id!! }) {
                it.fromDomain(notification)
                it.refreshNotificationUpdatedAt()
            }.single()
            .toNotificationDomain()

    override suspend fun deletePendingByContextIds(
        context: NotificationContext,
        ids: List<Long>,
    ): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val notificationIds =
            Notifications
                .select(Notifications.id)
                .where {
                    (Notifications.context eq context) and (Notifications.contextId inList ids) and
                        Notifications.firedAt.isNull() and Notifications.failedAt.isNull()
                }.orderBy(Notifications.id to SortOrder.ASC)
                .forUpdate()
                .map { it[Notifications.id] }
        if (notificationIds.isNotEmpty()) {
            Notifications.deleteWhere { Notifications.id inList notificationIds }
        }
        return notificationIds
    }

    override suspend fun deletePendingByContext(
        context: NotificationContext,
        contextId: Long,
    ): List<Long> {
        val ids =
            Notifications
                .select(Notifications.id)
                .where {
                    (Notifications.context eq context) and
                        (Notifications.contextId eq contextId) and
                        Notifications.firedAt.isNull() and
                        Notifications.failedAt.isNull()
                }.map { it[Notifications.id] }

        if (ids.isNotEmpty()) {
            Notifications.deleteWhere { Notifications.id inList ids }
        }

        return ids
    }
}

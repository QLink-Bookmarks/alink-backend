package com.qlink.notification.worker.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.link.repository.LinkRepository
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.todo.repository.TodoRepository
import kotlin.time.Instant

class PrepareScheduledNotificationsService(
    private val tx: TransactionRunner,
    private val notificationRepository: NotificationRepository,
    private val todoRepository: TodoRepository,
    private val linkRepository: LinkRepository,
) {
    suspend fun prepare(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Notification> =
        tx.required {
            createMissingTodoNotifications(
                startInclusive = startInclusive,
                endExclusive = endExclusive,
            )
            notificationRepository.findPendingBetween(
                startInclusive = startInclusive,
                endExclusive = endExclusive,
            )
        }

    private suspend fun createMissingTodoNotifications(
        startInclusive: Instant,
        endExclusive: Instant,
    ) {
        todoRepository
            .findAllWithReminderBetween(
                startInclusive = startInclusive,
                endExclusive = endExclusive,
            ).sortedBy { it.linkId }
            .forEach { todo ->
                val todoId = todo.id ?: return@forEach
                val alreadyExists =
                    notificationRepository
                        .findByContext(
                            context = NotificationContext.TODO,
                            contextId = todoId,
                        ).any { it.isFrom(todo) }
                if (alreadyExists) return@forEach

                val link = linkRepository.findByIdForShare(todo.linkId) ?: return@forEach
                Notification
                    .todo(todo = todo, linkTitle = link.title, linkUrl = link.url)
                    ?.let { notificationRepository.insert(it) }
            }
    }
}

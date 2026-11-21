package com.qlink.notification.worker

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.logging.traceContext
import com.qlink.notification.domain.Notification
import com.qlink.notification.worker.service.FailScheduledNotificationService
import com.qlink.notification.worker.service.MarkNotificationScheduledService
import com.qlink.notification.worker.service.PrepareScheduledNotificationsService
import com.qlink.notification.worker.service.SendNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

private val DAILY_BATCH_TIME = LocalTime.of(23, 55)
private val UTC = ZoneOffset.UTC

class TaskScheduler(
    private val prepareScheduledNotificationsService: PrepareScheduledNotificationsService,
    private val markNotificationScheduledService: MarkNotificationScheduledService,
    private val failScheduledNotificationService: FailScheduledNotificationService,
    private val sendNotificationService: SendNotificationService,
    private val traceIdLength: Int,
    private val log: Logger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationJobs = ConcurrentHashMap<Long, Job>()

    fun start() {
        log.info("[TASK_SCHEDULER] Starting task scheduler")
        scope.launch(traceContext(traceIdLength)) {
            schedulePendingForUtcDate(utcToday())
        }
        scope.launch {
            startDailyBatch()
        }
    }

    fun stop() {
        log.info("[TASK_SCHEDULER] Stopping task scheduler")
        scope.cancel()
        notificationJobs.clear()
    }

    fun scheduleIfToday(notification: Notification) {
        if (notification.isPending && notification.willFireAt
                .toJavaInstant()
                .atZone(UTC)
                .toLocalDate() == utcToday()
        ) {
            schedule(notification.requireId())
        }
    }

    fun schedule(notificationId: Long) {
        cancel(notificationId)

        notificationJobs[notificationId] =
            scope.launch(traceContext(traceIdLength)) {
                val notification = markNotificationScheduledService.markScheduled(notificationId)

                delay(notification.delayFromNow())
                fire(notification.requireId())
            }
    }

    fun cancel(notificationId: Long) {
        notificationJobs.remove(notificationId)?.cancel()
    }

    private suspend fun startDailyBatch() {
        while (scope.isActive) {
            delayUntilNextDailyBatch()
            withContext(traceContext(traceIdLength)) {
                schedulePendingForUtcDate(utcToday().plusDays(1))
            }
        }
    }

    private suspend fun schedulePendingForUtcDate(date: LocalDate) {
        runCatching {
            val notifications = prepareScheduledNotificationsService.prepare(date)

            notifications.forEach { schedule(it.id!!) }
            log.info("[TASK_SCHEDULER] Pending notifications have been scheduled. date=$date, count=${notifications.size}")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.warn("[TASK_SCHEDULER] Failed to schedule pending notifications. date=$date", error)
        }
    }

    private suspend fun fire(notificationId: Long) {
        runCatching {
            sendNotificationService.send(notificationId)
        }.onFailure { exception ->
            logSendFailure(notificationId, exception)
            failScheduledNotificationService.fail(notificationId)
        }

        notificationJobs.remove(notificationId)
    }

    private fun logSendFailure(
        notificationId: Long,
        exception: Throwable,
    ) {
        when (exception) {
            is BusinessException -> {
                log.warn(
                    "[TASK_SCHEDULER] Failed to send notification by business exception. notificationId=$notificationId, " +
                        "errorCode=${exception.errorCode.code}",
                    exception,
                )
            }

            else -> {
                log.warn(
                    "[TASK_SCHEDULER] Failed to send notification. notificationId=$notificationId",
                    exception,
                )
            }
        }
    }

    private suspend fun delayUntilNextDailyBatch() {
        delay((nextDailyBatchAt() - Clock.System.now()).coerceAtLeast(ZERO))
    }

    private fun nextDailyBatchAt(now: Instant = Clock.System.now()): Instant {
        val zonedNow = now.toJavaInstant().atZone(UTC)
        val todayBatch =
            zonedNow
                .toLocalDate()
                .atTime(DAILY_BATCH_TIME)
                .atZone(UTC)
                .toInstant()
                .toKotlinInstant()

        return if (todayBatch > now) {
            todayBatch
        } else {
            todayBatch.plus(1.days)
        }
    }

    private fun Notification.delayFromNow(): Duration = (willFireAt - Clock.System.now()).coerceAtLeast(ZERO)

    private fun Notification.requireId(): Long = id ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)

    private fun utcToday(): LocalDate =
        Clock.System
            .now()
            .toJavaInstant()
            .atZone(UTC)
            .toLocalDate()
}

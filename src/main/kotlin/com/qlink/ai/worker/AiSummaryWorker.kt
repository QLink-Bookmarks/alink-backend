package com.qlink.ai.worker

import com.qlink.ai.worker.service.CountPendingAiSummaryJobsService
import com.qlink.ai.worker.service.FailAiSummaryService
import com.qlink.ai.worker.service.ProcessAiSummaryService
import com.qlink.ai.worker.service.RecoverAiSummaryJobsService
import com.qlink.common.logging.traceContext
import com.qlink.config.WorkerConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.TimeSource

class AiSummaryWorker(
    private val processAiSummaryService: ProcessAiSummaryService,
    private val recoverAiSummaryJobsService: RecoverAiSummaryJobsService,
    private val countPendingAiSummaryJobsService: CountPendingAiSummaryJobsService,
    private val failAiSummaryService: FailAiSummaryService,
    private val channel: Channel<Long>,
    private val config: WorkerConfig,
    private val meterRegistry: MeterRegistry,
    private val traceIdLength: Int,
    private val log: Logger,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(config.aiSummaryParallelism, DISPATCHER_NAME)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inFlight = AtomicInteger(0).also { meterRegistry.gauge(IN_FLIGHT_METRIC, it) }
    private val pending = AtomicLong(0).also { meterRegistry.gauge(PENDING_METRIC, it) }

    fun start() {
        log.info("[WORKER] Starting AI summary worker")
        scope.launch(CoroutineName("$DISPATCHER_NAME-consumer")) {
            for (jobId in channel) {
                withContext(traceContext(traceIdLength)) {
                    val started = TimeSource.Monotonic.markNow()
                    log.info("[WORKER] Job has been detected: $jobId")
                    inFlight.incrementAndGet()
                    try {
                        catchingFailure {
                            processAiSummaryService.proceed(jobId) { countResult(it.name.lowercase()) }
                        }.onFailure { error ->
                            log.warn("[WORKER] Failed to process AI summary. jobId=$jobId", error)
                            catchingFailure { fail(jobId = jobId, reason = error.failureSummary()) }
                                .onFailure { log.warn("[WORKER] Failed to mark AI summary failed. jobId=$jobId", it) }
                        }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    catchingFailure { refreshPending() }
                    log.info("[WORKER] Job has been finished. jobId=$jobId, durationMs=${started.elapsedNow().inWholeMilliseconds}")
                }
            }
        }
        scope.launch(CoroutineName("$DISPATCHER_NAME-recovery")) {
            while (isActive) {
                withContext(traceContext(traceIdLength)) {
                    catchingFailure { recover() }
                        .onFailure { log.warn("[WORKER] Failed to recover AI summary jobs", it) }
                }
                delay(config.pollInterval)
            }
        }
    }

    fun stop() {
        log.info("[WORKER] Stopping AI summary worker")
        scope.cancel()
    }

    private suspend fun recover() {
        val runnableIds =
            recoverAiSummaryJobsService.recover { jobId ->
                log.info("[WORKER] Stale job has been failed. jobId=$jobId")
                countResult(RESULT_EXPIRED)
            }
        runnableIds.forEach { channel.send(it) }
        refreshPending()
    }

    private suspend fun fail(
        jobId: Long,
        reason: String,
    ) {
        failAiSummaryService.fail(jobId = jobId, reason = reason, now = Clock.System.now())
        countResult(RESULT_FAILED)
    }

    private suspend fun refreshPending() {
        pending.set(countPendingAiSummaryJobsService.count())
    }

    private fun countResult(result: String) {
        Counter
            .builder(RESULT_METRIC)
            .tag("result", result)
            .register(meterRegistry)
            .increment()
    }

    private companion object {
        const val DISPATCHER_NAME = "ai-summary-worker"
        const val IN_FLIGHT_METRIC = "ai_summary_in_flight"
        const val PENDING_METRIC = "ai_summary_pending"
        const val RESULT_METRIC = "ai_summary_results"
        const val RESULT_FAILED = "failed"
        const val RESULT_EXPIRED = "expired"
    }
}

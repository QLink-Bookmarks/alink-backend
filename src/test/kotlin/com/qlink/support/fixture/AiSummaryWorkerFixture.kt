package com.qlink.support.fixture

import com.qlink.ai.worker.AiSummaryWorker
import com.qlink.ai.worker.service.FailAiSummaryService
import com.qlink.ai.worker.service.ProcessAiSummaryService
import com.qlink.config.WorkerConfig
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.koinGet
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

fun createAiSummaryWorker(
    process: ProcessAiSummaryService = koinGet(),
    fail: FailAiSummaryService = koinGet(),
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    channel: Channel<Long> = koinGet(),
): AiSummaryWorker =
    AiSummaryWorker(
        processAiSummaryService = process,
        recoverAiSummaryJobsService = koinGet(),
        countPendingAiSummaryJobsService = koinGet(),
        completeAiSummaryService = koinGet(),
        failAiSummaryService = fail,
        channel = channel,
        config =
            WorkerConfig(
                pollInterval = RandomFixture.randomInt(1, 10).minutes,
                importThumbnailConcurrency = RandomFixture.randomInt(1, 10),
                importThumbnailParallelism = RandomFixture.randomInt(1, 10),
                importThumbnailBatchSize = RandomFixture.randomInt(1, 100),
                aiSummaryParallelism = RandomFixture.randomInt(1, 10),
            ),
        meterRegistry = meterRegistry,
        traceIdLength = ServiceTestEnvironment.TRACE_ID_LENGTH,
        log = LoggerFactory.getLogger(AiSummaryWorker::class.java),
    )

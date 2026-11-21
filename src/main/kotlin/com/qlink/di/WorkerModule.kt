package com.qlink.di

import com.qlink.ai.worker.AiSummaryDispatcher
import com.qlink.ai.worker.AiSummaryWorker
import com.qlink.ai.worker.service.CompleteAiSummaryService
import com.qlink.ai.worker.service.CountPendingAiSummaryJobsService
import com.qlink.ai.worker.service.FailAiSummaryService
import com.qlink.ai.worker.service.ProcessAiSummaryService
import com.qlink.ai.worker.service.RecoverAiSummaryJobsService
import com.qlink.bookmarkimport.worker.ImportThumbnailSignal
import com.qlink.bookmarkimport.worker.ImportThumbnailWorker
import com.qlink.bookmarkimport.worker.service.CountImportThumbnailTargetsService
import com.qlink.bookmarkimport.worker.service.FillImportThumbnailService
import com.qlink.bookmarkimport.worker.service.GetImportThumbnailTargetsService
import com.qlink.config.MonitoringConfig
import com.qlink.config.WorkerConfig
import com.qlink.link.worker.LinkPreviewFillSignal
import com.qlink.link.worker.LinkPreviewFillWorker
import com.qlink.link.worker.service.FillLinkPreviewService
import com.qlink.linkcontent.worker.LinkContentSignal
import com.qlink.linkcontent.worker.LinkContentWorker
import com.qlink.linkcontent.worker.service.CollectLinkContentService
import com.qlink.linkcontent.worker.service.GetLinkContentScheduleService
import com.qlink.linkcontent.worker.service.GetLinkContentTargetsService
import com.qlink.notification.worker.TaskScheduler
import com.qlink.notification.worker.service.FailScheduledNotificationService
import com.qlink.notification.worker.service.MarkNotificationScheduledService
import com.qlink.notification.worker.service.PrepareScheduledNotificationsService
import com.qlink.notification.worker.service.SendNotificationService
import io.ktor.server.config.ApplicationConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.channels.Channel
import org.koin.dsl.module
import org.slf4j.LoggerFactory

fun workerModule(config: ApplicationConfig) =
    module {
        single { WorkerConfig.from(config) }

        single {
            Channel<Long>(capacity = Channel.BUFFERED)
        }

        single {
            AiSummaryDispatcher(channel = get())
        }

        single {
            CompleteAiSummaryService(
                tx = get(),
                aiJobRepository = get(),
                dailyUsageRepository = get(),
                folderRepository = get(),
                folderMemberRepository = get(),
                linkRepository = get(),
                todoRepository = get(),
                scheduleTodoNotificationService = get(),
                urlResolver = get(),
                log = LoggerFactory.getLogger(CompleteAiSummaryService::class.java),
            )
        }

        single {
            FailAiSummaryService(
                tx = get(),
                aiJobRepository = get(),
                linkRepository = get(),
                urlResolver = get(),
            )
        }

        single {
            ProcessAiSummaryService(
                tx = get(),
                aiJobRepository = get(),
                userProviderRepository = get(),
                availableModelRepository = get(),
                aiProviderRepository = get(),
                dailyUsageRepository = get(),
                folderRepository = get(),
                linkRepository = get(),
                aiClientRouter = get(),
                apiKeyCipher = get(),
                log = LoggerFactory.getLogger(AiSummaryWorker::class.java),
            )
        }

        single {
            RecoverAiSummaryJobsService(
                tx = get(),
                aiJobRepository = get(),
            )
        }

        single {
            CountPendingAiSummaryJobsService(
                tx = get(),
                aiJobRepository = get(),
            )
        }

        single {
            AiSummaryWorker(
                processAiSummaryService = get(),
                recoverAiSummaryJobsService = get(),
                countPendingAiSummaryJobsService = get(),
                completeAiSummaryService = get(),
                failAiSummaryService = get(),
                channel = get(),
                config = get(),
                meterRegistry = get<PrometheusMeterRegistry>(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(AiSummaryWorker::class.java),
            )
        }

        single {
            ImportThumbnailSignal()
        }

        single {
            LinkPreviewFillSignal()
        }

        single {
            FillLinkPreviewService(
                tx = get(),
                linkRepository = get(),
                previewClient = get(),
                urlResolver = get(),
            )
        }

        single {
            LinkPreviewFillWorker(
                signal = get(),
                fillService = get(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(LinkPreviewFillWorker::class.java),
            )
        }

        single {
            FillImportThumbnailService(
                tx = get(),
                bookmarkImportRepository = get(),
                linkRepository = get(),
                previewClient = get(),
                urlResolver = get(),
            )
        }

        single {
            GetImportThumbnailTargetsService(
                tx = get(),
                bookmarkImportRepository = get(),
            )
        }

        single {
            CountImportThumbnailTargetsService(
                tx = get(),
                bookmarkImportRepository = get(),
            )
        }

        single {
            ImportThumbnailWorker(
                signal = get(),
                getTargetsService = get(),
                countTargetsService = get(),
                fillService = get(),
                config = get(),
                meterRegistry = get<PrometheusMeterRegistry>(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(ImportThumbnailWorker::class.java),
            )
        }

        single {
            LinkContentSignal()
        }

        single {
            CollectLinkContentService(
                tx = get(),
                linkContentRepository = get(),
                linkRepository = get(),
                contentClient = get(),
            )
        }

        single {
            GetLinkContentTargetsService(
                tx = get(),
                linkContentRepository = get(),
            )
        }

        single {
            GetLinkContentScheduleService(
                tx = get(),
                linkContentRepository = get(),
            )
        }

        single {
            LinkContentWorker(
                signal = get(),
                getTargetsService = get(),
                getScheduleService = get(),
                collectService = get(),
                config = get(),
                pollInterval = get<WorkerConfig>().pollInterval,
                meterRegistry = get<PrometheusMeterRegistry>(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(LinkContentWorker::class.java),
            )
        }

        single {
            PrepareScheduledNotificationsService(
                tx = get(),
                notificationRepository = get(),
                todoRepository = get(),
                linkRepository = get(),
            )
        }

        single {
            MarkNotificationScheduledService(
                tx = get(),
                notificationRepository = get(),
            )
        }

        single {
            FailScheduledNotificationService(
                tx = get(),
                notificationRepository = get(),
            )
        }

        single {
            SendNotificationService(
                tx = get(),
                notificationRepository = get(),
                userRepository = get(),
                deviceTokenRepository = get(),
                senderRouter = get(),
                todoRepository = get(),
                linkRepository = get(),
                log = LoggerFactory.getLogger(SendNotificationService::class.java),
            )
        }

        single {
            TaskScheduler(
                prepareScheduledNotificationsService = get(),
                markNotificationScheduledService = get(),
                failScheduledNotificationService = get(),
                sendNotificationService = get(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(TaskScheduler::class.java),
            )
        }
    }

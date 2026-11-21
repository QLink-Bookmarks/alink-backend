package com.qlink.di

import com.qlink.config.MonitoringConfig
import com.qlink.config.NotificationConfig
import com.qlink.notification.worker.TaskScheduler
import com.qlink.notification.worker.service.FailScheduledNotificationService
import com.qlink.notification.worker.service.MarkNotificationScheduledService
import com.qlink.notification.worker.service.PrepareScheduledNotificationsService
import com.qlink.push.client.ExpoPushClient
import com.qlink.push.client.FcmPushClient
import com.qlink.push.client.FirebaseInitializer
import com.qlink.push.client.PushNotificationSenderRouter
import org.koin.dsl.module
import org.slf4j.LoggerFactory

fun notificationModule(config: NotificationConfig) =
    module {
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
            TaskScheduler(
                prepareScheduledNotificationsService = get(),
                markNotificationScheduledService = get(),
                failScheduledNotificationService = get(),
                sendNotificationService = get(),
                traceIdLength = get<MonitoringConfig>().httpLogging.traceIdLength,
                log = LoggerFactory.getLogger(TaskScheduler::class.java),
            )
        }

        single {
            FirebaseInitializer(config.fcm.serviceAccountJson)
        }

        single {
            get<FirebaseInitializer>().initializeIfConfigured()
            FcmPushClient(firebaseInitializer = get())
        }

        single {
            ExpoPushClient(
                httpClient = get(),
                sendUrl = config.expo.sendUrl,
                accessToken = config.expo.accessToken,
            )
        }

        single {
            PushNotificationSenderRouter(
                senders =
                    listOf(
                        get<FcmPushClient>(),
                        get<ExpoPushClient>(),
                    ),
            )
        }
    }

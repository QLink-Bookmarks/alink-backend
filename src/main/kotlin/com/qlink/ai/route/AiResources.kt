package com.qlink.ai.route

import io.ktor.resources.Resource

@Resource("/ai")
class AiResources {
    @Resource("providers")
    class Providers(
        val parent: AiResources = AiResources(),
        val includesInactive: Boolean = false,
    ) {
        @Resource("{id}")
        class ById(
            val parent: Providers = Providers(),
            val id: Long,
        ) {
            @Resource("shared-key")
            class SharedKey(
                val parent: ById,
            )

            @Resource("active")
            class Active(
                val parent: ById,
            )
        }

        @Resource("models")
        class Models(
            val parent: Providers = Providers(),
            val isMine: Boolean = false,
        )
    }

    @Resource("models")
    class Models(
        val parent: AiResources = AiResources(),
    ) {
        @Resource("{id}")
        class ById(
            val parent: Models = Models(),
            val id: Long,
        ) {
            @Resource("active")
            class Active(
                val parent: ById,
            )
        }
    }

    @Resource("daily-usage")
    class DailyUsage(
        val parent: AiResources = AiResources(),
        val date: String? = null,
    )

    @Resource("users/providers")
    class UserProviders(
        val parent: AiResources = AiResources(),
    )
}

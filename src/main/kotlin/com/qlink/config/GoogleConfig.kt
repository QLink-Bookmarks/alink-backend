package com.qlink.config

import io.ktor.server.config.ApplicationConfig

data class GoogleConfig(
    val clientIds: List<String>,
    val adminClientIds: List<String> = emptyList(),
) {
    companion object {
        fun from(
            config: ApplicationConfig,
            env: Map<String, String> = System.getenv(),
        ): GoogleConfig =
            GoogleConfig(
                adminClientIds = env.clientIdsOrNull("GOOGLE_ADMIN_CLIENT_IDS") ?: config.optionalStringList("google.adminClientIds"),
                clientIds =
                    env.clientIdsOrNull("GOOGLE_CLIENT_IDS")
                        ?: config.optionalStringList("google.clientIds"),
            )
    }
}

private fun Map<String, String>.clientIdsOrNull(key: String): List<String>? =
    get(key)
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.takeIf(List<String>::isNotEmpty)

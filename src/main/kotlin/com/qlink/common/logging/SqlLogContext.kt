package com.qlink.common.logging

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

private const val REDACT_SQL_MDC_KEY = "redactSql"

internal fun isSqlLoggingRedacted(): Boolean = MDC.get(REDACT_SQL_MDC_KEY) == "true"

internal suspend fun <T> withRedactedSqlLogging(block: suspend () -> T): T =
    withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + (REDACT_SQL_MDC_KEY to "true"))) { block() }

package com.qlink.config

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.slf4j.Logger
import org.slf4j.MDC

private const val REDACT_SQL_MDC_KEY = "redactSql"

internal suspend fun <T> withRedactedSqlLogging(block: suspend () -> T): T =
    withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + (REDACT_SQL_MDC_KEY to "true"))) { block() }

class AbbreviatedSqlLogger(
    private val log: Logger,
) : SqlLogger {
    override fun log(
        context: StatementContext,
        transaction: Transaction,
    ) {
        if (!log.isDebugEnabled) return
        if (MDC.get(REDACT_SQL_MDC_KEY) == "true") {
            log.debug("SQL operation={} values redacted", context.statement.type)
            return
        }

        val args =
            context.args.map { (type, value) ->
                val textType = type as? AbbreviatedTextColumnType
                type to if (textType != null && value is String) textType.abbreviate(value) else value
            }
        val logContext = StatementContext(statement = context.statement, args = args)
        log.debug(logContext.expandArgs(transaction))
    }
}

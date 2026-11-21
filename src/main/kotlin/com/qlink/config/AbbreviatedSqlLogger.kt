package com.qlink.config

import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.slf4j.Logger

class AbbreviatedSqlLogger(
    private val log: Logger,
) : SqlLogger {
    override fun log(
        context: StatementContext,
        transaction: Transaction,
    ) {
        if (!log.isDebugEnabled) return

        val args =
            context.args.map { (type, value) ->
                val textType = type as? AbbreviatedTextColumnType
                type to if (textType != null && value is String) textType.abbreviate(value) else value
            }
        val logContext = StatementContext(statement = context.statement, args = args)
        log.debug(logContext.expandArgs(transaction))
    }
}

package com.qlink.config

import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.slf4j.Logger

class AbbreviatedSqlLogger(
    private val log: Logger,
) : SqlLogger {
    override fun log(
        context: StatementContext,
        transaction: Transaction,
    ) {
        if (!log.isDebugEnabled) return
        log.debug(context.sql(transaction))
    }
}

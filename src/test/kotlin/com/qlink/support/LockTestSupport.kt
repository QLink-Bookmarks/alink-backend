package com.qlink.support

import com.qlink.common.transaction.TransactionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

suspend fun <T> runWhileLinkLocked(
    tx: TransactionRunner,
    holdLock: suspend () -> Unit,
    afterWaiting: suspend () -> Unit = {},
    action: suspend () -> T,
): T =
    runWhileLocked(
        tx = tx,
        table = "links",
        holdLock = holdLock,
        afterWaiting = afterWaiting,
        action = action,
    )

suspend fun <T> runWhileFolderLocked(
    tx: TransactionRunner,
    holdLock: suspend () -> Unit,
    afterWaiting: suspend () -> Unit,
    action: suspend () -> T,
): T =
    runWhileLocked(
        tx = tx,
        table = "folders",
        holdLock = holdLock,
        afterWaiting = afterWaiting,
        action = action,
    )

private suspend fun <T> runWhileLocked(
    tx: TransactionRunner,
    table: String,
    holdLock: suspend () -> Unit,
    afterWaiting: suspend () -> Unit,
    action: suspend () -> T,
): T =
    coroutineScope {
        val locked = CompletableDeferred<Int>()
        val release = CompletableDeferred<Unit>()
        val competitor =
            async {
                tx.required {
                    holdLock()
                    locked.complete(backendPid())
                    release.await()
                    afterWaiting()
                }
            }
        val blockerPid = locked.await()
        val result = async { action() }
        withTimeout(10.seconds) {
            var waiterPid = tx.readOnly { findWaitingBackend(table, blockerPid) }
            while (waiterPid == null) {
                delay(20.milliseconds)
                waiterPid = tx.readOnly { findWaitingBackend(table, blockerPid) }
            }
            println("PostgreSQL lock wait on $table: backend $waiterPid blocked by backend $blockerPid")
        }
        release.complete(Unit)
        competitor.await()
        result.await()
    }

private fun backendPid(): Int =
    TransactionManager.current().exec("SELECT pg_backend_pid()") {
        it.next()
        it.getInt(1)
    }!!

private fun findWaitingBackend(
    table: String,
    blockerPid: Int,
): Int? =
    TransactionManager.current().exec(
        "SELECT pid FROM pg_stat_activity " +
            "WHERE wait_event_type = 'Lock' AND datname = current_database() " +
            "AND $blockerPid = ANY(pg_blocking_pids(pid)) AND query ~* '\\m$table\\M'",
    ) { it.takeIf { row -> row.next() }?.getInt(1) }

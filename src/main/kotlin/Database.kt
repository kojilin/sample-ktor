import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.withSuspendTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import kotlin.time.Duration.Companion.seconds


class PermitController {
    private val semaphore = Semaphore(30)
    suspend fun <T> permit(block: suspend () -> T): T {
        val acquiredPermit = try {
            withTimeoutOrNull(30.seconds) {
                semaphore.acquire()
                true
            } ?: false
        } finally {
            // future for metrics
        }
        if (!acquiredPermit) {
            throw IllegalStateException("Could not acquire permit")
        }
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }
}

private suspend fun <T> Database.withPermit(permitController: PermitController, block: suspend () -> T): T {
    if (transactionManager.currentOrNull() != null) {
        return block()
    }
    return permitController.permit {
        block()
    }
}


suspend fun <T> Database.transactionOnDbDispatcher(
    permitController: PermitController,
    dbDispatcher: CoroutineDispatcher,
    statement: Transaction.() -> T
): T = withPermit(permitController) {
    withContext(dbDispatcher) {
        transaction(this@transactionOnDbDispatcher, statement = statement)
    }
}

suspend fun <T> Database.suspendedTransactionOnDbDispatcher(
    permitController: PermitController,
    dbDispatcher: CoroutineDispatcher,
    statement: suspend Transaction.() -> T
): T = withPermit(permitController) {
    supervisorScope {
        transactionManager.currentOrNull()?.withSuspendTransaction(
            dbDispatcher,
            statement = statement
        )
            ?: newSuspendedTransaction(
                dbDispatcher,
                db = this@suspendedTransactionOnDbDispatcher,
                statement = statement
            )
    }
}
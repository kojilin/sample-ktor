package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import java.util.concurrent.Executors
import kotlin.random.Random

val threadLocal = ThreadLocal<String>()

private fun txHash(tx: Any?) = tx?.let { System.identityHashCode(it) }

private suspend fun probeCurrent(db: Database) = txHash(db.transactionManager.currentOrNull())

// TEMP test route target: mimics Exposed's newSuspendedTransaction internals
// (async on a dedicated dispatcher carrying a ThreadContextElement)
val dbDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

fun Application.configureRouting() {
    routing {
        get("/foo") {
            require(threadLocal.get() == null) { "1 - thread local should not exist" }
            withContext(threadLocal.asContextElement(Random.nextInt(100).toString())) {
                require(threadLocal.get() != null) { "2 - thread local should exist now" }
                delay(200)
                require(threadLocal.get() != null) { "3 - thread local should exist after resume" }
            }
            require(threadLocal.get() == null) { "4 - thread local should not exist" }
            call.respondText("Hello World!")
        }
        get("/bar") {
            require(threadLocal.get() == null) { "1 - thread local should not exist at the start" }
            withContext(
                Dispatchers.IO +
                        threadLocal.asContextElement(Random.nextInt(100).toString())
            ) {
                require(threadLocal.get() != null) { "2 - thread local should exist now" }
                delay(200)
                require(threadLocal.get() != null) { "3 - thread local should exist after resume" }
            }
            require(threadLocal.get() == null) { "4 - thread local should not exist outside the withContext" }
            call.respondText("Hello World!")
        }
        get("/exposed-style") {
            require(threadLocal.get() == null) { "1 - thread local should not exist at the start" }
            val result = supervisorScope {
                async(dbDispatcher + threadLocal.asContextElement("tx-" + Random.nextInt(100))) {
                    require(threadLocal.get() != null) { "2 - element should be set in tx coroutine" }
                    delay(200)
                    require(threadLocal.get() != null) { "3 - element should be restored after resume" }
                    "Hello World!"
                }.await()
            }
            require(threadLocal.get() == null) { "4 - handler frame should be clean after tx" }
            call.respondText(result)
        }
        get("/blocking-style") {
            require(threadLocal.get() == null) { "1 - thread local should not exist at the start" }
            val txThread = withContext(dbDispatcher) {
                threadLocal.set("tx-" + Random.nextInt(100))
                try {
                    require(threadLocal.get() != null) { "2 - thread local should be set in tx" }
                    Thread.sleep(100) // mimic blocking JDBC work
                    require(threadLocal.get() != null) { "3 - thread local should survive blocking work" }
                    Thread.currentThread().name
                } finally {
                    threadLocal.remove()
                }
            }
            require(threadLocal.get() == null) { "4 - handler frame should be clean after tx" }
            call.respondText("OK txThread=$txThread resumedOn=${Thread.currentThread().name}")
        }
        get("/mode") {
            call.respondText("developmentMode=${call.application.developmentMode}")
        }

        val database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val permitController = PermitController()

        get("/tx-helper") {
            val startLeak = database.transactionManager.currentOrNull()
            val txThread = database.suspendedTransactionOnDbDispatcher(permitController, dbDispatcher) {
                exec("SELECT 1")
                delay(100)
                Thread.currentThread().name
            }
            val afterLeak = database.transactionManager.currentOrNull()
            call.respondText(
                "startLeak=${startLeak != null} txThread=$txThread " +
                    "afterThread=${Thread.currentThread().name} afterLeak=${afterLeak != null}"
            )
        }

        get("/tx-plain") {
            val startLeak = database.transactionManager.currentOrNull()
            val txThread = newSuspendedTransaction(db = database) {
                exec("SELECT 1")
                delay(100)
                Thread.currentThread().name
            }
            val afterLeak = database.transactionManager.currentOrNull()
            call.respondText(
                "startLeak=${startLeak != null} txThread=$txThread " +
                    "afterThread=${Thread.currentThread().name} afterLeak=${afterLeak != null}"
            )
        }

        get("/tx-mixed") {
            // plain newSuspendedTransaction leaves a stale binding on this thread...
            var outerTx = 0
            newSuspendedTransaction(db = database) {
                outerTx = System.identityHashCode(this)
                exec("SELECT 1")
                delay(100)
            }
            // ...and the helper called in the stale window misreads currentOrNull
            val staleBefore = database.transactionManager.currentOrNull()
            val result = runCatching {
                database.suspendedTransactionOnDbDispatcher(permitController, dbDispatcher) {
                    exec("SELECT 1")
                    "helperTx=${System.identityHashCode(this)}"
                }
            }
            call.respondText(
                "outerTx=$outerTx staleBefore=${staleBefore?.let { System.identityHashCode(it) }} " +
                    "result=${result.getOrElse { "FAILED: ${it::class.simpleName}: ${it.message?.take(120)}" }}"
            )
        }

        get("/tx-probe") {
            var outerTx = 0
            newSuspendedTransaction(db = database) {
                outerTx = System.identityHashCode(this)
                exec("SELECT 1")
                delay(100)
            }
            val s1 = txHash(database.transactionManager.currentOrNull()) // straight-line read
            val s2 = probeCurrent(database) // after a plain suspend-fun call
            val s3 = supervisorScope { txHash(database.transactionManager.currentOrNull()) } // inside supervisorScope
            val s4 = txHash(database.transactionManager.currentOrNull()) // after scope returns
            call.respondText("outerTx=$outerTx s1=$s1 s2=$s2 s3=$s3 s4=$s4 thread=${Thread.currentThread().name}")
        }
    }
}

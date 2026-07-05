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
import java.util.concurrent.Executors
import kotlin.random.Random

val threadLocal = ThreadLocal<String>()

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
    }
}

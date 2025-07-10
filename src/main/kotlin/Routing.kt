package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

val threadLocal = ThreadLocal<String>()

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
    }
}

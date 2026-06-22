package com.jonnyzzz.mcpSteroid.devrig


fun waitFor(timeoutMillis: Long, condition: String = "condition", action: () -> Boolean) {
    println("Waiting $condition for $timeoutMillis ms...")
    val now = System.currentTimeMillis()
    Thread.sleep(50)
    var lastException: Exception? = null
    while (System.currentTimeMillis() - now < timeoutMillis) {
        try {
            if (action()) return
            lastException = null
        } catch (e: Exception) {
            lastException = e
        }
        Thread.sleep(50)
    }
    val elapsed = System.currentTimeMillis() - now
    val msg = buildString {
        append("Failed waiting for $condition after ${elapsed}ms!")
        val exc = lastException
        if (exc != null) append(" Last error: ${exc.message}")
    }
    throw RuntimeException(msg, lastException)
}

fun <T : Any> waitForValue(timeoutMillie: Long, condition: String = "condition", action: () -> T?): T {
    var value: T? = null
    waitFor(timeoutMillie, condition) {
        value = action()
        value != null
    }
    return value ?: throw RuntimeException("Failed waiting for $condition!")
}


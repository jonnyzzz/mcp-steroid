/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/** A started fake HTTP server bound to [port] on 127.0.0.1. */
class TestHttpServer(val server: EmbeddedServer<*, *>, val port: Int)

/**
 * Starts [module] on an **ephemeral** port and returns the port it actually bound.
 *
 * This is the fixture shape for every fake-IDE / fake-backend server in `:npx-kt:test`. It replaces
 * `port = ServerSocket(0).use { it.localPort }` followed by `embeddedServer(port = port)`, which had two
 * defects that together produced jonnyzzz/mcp-steroid#477 — a flake reproduced 2 times in 14 loaded runs:
 *
 *  1. **Probe-then-bind race.** Closing the probe socket and binding that number later leaves a window in
 *     which anything (another test, an ephemeral client port) can take it. Binding port 0 has no window.
 *  2. **Nobody observed startup.** `start(wait = false)` publishes failures into the server's lazily
 *     started coroutine, and `runBlocking { server.monitor.subscribe(ApplicationStarted) {} }` was not the
 *     barrier it looked like — `subscribe` only registers a handler, it never waits. (Production does wait
 *     correctly: `SteroidsMcpServer` pairs subscribe/unlock with a blocking `lock()`.) So a
 *     `BindException: Address already in use` stayed invisible, cancelled the coroutine's parent, and hit
 *     whichever test later touched the server as
 *     `JobCancellationException: LazyStandaloneCoroutine is cancelling` — a message naming neither the
 *     port nor the bind.
 *
 * `resolvedConnectors()` suspends until the socket is really listening and yields the bound port, so a
 * startup failure is thrown HERE, from the fixture, with its own stack trace.
 *
 * The server is started at top level on purpose: called inside `runBlocking`, `embeddedServer` resolves to
 * the `CoroutineScope` extension, the server becomes a child of the calling coroutine, and the caller then
 * waits for it forever (so `@AfterEach` never runs).
 */
fun startTestHttpServer(module: Application.() -> Unit): TestHttpServer {
    val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1", module = module)
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    return TestHttpServer(server, port)
}

/**
 * A port with nothing listening on it, for the "connection refused" cases.
 *
 * Probe-then-close is correct HERE — the point is that the port is unbound. Never use this to pick a port
 * to bind later: use [startTestHttpServer], which binds port 0 and reports what it got (#477).
 */
fun unboundPort(): Int = java.net.ServerSocket(0).use { it.localPort }

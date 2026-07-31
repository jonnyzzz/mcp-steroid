/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contract for [VfsRefreshService]. These tests pin the two guarantees the
 * service's kdoc makes: [VfsRefreshService.scheduleAsyncRefresh] is
 * fire-and-forget (returns well before any refresh work happens), and
 * [VfsRefreshService.awaitRefresh] resolves or times out — never hangs.
 *
 * We do not try to observe the refresh effect here; the
 * `RefreshQueue.getInstance()` is coalescing + single-threaded and its
 * timing is environment-dependent, which makes "was something refreshed"
 * assertions flaky in unit tests. The production semantics are validated
 * end-to-end by the integration tests.
 */
class VfsRefreshServiceTest : BasePlatformTestCase() {

    // Run tests off the EDT so `timeoutRunBlocking` doesn't park the dispatch
    // thread while the service-under-test needs `withContext(Dispatchers.EDT)`
    // or similar platform-coroutine dispatches — classic deadlock otherwise.
    override fun runInDispatchThread(): Boolean = false

    fun testScheduleAsyncRefreshReturnsImmediately(): Unit = timeoutRunBlocking(30.seconds) {
        val service = project.vfsRefreshService
        val elapsedMs = measureTimeMillis {
            service.scheduleAsyncRefresh()
        }
        // The call launches a coroutine on the service's injected scope and returns.
        // Give it a generous cap (500 ms) to protect against CI jitter — a blocking
        // implementation would take multiple seconds for a real refresh.
        assertTrue(
            "scheduleAsyncRefresh should be fire-and-forget, took ${elapsedMs}ms",
            elapsedMs < 500L,
        )
    }

    fun testAwaitRefreshCompletesWithinCap(): Unit = timeoutRunBlocking(40.seconds) {
        val service = project.vfsRefreshService
        val elapsedMs = measureTimeMillis {
            service.awaitRefresh()
        }
        // Contract: awaitRefresh either finishes (RefreshQueue callback fires) OR
        // times out at 30 s and logs a warning. In a healthy test fixture with a
        // real project.basePath, refresh runs quickly; the ceiling guards against a
        // regression where the callback bridge breaks and the function hangs
        // longer than the advertised 30 s cap.
        assertTrue(
            "awaitRefresh honoured the 30 s cap but took ${elapsedMs}ms",
            elapsedMs < 35_000L,
        )
    }

    fun testScheduleAsyncRefreshTwiceIsSafe(): Unit = timeoutRunBlocking(30.seconds) {
        // Back-to-back steroid_execute_code calls stack two schedules; they must
        // both succeed without throwing (RefreshQueue's coalescing handles the
        // redundancy internally).
        val service = project.vfsRefreshService
        service.scheduleAsyncRefresh()
        service.scheduleAsyncRefresh()
        // If we got here without an exception, the contract holds.
    }

    fun testAwaitRefreshFromTheEdtFailsFast(): Unit = timeoutRunBlocking(30.seconds) {
        // jonnyzzz/mcp-steroid#318: awaiting the recursive refresh from the EDT ran the
        // RefreshWorker directory scan ON AWT-EventQueue-0 (31s captured freeze). The guard must
        // fail fast — long before any traversal — so no caller can reintroduce the freeze.
        val service = project.vfsRefreshService
        val failure = try {
            withContext(Dispatchers.EDT) { service.awaitRefresh() }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(
            "awaitRefresh from the EDT must throw IllegalStateException, got: $failure",
            failure is IllegalStateException && failure.message!!.contains("#318"),
        )
    }

    fun testAwaitRefreshWithNoProjectBasePathIsNoOp(): Unit = timeoutRunBlocking(5.seconds) {
        // In BasePlatformTestCase the light project always has a basePath. We can't
        // easily simulate a null basePath without reflection shenanigans, so this
        // test simply asserts the happy path returns cleanly — the no-project-
        // basePath skip branch is exercised by integration (project close) paths.
        project.vfsRefreshService.awaitRefresh()
    }
}

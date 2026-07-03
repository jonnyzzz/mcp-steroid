/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Sticky-cancel contract of [McpExecutionProgressIndicator] (#213).
 *
 * The whole reason this class exists instead of `EmptyProgressIndicator`: the platform's
 * `EmptyProgressIndicator.start()` CLEARS the cancellation flag, so a consumer running
 * `ProgressManager.runProcess(..., indicator)` would silently un-cancel an already-cancelled
 * execution. These tests pin the sticky behavior the `ScriptExecutor` watcher relies on.
 */
class McpExecutionProgressIndicatorTest : BasePlatformTestCase() {

    fun testFreshIndicatorIsNotCancelled() {
        val indicator = McpExecutionProgressIndicator()
        assertFalse("A fresh indicator must not be cancelled", indicator.isCanceled)
        // Must not throw:
        indicator.checkCanceled()
    }

    fun testCancelledIndicatorThrowsFromCheckCanceled() {
        val indicator = McpExecutionProgressIndicator()
        indicator.cancel()
        assertTrue(indicator.isCanceled)
        try {
            indicator.checkCanceled()
            fail("checkCanceled() must throw ProcessCanceledException after cancel()")
        } catch (e: ProcessCanceledException) {
            // expected
        }
    }

    fun testCancellationIsStickyAcrossStart() {
        // The EmptyProgressIndicator trap: start() clears the cancelled flag there.
        // Our indicator must keep it — start() must not resurrect a cancelled execution.
        val indicator = McpExecutionProgressIndicator()
        indicator.cancel()
        indicator.start()
        assertTrue("cancel() must survive start()", indicator.isCanceled)
        indicator.stop()
        assertTrue("cancel() must survive stop()", indicator.isCanceled)
    }

    fun testRunProcessOnCancelledIndicatorStaysCancelled() {
        // ProgressManager.runProcess() calls indicator.start() before running the task and
        // installs the indicator on the thread — the exact path InspectionEngine.inspectEx
        // worker threads take. A cancelled execution must be observed by the very first
        // ProgressManager.checkCanceled() inside.
        val indicator = McpExecutionProgressIndicator()
        indicator.cancel()
        var reached = false
        try {
            ProgressManager.getInstance().runProcess(
                Runnable {
                    ProgressManager.checkCanceled()
                    reached = true
                },
                indicator,
            )
            fail("runProcess on a cancelled sticky indicator must abort with ProcessCanceledException")
        } catch (e: ProcessCanceledException) {
            // expected
        }
        assertFalse("checkCanceled() must have aborted the task", reached)
        assertTrue("the indicator must still be cancelled after runProcess", indicator.isCanceled)
    }
}

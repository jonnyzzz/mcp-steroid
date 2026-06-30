/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [pollWhileIdeBusy] and [isTransientMcpRequestFailure] — the retry policy
 * `mcpExecuteCode` uses to keep waiting while the IDE is saturated by a big-project import/indexing.
 *
 * Two busy signals are treated the same ("call again"): a clean result carrying the
 * [INDEXING_IN_PROGRESS_MARKER], AND a transient transport failure — the execute_code request itself
 * could not complete in its window because the IDE was too busy to answer, so the curl was killed
 * (exit -1). Before this fix only the first was retried; the second propagated as a hard failure,
 * which is exactly how Keycloak's Maven import broke `mcpSetProjectSdk` on CI. See
 * jonnyzzz/mcp-steroid#169.
 */
class PollWhileIdeBusyTest {

    private class FakeClock(var nowMs: Long = 0L) {
        val sleeps = mutableListOf<Long>()
        fun now() = nowMs
        fun sleep(ms: Long) { sleeps += ms; nowMs += ms }
    }

    @Test
    fun `returns the first result when the IDE is not busy`() {
        val clock = FakeClock()
        var attempts = 0
        val result = pollWhileIdeBusy(
            deadlineMs = 10_000,
            now = clock::now,
            sleep = clock::sleep,
            isBusy = { false },
            transientFailure = { false },
            attempt = { attempts++; "ok" },
        )
        assertEquals("ok", result)
        assertEquals(1, attempts)
        assertTrue(clock.sleeps.isEmpty(), "must not sleep when not busy")
    }

    @Test
    fun `retries while a clean busy-marker result keeps coming, then returns success`() {
        val clock = FakeClock()
        val results = ArrayDeque(listOf("busy", "busy", "done"))
        val result = pollWhileIdeBusy(
            deadlineMs = 10_000,
            now = clock::now,
            sleep = clock::sleep,
            isBusy = { it == "busy" },
            transientFailure = { false },
            attempt = { results.removeFirst() },
        )
        assertEquals("done", result)
        assertEquals(2, clock.sleeps.size, "slept once between each of the 3 attempts")
    }

    @Test
    fun `retries on a transient transport failure, then returns success`() {
        val clock = FakeClock()
        var attempt = 0
        val result = pollWhileIdeBusy(
            deadlineMs = 10_000,
            now = clock::now,
            sleep = clock::sleep,
            isBusy = { false },
            transientFailure = { it is IllegalStateException },
            attempt = {
                attempt++
                if (attempt < 3) throw IllegalStateException("MCP request failed: ... exit code is -1 != 0")
                "recovered"
            },
        )
        assertEquals("recovered", result)
        assertEquals(3, attempt)
        assertEquals(2, clock.sleeps.size)
    }

    @Test
    fun `rethrows a non-transient exception immediately without retrying`() {
        val clock = FakeClock()
        var attempt = 0
        val ex = assertThrows(IllegalArgumentException::class.java) {
            pollWhileIdeBusy(
                deadlineMs = 10_000,
                now = clock::now,
                sleep = clock::sleep,
                isBusy = { false },
                transientFailure = { false }, // not transient
                attempt = { attempt++; throw IllegalArgumentException("real bug") },
            )
        }
        assertEquals("real bug", ex.message)
        assertEquals(1, attempt, "must not retry a non-transient failure")
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `stops polling and returns the last busy result once the deadline passes`() {
        val clock = FakeClock()
        var attempts = 0
        val result = pollWhileIdeBusy(
            deadlineMs = 5_000, // 3s sleep each round -> deadline crossed on the 2nd check
            now = clock::now,
            sleep = clock::sleep,
            isBusy = { true }, // always busy
            transientFailure = { false },
            attempt = { attempts++; "still-busy" },
        )
        assertEquals("still-busy", result)
        // attempt 1 (t=0, busy, sleep->3000), attempt 2 (t=3000<5000, busy, sleep->6000), then t>=deadline -> return
        assertTrue(attempts >= 2, "polled at least twice before the deadline")
    }

    @Test
    fun `rethrows the transient exception if the deadline is already reached`() {
        val clock = FakeClock(nowMs = 10_000) // already past the deadline
        assertThrows(IllegalStateException::class.java) {
            pollWhileIdeBusy(
                deadlineMs = 5_000,
                now = clock::now,
                sleep = clock::sleep,
                isBusy = { false },
                transientFailure = { true },
                attempt = { throw IllegalStateException("MCP request failed: ... exit code is -1 != 0") },
            )
        }
    }

    @Test
    fun `isTransientMcpRequestFailure matches a killed-curl timeout but not a script error`() {
        assertTrue(isTransientMcpRequestFailure(
            IllegalStateException("Process MCP request failed: <no body> exit code is -1 != 0")))
        // A script-level error comes back as an isError result (not an exception), and even if surfaced
        // as a message it has a real exit code (1), not the killed-process -1 — must NOT be retried.
        assertFalse(isTransientMcpRequestFailure(
            IllegalStateException("Process MCP request failed: compile error exit code is 1 != 0")))
        assertFalse(isTransientMcpRequestFailure(RuntimeException("some unrelated failure")))
        assertFalse(isTransientMcpRequestFailure(IllegalStateException(null as String?)))
    }
}

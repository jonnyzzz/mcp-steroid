/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ParentDeathWatchdogTest {
    @Test
    fun `fires once after the configured number of consecutive dead readings`() = runTest {
        val fired = AtomicInteger()
        ParentDeathWatchdog(
            parentAlive = { false },
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
            confirmations = 2,
        ).launchIn(this)

        advanceTimeBy(6.seconds) // one dead reading — below the confirmation bar
        assertEquals(0, fired.get(), "one dead reading must not fire yet")
        advanceTimeBy(5.seconds) // second consecutive dead reading
        assertEquals(1, fired.get(), "two consecutive dead readings must fire")
        advanceTimeBy(60.seconds) // watchdog stops after firing
        assertEquals(1, fired.get(), "the watchdog must fire exactly once")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `never fires while the parent stays alive`() = runTest {
        val fired = AtomicInteger()
        ParentDeathWatchdog(
            parentAlive = { true },
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
        ).launchIn(this)

        advanceTimeBy(10_000.seconds)
        assertEquals(0, fired.get())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a transient dead reading resets on the next alive reading`() = runTest {
        val fired = AtomicInteger()
        var alive = false
        ParentDeathWatchdog(
            parentAlive = { alive },
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
            confirmations = 2,
        ).launchIn(this)

        advanceTimeBy(6.seconds) // one dead reading
        alive = true // platform hiccup over — parent is alive
        advanceTimeBy(100.seconds)
        assertEquals(0, fired.get(), "a single transient dead reading must not accumulate")

        alive = false // now the parent really dies
        advanceTimeBy(11.seconds)
        assertEquals(1, fired.get())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `disabled when the parent process is unknown`() = runTest {
        val fired = AtomicInteger()
        ParentDeathWatchdog(
            parentAlive = null,
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
        ).launchIn(this)

        advanceTimeBy(10_000.seconds)
        assertEquals(0, fired.get(), "no parent handle → watchdog must stay silent, never false-positive")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `currentParentLiveness sees this test JVM's real parent as alive`() {
        // The gradle test worker always has a live parent (the daemon); exercises the real
        // ProcessHandle path that production wires in.
        val probe = currentParentLiveness()
        assertNotNull(probe, "a gradle worker JVM must have a resolvable parent")
        assertTrue(probe(), "the gradle daemon parent must be alive while the test runs")
    }
}

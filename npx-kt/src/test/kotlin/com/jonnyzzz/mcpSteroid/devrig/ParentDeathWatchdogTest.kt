/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            ancestorsAlive = listOf({ false }),
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
    fun `never fires while every watched ancestor stays alive`() = runTest {
        val fired = AtomicInteger()
        ParentDeathWatchdog(
            ancestorsAlive = listOf({ true }, { true }),
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
        ).launchIn(this)

        advanceTimeBy(10_000.seconds)
        assertEquals(0, fired.get())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a dead grandparent fires even while the wrapper parent stays alive`() = runTest {
        // The Windows launcher shape (#132): agent -> cmd.exe wrapper -> this JVM. taskkill /F on
        // the agent leaves cmd.exe alive waiting on us — the grandparent probe must still fire.
        val fired = AtomicInteger()
        var agentAlive = true
        ParentDeathWatchdog(
            ancestorsAlive = listOf({ true }, { agentAlive }),
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
            confirmations = 2,
        ).launchIn(this)

        advanceTimeBy(100.seconds)
        assertEquals(0, fired.get())
        agentAlive = false
        advanceTimeBy(11.seconds)
        assertEquals(1, fired.get(), "a dead agent behind a live cmd.exe wrapper must fire")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a transient dead reading resets on the next alive reading`() = runTest {
        val fired = AtomicInteger()
        var alive = false
        ParentDeathWatchdog(
            ancestorsAlive = listOf({ alive }),
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
    fun `disabled when the ancestry is unknown`() = runTest {
        val fired = AtomicInteger()
        ParentDeathWatchdog(
            ancestorsAlive = emptyList(),
            onParentDeath = { fired.incrementAndGet() },
            pollInterval = 5.seconds,
        ).launchIn(this)

        advanceTimeBy(10_000.seconds)
        assertEquals(0, fired.get(), "no ancestry → watchdog must stay silent, never false-positive")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `watchedAncestorLiveness sees this test JVM's real ancestors as alive`() {
        // The gradle test worker always has a live parent (the daemon); exercises the real
        // ProcessHandle path that production wires in.
        val probes = watchedAncestorLiveness()
        assertTrue(probes.isNotEmpty(), "a gradle worker JVM must have a resolvable parent")
        probes.forEach { probe -> assertTrue(probe(), "every watched ancestor must be alive while the test runs") }
    }

    @Test
    fun `isCmdExe matches the Windows wrapper shape only`() {
        assertTrue(isCmdExe("""C:\Windows\System32\cmd.exe"""))
        assertTrue(isCmdExe("CMD.EXE"))
        assertFalse(isCmdExe("/bin/zsh"))
        assertFalse(isCmdExe("""C:\Program Files\PowerShell\7\pwsh.exe"""))
        assertFalse(isCmdExe("""C:\tools\notcmd.exe"""))
        assertFalse(isCmdExe(""))
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The EDT never reads the devrig launcher from disk: every presentation path reads
 * [DevrigStateCache.current], and the cache is fed by one background collector behind a conflating
 * update-signal flow. That machinery is plain logic — no IDE, no filesystem — so its contract is pinned
 * here: the safe default, the seed refresh, change detection, request conflation, and survival of a
 * failing compute.
 */
class DevrigConnectionStateCacheTest {

    /** What a push handed to the UI side; a tiny holder so assertions read as prose. */
    private data class Push(val state: DevrigConnectionState, val changed: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pushes = Channel<Push>(Channel.UNLIMITED)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun notInstalled() = DevrigConnectionState(
        devrigInstalled = false, installedVersion = null, latestBaseVersion = null,
    )

    private fun installed(version: String = "1.2.3") = DevrigConnectionState(
        devrigInstalled = true, installedVersion = version, latestBaseVersion = null,
    )

    private fun cache(debounceMs: Long = 10, compute: () -> DevrigConnectionState) =
        DevrigStateCache(scope, debounceMs, compute) { state, changed -> pushes.send(Push(state, changed)) }

    private suspend fun nextPush(): Push = withTimeout(30_000) { pushes.receive() }

    @Test
    fun `the cache starts from a safe default that never nags`() {
        // The default itself: installed and current, so nothing offers, nags, or claims staleness.
        assertEquals(OnboardingDecision.DEVRIG_READY, DevrigStateCache.SAFE_DEFAULT.decision)
        assertFalse(
            "an unknown state must not hold status-bar space",
            shouldShowDevrigWidget(DevrigStateCache.SAFE_DEFAULT),
        )

        // And it is what readers see before the first refresh lands: a debounce the test never outlives
        // keeps the collector from computing, exactly like an EDT read racing service construction.
        val cache = cache(debounceMs = 600_000) { notInstalled() }
        assertEquals(DevrigStateCache.SAFE_DEFAULT, cache.current)
    }

    @Test
    fun `the seed refresh lands the computed state in the cache and reports the change`(): Unit = runBlocking {
        val cache = cache { notInstalled() }

        val seed = nextPush()
        assertEquals(notInstalled(), seed.state)
        assertTrue("SAFE_DEFAULT -> not installed is a change the UI must hear about", seed.changed)
        assertEquals(notInstalled(), cache.current)
    }

    @Test
    fun `an unchanged recompute reports no change so no surface rebuilds for nothing`(): Unit = runBlocking {
        val cache = cache { installed() }

        assertTrue("the seed replaces the default, which is a change", nextPush().changed)

        cache.requestRefresh()
        val second = nextPush()
        assertEquals(installed(), second.state)
        assertFalse(
            "same facts twice — the settings page must not be told to rebuild",
            second.changed,
        )
    }

    @Test
    fun `a burst of requests conflates into a bounded number of refreshes`(): Unit = runBlocking {
        val cache = cache(debounceMs = 200) { installed() }
        nextPush()   // the seed

        // Every focus regain is one of these. 50 in a row must not mean 50 file reads: the debounce is
        // orders of magnitude longer than the emit loop, so the burst conflates while the collector waits.
        repeat(50) { cache.requestRefresh() }
        nextPush()   // the one coalesced refresh (a second may sneak in if one was already in flight)

        delay(600)   // > debounce: any straggler would have landed by now
        var extra = 0
        while (pushes.tryReceive().isSuccess) extra++
        assertTrue(
            "50 requests must collapse into at most 2 refreshes; saw ${2 + extra} pushes in total",
            extra <= 1,
        )
        assertEquals(installed(), cache.current)
    }

    @Test
    fun `a failing compute is survived and the next request still refreshes`(): Unit = runBlocking {
        val failFirst = AtomicBoolean(true)
        val failed = CompletableDeferred<Unit>()
        val cache = cache {
            if (failFirst.getAndSet(false)) {
                failed.complete(Unit)
                throw IllegalStateException("simulated unreadable launcher")
            }
            installed()
        }

        withTimeout(30_000) { failed.await() }   // the seed compute threw; the collector must live on
        assertEquals("a failed refresh must leave the last known state", DevrigStateCache.SAFE_DEFAULT, cache.current)

        cache.requestRefresh()
        val push = nextPush()
        assertEquals(installed(), push.state)
        assertTrue(push.changed)
    }
}

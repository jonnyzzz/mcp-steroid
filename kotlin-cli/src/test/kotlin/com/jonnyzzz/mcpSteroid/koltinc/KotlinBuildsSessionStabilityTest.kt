/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stability / stress coverage for [KotlinBuildsSession]: memory flatness under
 * sequential compiles, close()+recompile churn, timeout and error storms, the
 * close-vs-compile race, and a large single input. The structural probes
 * (env identity, `ourProjectCount`, jar-handler count) live in
 * [KotlinBuildsSessionTestProbes.kt] and are pinned to BTA 2.4.x internals —
 * same lifetime contract as [CompilerEnvironmentPin].
 */
class KotlinBuildsSessionStabilityTest {
    @JvmField
    @Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun newSession() = KotlinBuildsSession(KotlinBuildsSession.implJarsFrom(
        Path.of(System.getProperty("mcp.steroid.bta.impl.dir")
            ?: error("Gradle sets mcp.steroid.bta.impl.dir (see kotlin-cli/build.gradle.kts)"))
    ))

    private suspend fun KotlinBuildsSession.compileSnippet(
        source: Path,
        out: Path,
        renderer: CompilerMessageRenderer? = null,
        timeout: Duration = 120.seconds,
    ): CompilationResult = compileKotlin(
        sources = listOf(source),
        destinationDir = out,
        compilationTimeout = timeout,
        compilerMessageRenderer = renderer,
    ) { set(JvmCompilerArguments.CLASSPATH, listOf(defaultStdlibJar)) }

    private class RecordingRenderer : CompilerMessageRenderer {
        private val messages =
            ConcurrentLinkedQueue<Pair<CompilerMessageRenderer.Severity, String>>()

        override fun render(
            severity: CompilerMessageRenderer.Severity,
            message: String,
            location: CompilerMessageRenderer.SourceLocation?,
        ): String {
            messages.add(severity to message)
            return message
        }

        fun errorMessages(): List<String> = messages
            .filter { it.first == CompilerMessageRenderer.Severity.ERROR }.map { it.second }
    }

    @Test
    fun pinnedCachesStayFlatAcrossSixtySequentialCompiles() {
        val n = 60
        val srcRoot = tempFolder.newFolder("mem-src").toPath()
        val outRoot = tempFolder.newFolder("mem-out").toPath()
        fun sourceFile(i: Int): Path {
            // DISTINCT source per compile: same-source runs could mask per-input caching.
            val src = (srcRoot / "iter$i").createDirectories() / "Source$i.kt"
            src.writeText("fun compute$i(): Int = $i + (1..$i).sum()\n" +
                "fun main() { println(compute$i()) }\n")
            return src
        }

        newSession().use { session ->
            runBlocking {
                suspend fun compileOnce(i: Int) = assertEquals(
                    CompilationResult.COMPILATION_SUCCESS,
                    session.compileSnippet(sourceFile(i), (outRoot / "out$i").createDirectories() / "out.jar"))

                repeat(10) { compileOnce(it) }                      // warmup: lazy singletons settle
                val envAtWarmup = applicationEnvironmentOf(session)
                assertNotNull(envAtWarmup)
                val handlersAtWarmup = jarHandlerCountOf(session)
                assertTrue("probe must see the live jar-handler map", handlersAtWarmup > 0)
                val heapAtWarmup = usedHeapAfterGc()

                for (i in 10 until n) {
                    compileOnce(i)
                    // Structural, per-iteration, GC-independent:
                    assertSame("ONE application environment must serve the whole session",
                        envAtWarmup, applicationEnvironmentOf(session))
                    assertEquals("env ref-count at rest must be exactly the pin's 1 " +
                        "(growth = a leaked per-compile ref)", 1, environmentRefCountOf(session))
                }
                assertEquals("jar-handler cache must not grow under a constant classpath",
                    handlersAtWarmup, jarHandlerCountOf(session))

                // Secondary, generous heap bound. Expected delta after forced GC is <30 MB
                // (live set is flat); 200 MB only trips on a real >~4 MB/compile leak. A
                // failure here is a real leak signal first — investigate, never widen.
                val heapGrowth = usedHeapAfterGc() - heapAtWarmup
                println("heap growth over ${n - 10} compiles after GC: ${heapGrowth / 1024 / 1024} MB")
                assertTrue("used heap grew by ${heapGrowth / 1024 / 1024} MB over ${n - 10} compiles — " +
                    "investigate a per-compile leak (bound is deliberately generous)",
                    heapGrowth < 200L * 1024 * 1024)
            }
        }
    }

    @Test
    fun sessionChurnOnOneInstanceLeavesNothingBehind() {
        // The production-relevant churn: CodeEvalManager holds ONE session per project
        // for the project's life, and the documented contract is "fine to compile again
        // after close()". Repeated pin acquire->dispose in the SAME impl classloader —
        // env statics must return to zero after every close, each reopen builds a FRESH env.
        val src = tempFolder.newFolder("churn-src").toPath() / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outRoot = tempFolder.newFolder("churn-out").toPath()

        val session = newSession()
        try {
            var previousEnv: Any? = null
            repeat(15) { cycle ->
                runBlocking {
                    assertEquals(CompilationResult.COMPILATION_SUCCESS,
                        session.compileSnippet(src, (outRoot / "c$cycle").createDirectories() / "out.jar"))
                }
                val env = applicationEnvironmentOf(session)
                assertNotNull("cycle $cycle must pin an environment", env)
                previousEnv?.let {
                    assertNotSame("cycle $cycle must not resurrect the disposed environment", it, env)
                }
                previousEnv = env

                session.close()
                assertNull("cycle $cycle: close() must dispose the environment",
                    applicationEnvironmentOf(session))
                assertEquals("cycle $cycle: no ref-count may survive close()",
                    0, environmentRefCountOf(session))
                assertEquals(0, session.activeOperations)
            }
            // The instance is still fully usable after the churn.
            runBlocking {
                assertEquals(CompilationResult.COMPILATION_SUCCESS,
                    session.compileSnippet(src, (outRoot / "final").createDirectories() / "out.jar"))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun timeoutStormDoesNotPoisonTheSession() {
        // The cancellation path is production's riskiest: CodeEvalManager maps TCE to
        // "stopped on timeout" and then keeps using the same session forever. Repeated
        // jvmOperation.cancel() + OCE translation must not poison the lease bookkeeping,
        // the pinned env, or the in-process executor.
        val src = tempFolder.newFolder("storm-src").toPath() / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outRoot = tempFolder.newFolder("storm-out").toPath()

        newSession().use { session ->
            runBlocking {
                // Pin + identity anchor via one normal compile.
                assertEquals(CompilationResult.COMPILATION_SUCCESS,
                    session.compileSnippet(src, (outRoot / "pre").createDirectories() / "out.jar"))
                val env = applicationEnvironmentOf(session)
                assertNotNull(env)

                repeat(10) { i ->
                    try {
                        session.compileSnippet(src,
                            (outRoot / "t$i").createDirectories() / "out.jar",
                            timeout = 1.milliseconds)
                        fail("iteration $i: expected TimeoutCancellationException")
                    } catch (expected: TimeoutCancellationException) {
                        // expected — BTA's OperationCancelledException must stay translated
                    }
                    // The finally-path lease release under cancellation is OUR machinery:
                    assertEquals("iteration $i leaked an operation lease", 0, session.activeOperations)
                    assertSame("iteration $i: cancelled compile must not tear down the pinned env",
                        env, applicationEnvironmentOf(session))
                    assertEquals(1, environmentRefCountOf(session))
                }

                // The same session still compiles normally after the storm.
                val out = (outRoot / "post").createDirectories() / "out.jar"
                assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileSnippet(src, out))
                assertTrue(out.exists())
                assertSame(env, applicationEnvironmentOf(session))
            }
        }
    }

    @Test
    fun errorStormKeepsSessionUsableAndRendererIsolated() {
        // Compiler-error results (BTA returns COMPILATION_ERROR, doesn't throw) must not
        // degrade the session, and diagnostics must never bleed across compiles — each
        // fresh renderer sees exactly its own compile's messages (renderer/state leakage
        // would corrupt the agent-visible error blocks CodeEvalManager builds).
        val srcRoot = tempFolder.newFolder("err-src").toPath()
        val outRoot = tempFolder.newFolder("err-out").toPath()
        fun badSource(i: Int): Path {
            val src = (srcRoot / "bad$i").createDirectories() / "Bad$i.kt"
            // Unresolved reference => the marker name appears verbatim in the K2 diagnostic.
            src.writeText("fun main() { unresolvedSymbol$i() }\n")
            return src
        }
        fun goodSource(i: Int): Path {
            val src = (srcRoot / "good$i").createDirectories() / "Good$i.kt"
            src.writeText("fun main() { println(${i * 7}) }\n")
            return src
        }

        newSession().use { session ->
            runBlocking {
                assertEquals(CompilationResult.COMPILATION_SUCCESS,
                    session.compileSnippet(goodSource(999), (outRoot / "pre").createDirectories() / "out.jar"))
                val env = applicationEnvironmentOf(session)

                repeat(12) { i ->
                    val failing = RecordingRenderer()
                    assertEquals("iteration $i must fail to compile", CompilationResult.COMPILATION_ERROR,
                        session.compileSnippet(badSource(i),
                            (outRoot / "bad$i").createDirectories() / "out.jar", renderer = failing))
                    val errors = failing.errorMessages()
                    assertTrue("iteration $i must render its own error",
                        errors.any { it.contains("unresolvedSymbol$i") })
                    if (i > 0) assertTrue("iteration $i must not replay iteration ${i - 1}'s diagnostics",
                        errors.none { it.contains("unresolvedSymbol${i - 1}") })

                    val succeeding = RecordingRenderer()
                    assertEquals("iteration $i: session must stay usable after a failure",
                        CompilationResult.COMPILATION_SUCCESS,
                        session.compileSnippet(goodSource(i),
                            (outRoot / "good$i").createDirectories() / "out.jar", renderer = succeeding))
                    assertTrue("a successful compile must render no stale errors",
                        succeeding.errorMessages().isEmpty())

                    assertSame("failed compiles must not rebuild the pinned env",
                        env, applicationEnvironmentOf(session))
                    assertEquals(1, environmentRefCountOf(session))
                }
            }
        }
    }

    @Test
    fun closeRacingCompileNeverLeaksThePin() {
        // Fuzz-by-schedule with invariant assertions: the race outcome (close-before-
        // acquire vs close-mid-compile vs close-after-release) varies per run, but every
        // interleaving must converge on the same post-conditions — compile success +
        // zero residue after the final close. This exercises OUR synchronized lease/pin
        // bookkeeping; it deliberately never overlaps two compiles (production
        // serializes them via CodeEvalManager.compilationMutex, and BTA does not
        // document BuildSession thread-safety).
        val src = tempFolder.newFolder("race-src").toPath() / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outRoot = tempFolder.newFolder("race-out").toPath()

        val session = newSession()
        try {
            runBlocking {
                repeat(8) { i ->
                    val compile = async(Dispatchers.IO) {
                        session.compileSnippet(src, (outRoot / "r$i").createDirectories() / "out.jar")
                    }
                    if (i % 2 == 0) {
                        // Even iterations: deterministic mid-compile close (the existing
                        // closeDuringCompileDefersUntilOperationCompletes pattern).
                        while (session.activeOperations == 0 && !compile.isCompleted) yield()
                    }
                    // Odd iterations: close IMMEDIATELY, racing the lease acquire itself.
                    session.close()

                    // Every interleaving is legal and must converge on the same outcome:
                    // the compile succeeds (on the old lease or a fresh one)...
                    assertEquals("iteration $i", CompilationResult.COMPILATION_SUCCESS, compile.await())
                    // ...and after a final close with no in-flight ops, NOTHING survives —
                    // regardless of whether the compile re-opened a lease after the race close.
                    session.close()
                    assertNull("iteration $i leaked a pinned environment",
                        applicationEnvironmentOf(session))
                    assertEquals("iteration $i leaked an env ref-count",
                        0, environmentRefCountOf(session))
                    assertEquals(0, session.activeOperations)
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun compilesLargeGeneratedSourceWithinDefaultTimeout() {
        // Guards against a pathological (superlinear) regression on large single inputs —
        // the shape an agent producing a huge snippet hits.
        val src = tempFolder.newFolder("big-src").toPath() / "Big.kt"
        src.writeText(buildString {
            appendLine("package big")
            for (i in 0 until 2000) appendLine("fun f$i(x: Int): Int = x + $i")
            appendLine("fun main() { println(f0(f1999(1)) + (0 until 2000).sum()) }")
        })
        val out = tempFolder.newFolder("big-out").toPath() / "out.jar"

        newSession().use { session ->
            runBlocking {
                // Default 120s timeout on purpose: the guard is "completes at all, not
                // hangs/superlinear" — a tighter bound would trade regression sensitivity
                // for CI flake on loaded agents.
                assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileSnippet(src, out))
            }
            assertTrue(out.exists())
        }
    }
}

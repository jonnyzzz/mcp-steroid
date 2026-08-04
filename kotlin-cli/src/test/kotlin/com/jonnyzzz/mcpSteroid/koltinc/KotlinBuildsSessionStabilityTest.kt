/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stability / stress coverage for [KotlinBuildsSession]: memory flatness under
 * sequential compiles, repeated fresh-session lifecycles, error storms,
 * and a large single input. The structural probes verify BTA 2.4.20 RC's upstream
 * KT-87743 environment-pin lifetime.
 */
class KotlinBuildsSessionStabilityTest {
    @JvmField
    @Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun newSession() = KotlinBuildsSession(KotlinBuildsSession.implJarsFrom(
        Path.of(System.getProperty("mcp.steroid.bta.impl.dir")
            ?: error("Gradle sets mcp.steroid.bta.impl.dir (see kotlin-cli/build.gradle.kts)"))
    ))

    private fun withSession(action: (KotlinBuildsSession) -> Unit) {
        newSession().use(action)
    }

    private suspend fun KotlinBuildsSession.compileSnippet(
        source: Path,
        out: Path,
        renderer: CompilerMessageRenderer? = null,
    ): CompilationResult = compileKotlin(
        sources = listOf(source),
        destinationDir = out,
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

        withSession { session ->
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
                    assertEquals("env ref-count at rest must be exactly BTA's pin count of 1 " +
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
    fun repeatedFreshSessionLifecyclesLeaveNothingBehind() {
        // KotlinBuildsSession is single-lifecycle: production owns one for the
        // project lifetime and closes it during disposal. Exercise repeated lifecycles
        // by allocating a fresh wrapper instead of reopening a closed one.
        val src = tempFolder.newFolder("churn-src").toPath() / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outRoot = tempFolder.newFolder("churn-out").toPath()

        var previousEnv: Any? = null
        repeat(15) { cycle ->
            val session = newSession()
            session.use {
                runBlocking {
                    assertEquals(CompilationResult.COMPILATION_SUCCESS,
                        session.compileSnippet(src,
                            (outRoot / "c$cycle").createDirectories() / "out.jar"))
                }
                val env = applicationEnvironmentOf(session)
                assertNotNull("cycle $cycle must pin an environment", env)
                previousEnv?.let {
                    assertNotSame("cycle $cycle must use a fresh environment", it, env)
                }
                previousEnv = env
            }

            assertNull("cycle $cycle: close() must dispose the environment",
                applicationEnvironmentOf(session))
            assertEquals("cycle $cycle: no ref-count may survive close()",
                0, environmentRefCountOf(session))
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

        withSession { session ->
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

                    assertSame("failed compiles must not rebuild BTA's pinned env",
                        env, applicationEnvironmentOf(session))
                    assertEquals(1, environmentRefCountOf(session))
                }
            }
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

        withSession { session ->
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

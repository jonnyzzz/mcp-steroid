/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.net.URLClassLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.buildtools.api.CompilationResult
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


class KotlinBuildsSessionTest {
    @JvmField
    @Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // The BTA impl jar directory comes from Gradle (kotlin-cli/build.gradle.kts sets the
    // system property for the test JVM). Production never reads properties — the plugin
    // resolves its dist folder explicitly; tests are the only property consumers.
    private fun newSession() = KotlinBuildsSession(KotlinBuildsSession.implJarsFrom(
        java.nio.file.Path.of(System.getProperty("mcp.steroid.bta.impl.dir")
            ?: error("Gradle sets mcp.steroid.bta.impl.dir (see kotlin-cli/build.gradle.kts)"))
    ))

    @Test
    fun smokeCompile() {
        val smokeDir = tempFolder.newFolder("smoke").toPath()
        val outputJar = smokeDir.resolve("out.jar")
        val source = smokeDir.resolve("source.kt")
        source.writeText("""
        fun main() { println("Hello") }
        """.trimIndent())

        newSession().use {
            runBlocking {
                it.compileKotlin(
                    sources = listOf(source),
                    destinationDir = outputJar,
                ) {
                    set(JvmCompilerArguments.CLASSPATH, listOf(it.defaultStdlibJar))
                }
            }

            assertTrue(outputJar.exists())
        }
    }

    @Test
    fun compilesWithSpacesInSourceClasspathAndOutputPaths() {
        // Successor of main's KotlincCommandLineBuilderIntegrationTest
        // .testCompilesJarWithClasspathArgFileContainingSpaces (the "JPA Model"
        // IntelliJ plugin dir case). Under BTA, paths flow as List<Path> into
        // the compiler arguments with no quote/parse layer — this pins that,
        // and step 3 keeps the old "load and run the produced jar" verification.
        // The two-stage compile makes the spaced classpath entry load-bearing.
        val root = tempFolder.newFolder("JPA Model").toPath()
        val libSrc = (root / "lib src").createDirectories() / "Lib.kt"
        libSrc.writeText("package lib\n\nfun marker(): String = \"from spaced classpath\"\n")
        val libClasses = (root / "lib classes").createDirectories()
        val script = (root / "script src").createDirectories() / "Main.kt"
        script.writeText("import lib.marker\n\nfun run(): String = marker()\n")
        val outputJar = (root / "out dir").createDirectories() / "spaces out.jar"

        newSession().use { session ->
            runBlocking {
                assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileKotlin(
                    sources = listOf(libSrc),
                    destinationDir = libClasses,
                ) {
                    set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar))
                })

                assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileKotlin(
                    sources = listOf(script),
                    destinationDir = outputJar,
                ) {
                    set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar, libClasses))
                })
            }

            val classpath = arrayOf(outputJar, libClasses, session.defaultStdlibJar)
            URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray(), null).use { loader ->
                assertEquals(
                    "from spaced classpath",
                    loader.loadClass("MainKt").getDeclaredMethod("run").invoke(null),
                )
            }
        }
    }

    @Test
    fun compilesWithClasspathLongerThanWindowsCommandLineLimit() {
        // Historical concern: Windows CreateProcess caps a child's command line at 32767
        // chars, which pre-BTA forced an @argfile. In-process there is no OS command line
        // at all — arguments stay an in-memory Array<String> into K2JVMCompiler.exec. This
        // pins that the BTA List<Path> -> -classpath mapping carries a >40K-char classpath
        // untruncated, per-OS on TC.
        val src = tempFolder.newFolder("src").toPath() / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outputJar = tempFolder.newFolder("out").toPath() / "out.jar"
        val padding = (1..600).map { tempFolder.newFolder("cp-entry-$it-" + "x".repeat(60)).toPath() }

        newSession().use { session ->
            // NB: `padding + session.defaultStdlibJar` would be WRONG here — Path is
            // Iterable<Path> over its segments, so List<Path> + Path resolves to
            // plus(Iterable) and appends "Users", "jonnyzzz", ... as classpath entries.
            val classpath = padding + listOf(session.defaultStdlibJar)
            assertTrue(classpath.sumOf { it.toString().length + 1 } > 40_000)
            runBlocking {
                assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileKotlin(
                    sources = listOf(src),
                    destinationDir = outputJar,
                ) {
                    set(JvmCompilerArguments.CLASSPATH, classpath)
                })
            }
            assertTrue(outputJar.exists())
        }
    }

    @Test
    fun timeoutSurfacesAsTimeoutCancellationException() {
        // The caller-visible timeout contract: CodeEvalManager maps
        // TimeoutCancellationException to the "stopped on timeout" failure. The OCE
        // that BTA throws after the cooperative cancel must NOT supersede it
        // (quorum finding: translate OCE inside the async child).
        val srcDir = tempFolder.newFolder("timeout-src").toPath()
        val src = srcDir / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val outputJar = tempFolder.newFolder("timeout-out").toPath() / "out.jar"

        newSession().use { session ->
            try {
                runBlocking {
                    session.compileKotlin(
                        sources = listOf(src),
                        destinationDir = outputJar,
                        compilationTimeout = 1.milliseconds,
                    ) {
                        set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar))
                    }
                }
                fail("Expected TimeoutCancellationException for a 1ms compilation timeout")
            } catch (expected: TimeoutCancellationException) {
                // expected
            }
        }
    }

    @Test
    fun closeDuringCompileDefersUntilOperationCompletes() {
        // close() must not tear down the session underneath an in-flight compile:
        // the actual BuildSession.close() is deferred to the last lease release,
        // and compiles started after close() get a fresh session.
        val srcDir = tempFolder.newFolder("close-src").toPath()
        val src = srcDir / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")
        val out1 = tempFolder.newFolder("close-out-1").toPath() / "out.jar"
        val out2 = tempFolder.newFolder("close-out-2").toPath() / "out.jar"

        val session = newSession()
        runBlocking {
            val compile = async(Dispatchers.IO) {
                session.compileKotlin(
                    sources = listOf(src),
                    destinationDir = out1,
                ) {
                    set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar))
                }
            }
            while (session.activeOperations == 0 && !compile.isCompleted) {
                yield()
            }
            session.close()
            assertEquals(CompilationResult.COMPILATION_SUCCESS, compile.await())
            // The deferred close (last lease release) must close BTA's BuildSession and
            // its upstream environment pin, otherwise this path leaks the cached environment.
            assertNull("deferred close must dispose the pinned application environment",
                applicationEnvironmentOf(session))
        }

        // Compiling again after close() starts a fresh session.
        runBlocking {
            assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileKotlin(
                sources = listOf(src),
                destinationDir = out2,
            ) {
                set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar))
            })
        }
        session.close()
    }

    @Test
    fun upstreamPinnedEnvironmentIsReusedAcrossCompilesAndDisposedOnClose() {
        // Leak/lifecycle contract of BTA's KT-87743 BuildSession-owned pin:
        // (1) one application environment instance serves all compiles of a session
        //     (this is what keeps the jar caches warm),
        // (2) close() releases it — the compiler disposes the environment, so nothing
        //     is retained past the session (no leak),
        // (3) a session used again after close() gets a fresh environment.
        val srcDir = tempFolder.newFolder("pin-src").toPath()
        val src = srcDir / "source.kt"
        src.writeText("fun main() { println(\"Hello\") }\n")

        val session = newSession()
        fun compileOnce(tag: String) = runBlocking {
            assertEquals(CompilationResult.COMPILATION_SUCCESS, session.compileKotlin(
                sources = listOf(src),
                destinationDir = tempFolder.newFolder("pin-out-$tag").toPath() / "out.jar",
            ) {
                set(JvmCompilerArguments.CLASSPATH, listOf(session.defaultStdlibJar))
            })
        }

        compileOnce("a")
        val envAfterFirst = applicationEnvironmentOf(session)
        assertNotNull("pinned application environment must exist after a compile", envAfterFirst)
        compileOnce("b")
        val envAfterSecond = applicationEnvironmentOf(session)
        assertSame("the SAME application environment must serve all compiles of a session " +
            "(otherwise the jar caches are rebuilt per compile)", envAfterFirst, envAfterSecond)

        session.close()
        assertNull("close() must release the pin so the compiler disposes the application " +
            "environment — anything else leaks the jar caches", applicationEnvironmentOf(session))

        compileOnce("c")
        val envAfterReopen = applicationEnvironmentOf(session)
        assertNotNull(envAfterReopen)
        assertNotSame("a session reused after close() must build a fresh environment",
            envAfterFirst, envAfterReopen)
        session.close()
        assertNull(applicationEnvironmentOf(session))
    }

    @Test
    fun defaultJvmTargetTracksTheRunningJvm() {
        // Guards the fail-fast in getDefaultJvmTarget: the day the test JVM's
        // specification version outpaces the bundled BTA's JvmTarget enum, this
        // fails here instead of silently mis-targeting snippet compilation.
        assertEquals(
            System.getProperty("java.specification.version"),
            KotlinBuildsSession.DEFAULT_JVM_TARGET.stringValue,
        )
    }
}

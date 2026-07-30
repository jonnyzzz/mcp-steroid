/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.OperationCancelledException
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm

/**
 * Represents a build session for compiling Kotlin code with various compilation execution policies:
 * - in Kotlin daemon (default) - compilation runs in a separate Kotlin daemon process which could be shared
 * between multiple processes
 * - in process - compilation runs inside the current process
 *
 * This class keeps Kotlin caches between different compilations. To drop them - call `close()` method.
 * It is fine to call again compilation after calling `close()`.
 *
 * @param implClasspath The Kotlin Build Tools implementation jars (the plugin's `kotlinc/` folder,
 * or the `:kotlin-cli` `bta-impl-jars` directory in tests). The jars must be plain files on disk —
 * BTA loads them into an isolated URLClassLoader, and the Kotlin daemon builds the `-cp` of its own
 * JVM from these very paths.
 * @param kotlinLogger Optional logger used for capturing Kotlin compilation logs.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class KotlinBuildsSession(
    implClasspath: List<Path>,
    val kotlinLogger: KotlinLogger? = null,
) : AutoCloseable {

    val implClasspath: List<Path> = implClasspath.also { jars ->
        require(jars.isNotEmpty()) { "BTA implementation classpath must not be empty" }
        for (jar in jars) {
            require(jar.isRegularFile()) { "BTA implementation classpath entry is not a file: $jar" }
        }
    }

    private val buildToolsApi = KotlinToolchains.loadImplementation(this.implClasspath)
    private val inProcessExecutionStrategy = buildToolsApi.createInProcessExecutionPolicy()
    private val daemonExecutionPolicy = buildToolsApi.daemonExecutionPolicyBuilder().build()
    private var buildSession: KotlinToolchains.BuildSession? = null

    /**
     * Compiles a set of Kotlin source files into the specified destination directory.
     *
     * It is expected that multiple compilations from different threads could be invoked.
     *
     * @param sources A list of paths pointing to the Kotlin source files to be compiled.
     * @param destinationDir The path to the directory where the compiled outputs will be stored. Either could be a directory or jar file.
     * @param executionPolicy Specifies the execution policy for the compilation process. Defaults to [CompilationExecutionPolicy.DAEMON].
     * @param compilationTimeout maximum time compilation is allowed to run. The default is 120 seconds.
     * @param compilerMessageRenderer optional [CompilerMessageRenderer] to collect and transform compiler messages.
     * @param argumentsConf A lambda that allows configuration of additional JVM compiler arguments. '-no-stdlib' and '-no-reflect' arguments are always added.
     * @return A [CompilationResult] encapsulating the result of the compilation process.
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException on reaching [compilationTimeout]; the
     * in-flight compilation is cancelled cooperatively via [org.jetbrains.kotlin.buildtools.api.BuildOperation.cancel].
     * @throws CancellationException when the calling coroutine is cancelled; BTA's own
     * [OperationCancelledException] is translated and never escapes.
     */
    suspend fun compileKotlin(
        sources: List<Path>,
        destinationDir: Path,
        executionPolicy: CompilationExecutionPolicy = CompilationExecutionPolicy.DAEMON,
        compilationTimeout: Duration = 120.seconds,
        compilerMessageRenderer: CompilerMessageRenderer? = null,
        argumentsConf: JvmCompilerArguments.Builder.() -> Unit = {}
    ): CompilationResult {
         val buildSession = synchronized(this) {
             buildSession ?: buildToolsApi.createBuildSession().also {
                 buildSession = it
             }
         }

         val jvmCompilationBuilder = buildSession.kotlinToolchains
             .jvm
             .jvmCompilationOperationBuilder(
                 sources = sources,
                 destinationDirectory = destinationDir,
             )
        compilerMessageRenderer?.let {
            jvmCompilationBuilder[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = it
        }

         with(jvmCompilationBuilder.compilerArguments) {
             argumentsConf()
             set(JvmCompilerArguments.NO_STDLIB, true)
             set(JvmCompilerArguments.NO_REFLECT, true)
             set(JvmCompilerArguments.JVM_TARGET, DEFAULT_JVM_TARGET)
         }

        val jvmOperation = jvmCompilationBuilder.build()
        return try {
            coroutineScope {
                // executeOperation is a plain blocking call. Run it in a child coroutine so the
                // caller's timeout/cancellation can act while the compile is in flight — awaiting
                // it inline would let the compiler run to completion and only then observe the
                // cancellation, making the timeout (and jvmOperation.cancel()) decorative.
                val compilation = async(Dispatchers.IO) {
                    buildSession.executeOperation(
                        operation = jvmOperation,
                        executionPolicy = when (executionPolicy) {
                            CompilationExecutionPolicy.IN_PROCESS -> inProcessExecutionStrategy
                            CompilationExecutionPolicy.DAEMON -> daemonExecutionPolicy
                        },
                        logger = kotlinLogger,
                    )
                }
                try {
                    withTimeout(compilationTimeout) { compilation.await() }
                } catch (e: CancellationException) {
                    // Timeout or caller cancellation: request cooperative cancellation so the
                    // compiler aborts promptly — executeOperation then completes with
                    // OperationCancelledException and coroutineScope can exit instead of
                    // waiting for the full compilation to run to the end.
                    jvmOperation.cancel()
                    throw e
                }
            }
        } catch (e: OperationCancelledException) {
            // BTA reports a cancelled operation with its own RuntimeException subtype, not a
            // CancellationException. The only cancel() caller is the catch above, i.e. an
            // escaping OperationCancelledException always originates from this coroutine's
            // cancellation — translate it back so callers' CancellationException discipline
            // (rethrow, timeout mapping) applies.
            throw CancellationException("Kotlin compilation was cancelled", e)
        }
    }

    /**
     * Closes the current build session managed by this instance.
     *
     * This method ensures that any associated resources from previous compilations are released.
     * The operation is thread-safe.
     */
    override fun close() {
        synchronized(this) {
            if (buildSession != null) {
                buildSession?.close()
                buildSession = null
            }
        }
    }

    /**
     * The `Path` of the Kotlin standard library JAR file from the build tools API implementation jars.
     */
    val defaultStdlibJar: Path
        get() = implClasspath.single { it.name.startsWith("kotlin-stdlib-") }

    enum class CompilationExecutionPolicy {
        IN_PROCESS, DAEMON;
    }

    companion object {
        /**
         * Lists the BTA implementation jars of [dir] — the plugin distribution's `kotlinc/`
         * folder, or the `:kotlin-cli` `bta-impl-jars` build directory in tests. Sorted for a
         * deterministic classloader/daemon classpath. Fails fast when the directory is missing
         * or empty: there is exactly one way to compile, and it needs these jars.
         */
        fun implJarsFrom(dir: Path): List<Path> {
            require(dir.isDirectory()) { "BTA implementation jar directory not found: $dir" }
            val jars = dir.listDirectoryEntries("*.jar").sorted()
            require(jars.isNotEmpty()) { "No BTA implementation jars found in: $dir" }
            return jars
        }

        /**
         * [implJarsFrom] with the directory taken from the `mcp.steroid.bta.impl.dir` system
         * property — the contract Gradle test tasks use (see `:kotlin-cli` / `:prompts`
         * build scripts).
         */
        fun implJarsFromSystemProperty(property: String = "mcp.steroid.bta.impl.dir"): List<Path> {
            val dir = System.getProperty(property)
                ?: error("Missing system property '$property' — BTA implementation jar directory not provided")
            return implJarsFrom(Path.of(dir))
        }

        /**
         * Default `-jvm-target` for snippet compilation, derived from the JVM
         * that owns this process — `java.specification.version` is `"21"`
         * on JDK 21, `"25"` on JDK 25, etc. The Kotlin inline-bytecode
         * compatibility rule requires the script's target to be ≥ the target
         * of any inline function it calls; the IntelliJ Platform 261.* (EAP
         * for 2026.1.x) ships inline functions compiled at target 25, so a
         * fixed target of "21" rejects them with `cannot inline bytecode
         * built with JVM target 25 into bytecode that is being built with
         * JVM target 21`. Deriving from the live JVM keeps the script's
         * target in lock-step with whatever JDK the IDE / the test runner
         * happens to run on.
         *
         * Fails fast when the running JVM's version has no matching [JvmTarget]
         * entry in the bundled BTA — silently downgrading (e.g. to 21) would
         * resurrect the inline-bytecode failure above in a far less
         * diagnosable form. The fix is to bump `mcp.kotlinc.version`.
         */
        val DEFAULT_JVM_TARGET: JvmTarget = getDefaultJvmTarget()

        private fun getDefaultJvmTarget(): JvmTarget {
            val jvmTargetStr = System.getProperty("java.specification.version")
                ?: error("System property 'java.specification.version' is not set on this JVM")
            return JvmTarget.entries.find { it.stringValue == jvmTargetStr }
                ?: error(
                    "The running JVM ('java.specification.version'=$jvmTargetStr) has no matching " +
                        "JvmTarget in the bundled Kotlin Build Tools API. Bump mcp.kotlinc.version " +
                        "or run on a JDK the bundled compiler supports " +
                        "(known targets: ${JvmTarget.entries.joinToString { it.stringValue }})."
                )
        }
    }
}

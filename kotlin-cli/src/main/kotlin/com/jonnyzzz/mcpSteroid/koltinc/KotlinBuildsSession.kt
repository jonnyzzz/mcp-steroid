/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
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
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm

/**
 * A build session compiling Kotlin code in-process via the Kotlin Build Tools API.
 * The session pins the compiler application environment ([CompilerEnvironmentPin]) so
 * jar/classpath caches survive across compilations (measured 367 -> 218 ms warm snippet
 * compiles on an IDE-sized classpath). There is deliberately no daemon flow: BTA 2.4.10
 * clears the daemon-side jar caches around every operation (reported upstream as
 * KT-88183), so the daemon could never be made this fast from our side, and the plugin
 * ships only the in-process jars. REVIEW when updating the kotlinc/BTA logic or bumping
 * mcp.kotlinc.version: if KT-88183 is fixed, the daemon flow becomes viable again.
 *
 * This class keeps Kotlin caches between different compilations. To drop them - call `close()` method.
 * It is fine to call again compilation after calling `close()`.
 *
 * @param implClasspath The Kotlin Build Tools implementation jars (the plugin's `kotlinc/` folder,
 * or the `:kotlin-cli` `bta-impl-jars` directory in tests). The jars must be plain files on disk —
 * BTA loads them into an isolated URLClassLoader, and the compiler's FastJarFileSystem reads
 * these exact on-disk jars.
 * @param kotlinLogger Optional logger used for capturing Kotlin compilation logs.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class KotlinBuildsSession(
    implClasspath: List<Path>,
    private val kotlinLogger: KotlinLogger? = null,
) : AutoCloseable {

    val implClasspath: List<Path> = implClasspath.also { jars ->
        require(jars.isNotEmpty()) { "BTA implementation classpath must not be empty" }
        for (jar in jars) {
            require(jar.isRegularFile()) { "BTA implementation classpath entry is not a file: $jar" }
        }
    }

    // The session owns its implementation classloader (rather than using the
    // loadImplementation(List<Path>) convenience) so CompilerEnvironmentPin can
    // reach the compiler classes living inside it.
    private val implClassLoader = URLClassLoader(
        this.implClasspath.map { it.toUri().toURL() }.toTypedArray(),
        SharedApiClassesClassLoader(),
    )
    private val buildToolsApi = KotlinToolchains.loadImplementation(implClassLoader)
    private val inProcessExecutionStrategy = buildToolsApi.createInProcessExecutionPolicy()

    // Session lifecycle: every compile holds a lease on the current session; close()
    // detaches the session immediately (subsequent compiles start a fresh one) but the
    // actual BuildSession.close() is deferred until the last leased operation releases —
    // closing mid-operation would shut down the in-process executor underneath a running
    // compile. The environment pin shares the lease lifecycle: acquired lazily before
    // the first compile, released after the session closes — the compiler's jar caches
    // live exactly as long as the session (no leak past close).
    private class SessionLease(
        val session: KotlinToolchains.BuildSession,
    ) {
        var environmentPin: CompilerEnvironmentPin? = null
        var activeOperations = 0
        var closeRequested = false
    }

    private var currentLease: SessionLease? = null

    /** Number of in-flight compile operations on the current session (observable for tests). */
    val activeOperations: Int
        get() = synchronized(this) { currentLease?.activeOperations ?: 0 }

    private fun acquireSession(): SessionLease = synchronized(this) {
        val lease = currentLease
            ?: SessionLease(buildToolsApi.createBuildSession()).also { currentLease = it }
        if (lease.environmentPin == null) {
            // Lazy + transactional: on pin failure the lease stays valid (and unpinned
            // usage would still work), but we fail the compile loudly rather than run
            // with silently degraded per-compile cache teardown.
            lease.environmentPin = CompilerEnvironmentPin.acquire(implClassLoader)
        }
        lease.activeOperations++
        lease
    }

    private fun releaseSession(lease: SessionLease) {
        val toClose = synchronized(this) {
            lease.activeOperations--
            if (lease.closeRequested && lease.activeOperations == 0) lease else null
        }
        toClose?.closeNow()
    }

    private fun SessionLease.closeNow() {
        // Session first (its close() clears the jar caches), then the pin — dropping
        // the last ref-count lets the compiler dispose the application environment.
        // The pin MUST be released even when BuildSession.close() throws: the lease is
        // already detached, so this is the only chance.
        try {
            session.close()
        } finally {
            environmentPin?.dispose()
        }
    }

    /**
     * Compiles a set of Kotlin source files into the specified destination directory.
     *
     * Compilations may be invoked from multiple coroutines; each holds a lease on the
     * shared build session. (Note: BTA does not document BuildSession thread-safety —
     * the in-repo consumers serialize compiles anyway: CodeEvalManager via a mutex,
     * the KtBlock tests sequentially per test JVM.)
     *
     * @param sources A list of paths pointing to the Kotlin source files to be compiled.
     * @param destinationDir The path to the directory where the compiled outputs will be stored. Either could be a directory or jar file.
     * @param compilationTimeout maximum time compilation is allowed to run. The default is 120 seconds.
     * @param compilerMessageRenderer optional [CompilerMessageRenderer] to collect and transform compiler messages.
     * @param argumentsConf A lambda that allows configuration of additional JVM compiler arguments.
     * '-no-stdlib', '-no-reflect' and '-jvm-target' ([DEFAULT_JVM_TARGET]) are force-set AFTER
     * this lambda runs — a '-jvm-target' from [argumentsConf] (or applyArgumentStrings) is
     * deliberately overridden, the script target must track the running JVM.
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
        compilationTimeout: Duration = 120.seconds,
        compilerMessageRenderer: CompilerMessageRenderer? = null,
        argumentsConf: JvmCompilerArguments.Builder.() -> Unit = {}
    ): CompilationResult {
        val lease = acquireSession()
        try {
            val jvmCompilationBuilder = lease.session.kotlinToolchains
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
            return coroutineScope {
                // executeOperation is a plain blocking call. Run it in a child coroutine so the
                // caller's timeout/cancellation can act while the compile is in flight — awaiting
                // it inline would let the compiler run to completion and only then observe the
                // cancellation, making the timeout (and jvmOperation.cancel()) decorative.
                val compilation = async(Dispatchers.IO) {
                    try {
                        lease.session.executeOperation(
                            operation = jvmOperation,
                            executionPolicy = inProcessExecutionStrategy,
                            logger = kotlinLogger,
                        )
                    } catch (e: OperationCancelledException) {
                        // BTA reports a cancelled operation with its own RuntimeException
                        // subtype. The only cancel() caller is the catch below, so an OCE
                        // always means "this coroutine's compilation was cancelled". The
                        // translation must happen INSIDE the child: a child failing with
                        // CancellationException counts as cancellation and lets
                        // coroutineScope rethrow the caller's original exception (e.g.
                        // TimeoutCancellationException), whereas a non-CE child failure
                        // would supersede it and break the timeout contract.
                        throw CancellationException("Kotlin compilation was cancelled", e)
                    }
                }
                try {
                    withTimeout(compilationTimeout) { compilation.await() }
                } catch (e: CancellationException) {
                    // Timeout or caller cancellation: request cooperative cancellation so the
                    // compiler aborts promptly — executeOperation then completes with
                    // OperationCancelledException (translated above) and coroutineScope can
                    // exit instead of waiting for the full compilation to run to the end.
                    jvmOperation.cancel()
                    throw e
                }
            }
        } finally {
            releaseSession(lease)
        }
    }

    /**
     * Closes the current build session managed by this instance.
     *
     * Thread-safe. In-flight compilations keep the session alive until they finish
     * (the close is deferred to the last lease release); compilations started after
     * `close()` returns get a fresh session — it is fine to compile again after closing.
     */
    override fun close() {
        val toClose = synchronized(this) {
            val lease = currentLease ?: return
            currentLease = null
            if (lease.activeOperations == 0) {
                lease
            } else {
                lease.closeRequested = true
                null
            }
        }
        toClose?.closeNow()
    }

    companion object {
        /**
         * Lists the BTA implementation jars of [dir] — the plugin distribution's `kotlinc/`
         * folder, or the `:kotlin-cli` `bta-impl-jars` build directory in tests. Sorted for a
         * deterministic classloader classpath. Fails fast when the directory is missing
         * or empty: there is exactly one way to compile, and it needs these jars.
         */
        fun implJarsFrom(dir: Path): List<Path> {
            require(dir.isDirectory()) { "BTA implementation jar directory not found: $dir" }
            val jars = dir.listDirectoryEntries("*.jar").sorted()
            require(jars.isNotEmpty()) { "No BTA implementation jars found in: $dir" }
            return jars
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

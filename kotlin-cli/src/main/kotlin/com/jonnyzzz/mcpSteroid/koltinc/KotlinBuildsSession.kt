/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm

/**
 * A build session compiling Kotlin code in-process via the Kotlin Build Tools API.
 * Since 2.4.20 RC, BTA itself pins the compiler application environment to the
 * [KotlinToolchains.BuildSession] lifetime (KT-87743), so jar/classpath caches survive
 * across compilations without a local reflective workaround. There is deliberately no
 * daemon flow: BTA still clears the daemon-side jar caches around every operation
 * (KT-88183), so the daemon cannot match the in-process path, and the plugin ships only
 * the in-process jars. Review KT-88183 whenever updating the BTA dependencies: if it is
 * fixed, the daemon flow becomes viable again.
 *
 * Each instance owns one [KotlinToolchains.BuildSession], and all compilations on that
 * instance reuse its compiler and JarFS caches. `CodeEvalManager` keeps its instance for the
 * project lifetime and closes it through the IntelliJ Disposer at project shutdown. Creating
 * and closing a session for every compilation was measured against
 * 18 real IDEA/PyCharm/CLion prompt compilations with a clean KtBlock cache: it took 24.587 s,
 * while repeated shared-session runs took 15.023–17.259 s. Closing the session releases the
 * application-environment/JarFS pin added by KT-87743, so the following compilation must rebuild
 * those classpath caches. Keep the session field-owned until the upstream cache lifetime no longer
 * depends on the build-session lifetime. Callers must serialize compilations and call [close] only
 * after the last one completes. A closed instance must not be used again.
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

    private val buildToolsApi = KotlinToolchains.loadImplementation(this.implClasspath)
    val compilerVersion: String = buildToolsApi.getCompilerVersion()
    private val inProcessExecutionStrategy = buildToolsApi.createInProcessExecutionPolicy()
    // Keep for the project lifetime: closing it drops BTA's JarFS/classpath caches.
    private val buildSession = buildToolsApi.createBuildSession()

    /**
     * Compiles a set of Kotlin source files into the specified destination directory.
     *
     * BTA does not document [KotlinToolchains.BuildSession] thread-safety. Callers must
     * serialize calls: CodeEvalManager uses its compilation mutex, and KtBlock tests compile
     * sequentially within each test JVM.
     *
     * @param sources A list of paths pointing to the Kotlin source files to be compiled.
     * @param destinationDir The path to the directory where the compiled outputs will be stored. Either could be a directory or jar file.
     * @param compilerMessageRenderer optional [CompilerMessageRenderer] to collect and transform compiler messages.
     * @param argumentsConf A lambda that allows configuration of additional JVM compiler arguments.
     * '-no-stdlib', '-no-reflect' and '-jvm-target' ([DEFAULT_JVM_TARGET]) are force-set AFTER
     * this lambda runs — a '-jvm-target' from [argumentsConf] (or applyArgumentStrings) is
     * deliberately overridden, the script target must track the running JVM.
     * @return A [CompilationResult] encapsulating the result of the compilation process.
     *
     * The compiler operation is intentionally not timed out or cancelled. BTA execution is a
     * blocking call, so it runs on [Dispatchers.IO] and is allowed to finish normally. Cancelling
     * the caller may discard the result after the blocking call returns, but does not interrupt
     * compiler state halfway through an operation.
     */
    suspend fun compileKotlin(
        sources: List<Path>,
        destinationDir: Path,
        compilerMessageRenderer: CompilerMessageRenderer? = null,
        argumentsConf: JvmCompilerArguments.Builder.() -> Unit = {}
    ): CompilationResult {
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
        return withContext(Dispatchers.IO) {
            buildSession.executeOperation(
                operation = jvmOperation,
                executionPolicy = inProcessExecutionStrategy,
                logger = kotlinLogger,
            )
        }
    }

    /**
     * Closes this instance's build session and releases its compiler and JarFS caches.
     *
     * Call this only after the final compilation completes. Closing concurrently with
     * compilation and compiling again after close are unsupported.
     */
    override fun close() {
        buildSession.close()
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
         * diagnosable form. The fix is to bump the BTA API and implementation versions.
         */
        val DEFAULT_JVM_TARGET: JvmTarget by lazy {
            val jvmTargetStr = System.getProperty("java.specification.version")
            JvmTarget.entries.find { it.stringValue == jvmTargetStr } ?: JvmTarget.entries.last()
        }
    }
}

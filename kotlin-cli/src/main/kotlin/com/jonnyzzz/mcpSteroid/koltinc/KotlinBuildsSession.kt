package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.walk
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
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
 * Represents a build session for compiling Kotlin code with various compilation execution policies:
 * - in Kotlin daemon (default) - compilation runs in a separate Kotlin daemon process which could be shared
 * between multiple processes
 * - in process - compilation runs inside the current process
 *
 * This class keeps Kotlin caches between different compilations. To drop them - call `close()` method.
 * It is fine to call again compilation after calling `close()`.
 *
 * @param kotlinLogger Optional logger used for capturing Kotlin compilation logs.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class KotlinBuildsSession(
    val workingDir: Path,
    val kotlinLogger: KotlinLogger? = null,
) : AutoCloseable {

    private val buildToolsApi = KotlinToolchains.loadImplementation(getBtaImplJars())
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
     * @param compilationTimeout maximum time compilation is allowed to run. The default is 120_000ms.
     * @param compilerMessageRenderer optional [CompilerMessageRenderer] to collect and transform compiler messages.
     * @param argumentsConf A lambda that allows configuration of additional JVM compiler arguments. '-no-stdlib' and '-no-reflect' arguments are always added.
     * @return A [CompilationResult] encapsulating the result of the compilation process.
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException on reaching configured timeout during compilation
     */
    suspend fun compileKotlin(
        sources: List<Path>,
        destinationDir: Path,
        executionPolicy: CompilationExecutionPolicy = CompilationExecutionPolicy.DAEMON,
        compilationTimeout: Duration = 120_000L.toDuration(DurationUnit.MILLISECONDS),
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
            withTimeout(compilationTimeout) {
                buildSession.executeOperation(
                    operation = jvmOperation,
                    executionPolicy = when (executionPolicy) {
                        CompilationExecutionPolicy.IN_PROCESS -> inProcessExecutionStrategy
                        CompilationExecutionPolicy.DAEMON -> daemonExecutionPolicy
                    },
                    logger = kotlinLogger,
                )
            }
        } catch (e: CancellationException) {
            jvmOperation.cancel()
            throw e
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

    private fun getBtaImplJars(): List<Path> {
        val btaImplUrl = this::class.java.getResource("/BTA-IMPL")?.toURI()
            ?: throw IllegalStateException("Could not find 'BTA-IMPL' in jar resources!")
        if (btaImplUrl.scheme == "file") {
            return Paths.get(btaImplUrl).listDirectoryEntries()
        }

        require(btaImplUrl.scheme == "jar") {
            "Unsupported BTA-IMPL resource protocol: $btaImplUrl"
        }

        val btaImplDir = workingDir.resolve("bta-impl")
        if (btaImplDir.notExists()) btaImplDir.createDirectory()
        FileSystems.newFileSystem(btaImplUrl, emptyMap<String, Any>()).use { jarFs ->
            jarFs.getPath("/BTA-IMPL").walk().forEach { file ->
                if (file.isRegularFile()) {
                    file.copyTo(btaImplDir.resolve(file.fileName.toString()))
                }
            }
        }

        return btaImplDir.listDirectoryEntries()
    }

    /**
     * Retrieves the `Path` of the Kotlin standard library JAR file from the build tools API implementation jars.
     */
    val defaultStdlibJar: Path
        get() = getBtaImplJars().single { it.name.contains("kotlin-stdlib") }

    enum class CompilationExecutionPolicy {
        IN_PROCESS, DAEMON;
    }

    companion object {
        /**
         * Default `-jvm-target` for the kotlinc subprocess, derived from the
         * JVM that owns this process — `java.specification.version` is `"21"`
         * on JDK 21, `"25"` on JDK 25, etc. The kotlinc inline-bytecode
         * compatibility rule requires the script's target to be ≥ the target
         * of any inline function it calls; the IntelliJ Platform 261.* (EAP
         * for 2026.1.x) ships inline functions compiled at target 25, so a
         * fixed target of "21" rejects them with `cannot inline bytecode
         * built with JVM target 25 into bytecode that is being built with
         * JVM target 21`. Deriving from the live JVM keeps the script's
         * target in lock-step with whatever JDK Gradle / the test runner
         * happens to run on — bumping the Gradle daemon JVM is then the
         * single lever that controls the kotlinc target.
         *
         * Defaults to `"21"` only as a last-resort fallback when the property
         * is unset (e.g. an unusual embedding).
         */
        val DEFAULT_JVM_TARGET: JvmTarget = getDefaultJvmTarget()

        private fun getDefaultJvmTarget(): JvmTarget {
            val jvmTargetStr = System.getProperty("java.specification.version") ?: "21"
            return JvmTarget.entries.find { it.stringValue == jvmTargetStr } ?: JvmTarget.JVM_21
        }
    }
}

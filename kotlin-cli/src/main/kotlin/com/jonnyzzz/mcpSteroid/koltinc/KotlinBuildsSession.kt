package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
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
     * @param argumentsConf A lambda that allows configuration of additional JVM compiler arguments. '-no-stdlib' and '-no-reflect' arguments are always added.
     * @return A [CompilationResult] encapsulating the result of the compilation process.
     */
    fun compileKotlin(
         sources: List<Path>,
         destinationDir: Path,
         executionPolicy: CompilationExecutionPolicy = CompilationExecutionPolicy.DAEMON,
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

         with(jvmCompilationBuilder.compilerArguments) {
             set(JvmCompilerArguments.NO_STDLIB, true)
             set(JvmCompilerArguments.NO_REFLECT, true)
             argumentsConf()
         }

         return buildSession.executeOperation(
             operation = jvmCompilationBuilder.build(),
             executionPolicy = when (executionPolicy) {
                 CompilationExecutionPolicy.IN_PROCESS -> inProcessExecutionStrategy
                 CompilationExecutionPolicy.DAEMON -> daemonExecutionPolicy
             },
             logger = kotlinLogger,
         )
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
        btaImplDir.createDirectory()
        FileSystems.newFileSystem(btaImplUrl, emptyMap<String, Any>()).use { jarFs ->
            jarFs.getPath("/BTA-IMPL").walk().forEach {
                it.copyTo(btaImplDir.resolve(it.fileName))
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
}

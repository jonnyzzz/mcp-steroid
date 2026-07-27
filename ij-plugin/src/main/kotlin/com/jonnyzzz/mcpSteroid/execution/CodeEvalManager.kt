/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.koltinc.KotlinBuildsSession
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.koltinc.scriptClassLoaderFactory
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlinx.coroutines.TimeoutCancellationException
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments

data class EvalResult(
    val result: List<suspend McpScriptContext.() -> Unit>,
    val lineMapping: LineMapping,
)

inline val Project.codeEvalManager: CodeEvalManager get() = service()

@Service(Service.Level.PROJECT)
class CodeEvalManager(
    private val project: Project,
) : Disposable {
    override fun dispose() {
        kotlinBuildsSession.close()
        @OptIn(ExperimentalPathApi::class)
        try {
            btaWorkingDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    private val log = thisLogger()
    private val compilationMutex = Mutex()
    private val btaWorkingDir = Files.createTempDirectory("kotlin-bta")
    private val kotlinBuildsSession = KotlinBuildsSession(
        workingDir = btaWorkingDir,
        kotlinLogger = KotlinLoggerWrapper(log),
    )

    suspend fun evalCode(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        if (compilationMutex.isLocked) {
            log.info("Compilation $executionId waiting for previous compilation to complete")
            resultBuilder.logProgress("Waiting for previous compilation to complete...")
        }
        return compilationMutex.withLock {
            evalCodeInternal(executionId, code, resultBuilder)
        }
    }

    private suspend fun evalCodeInternal(
        executionId: ExecutionId,
        code: String,
        resultBuilder: ExecutionResultBuilder
    ): EvalResult? {
        try {
            log.info("Compiling script $executionId")

            // Pre-compile VFS refresh: await until IntelliJ's VFS has re-ingested
            // any on-disk changes since the previous exec_code tail refresh or any
            // peer process edits. Compilation inputs (script source + classpath)
            // MUST be up-to-date — a user who edited a library JAR between calls
            // would otherwise get stale compilation against the old contents.
            // This is intentionally a BLOCKING await (not fire-and-forget): the
            // 30-second cap in VfsRefreshService guards against a pathological
            // hang. See VfsRefreshService.awaitRefresh / scheduleAsyncRefresh.
            project.vfsRefreshService.awaitRefresh()

            val compileClasspath = scriptClassLoaderFactory.ideClasspath()
            val compilerDir = project.executionStorage.createCompilerOutputDir(executionId)

            val outputJar = compilerDir / "script.jar"
            val wrappedCode = codeButcher.wrapToKotlinClass("Script_@jonnyzzz_${executionId.executionId}", code)
            project.executionStorage.writeWrappedScript(executionId, wrappedCode.code)

            val inputKt = compilerDir / "input.kt"
            inputKt.writeText(wrappedCode.code)

            val extraParams = Registry.stringValue("mcp.steroid.kotlinc.parameters")
                .split(",")
                .map { it.trim() }

            val compilerMessageRenderer = RecordingCompilerMessageRenderer()
            val compileResult = try {
                kotlinBuildsSession.compileKotlin(
                    sources = listOf(inputKt),
                    destinationDir = outputJar,
                    compilerMessageRenderer = compilerMessageRenderer,
                ) {
                    if (extraParams.isNotEmpty()) {
                        try {
                            // Currently, it may wipe previously configured arguments, so it should be applied first
                            applyArgumentStrings(extraParams)
                        } catch (e: Exception) {
                            resultBuilder.logException("Failed to apply extra arguments ${extraParams.joinToString()}", e)
                        }
                    }
                    set(JvmCompilerArguments.CLASSPATH, compileClasspath)
                }
            } catch (_: TimeoutCancellationException) {
                resultBuilder.reportFailed("kotlinc stopped on timeout")
                return null
            }

            val compilerMessages = compilerMessageRenderer.getRecordedMessages()
            compilerMessages.forEach {
                when(it.severity) {
                    CompilerMessageRenderer.Severity.DEBUG,
                    CompilerMessageRenderer.Severity.INFO -> resultBuilder
                        .logProgress("Compiler output: ${it.message} at ${it.location?.remappedLocation(wrappedCode.lineMapping)}")
                    CompilerMessageRenderer.Severity.WARNING -> resultBuilder
                        .logMessage("Compiler warning: ${it.message} at ${it.location?.remappedLocation(wrappedCode.lineMapping)}")
                    CompilerMessageRenderer.Severity.ERROR -> resultBuilder
                        .logMessage("Compiler error: ${it.message} at ${it.location?.remappedLocation(wrappedCode.lineMapping)}")
                }
            }

            if (compilerMessages.isNotEmpty()) {
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "kotlin.txt",
                    """
                    $compileResult
                    ---
                    ${compilerMessages.joinToString { "${it.severity}: ${it.message} at ${it.location?.locationString}" }}
                    """.trimIndent()
                )
            }

            when (compileResult) {
                CompilationResult.COMPILATION_SUCCESS -> Unit
                CompilationResult.COMPILATION_ERROR -> {
                    resultBuilder.reportFailed("Kotlin compilation has failed")
                    return null
                }
                CompilationResult.COMPILATION_OOM_ERROR -> {
                    resultBuilder.reportFailed("Kotlin compilation has failed with OutOfMemoryException")
                    return null
                }
                CompilationResult.COMPILER_INTERNAL_ERROR -> {
                    resultBuilder.reportFailed("Kotlin compiler has crashed")
                    return null
                }
            }

            val capturedBlocks = try {
                val builder = McpScriptBuilder()
                val scriptClassloader = scriptClassLoaderFactory.execCodeClassloader(outputJar)
                val scriptClazz = scriptClassloader.loadClass(wrappedCode.classFqn)
                val scriptObject = scriptClazz.constructors.single().newInstance()
                val loadMethod = scriptClazz.getMethod(wrappedCode.methodName, McpScriptBuilder::class.java)
                loadMethod.invoke(scriptObject, builder)
                builder.executeBlocks.toList()
            } catch (e: CancellationException) {
                // Coroutine cancellation propagates — never wrap as "Failed to
                // load generated code". The kotlinc compile already finished
                // by the time we reach this block, so we don't need to keep
                // it alive; we just stop here cleanly.
                throw e
            } catch (t: Throwable) {
                resultBuilder.reportFailed("Failed to load generated code. ${t}. ${t.stackTraceToString()}")
                return null
            }

            log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} script block(s)")

            project.executionStorage.writeCodeExecutionData(executionId, "compilation-success.txt", "Compiled")
            return EvalResult(capturedBlocks.toList(), wrappedCode.lineMapping)
        } catch (e: CancellationException) {
            // Coroutine cancellation propagates through the kotlinc invocation
            // and any pre/post bookkeeping above without being mis-reported as
            // a script "Error executing script" failure.
            throw e
        } catch (e: Throwable) {
            val message = "Error executing script $executionId: ${e.message}"

            if (e.toString().contains("Service is dying", ignoreCase = true)) {
                log.warn("Kotlin daemon is dying detected: ${e.message}", e)
                kotlinBuildsSession.close()
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Kotlin Daemon is dying. TRY AGAIN otherwise let user know")
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "dying-kotlin-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            if (e.toString().contains("Incomplete code", ignoreCase = true)
                || e.toString().contains("Code is incomplete", ignoreCase = true)
            ) {

                log.warn("Kotlin incomplete code error detected: ${e.message}", e)
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Incomplete code error. It usually means the Kotlin syntax is invalid or incomplete")

                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "incomplete-code-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            log.warn(message, e)
            resultBuilder.logException(message, e)
            resultBuilder.reportFailed(message)
            return null
        }
    }


    private class KotlinLoggerWrapper(
        private val logger: Logger,
    ) : KotlinLogger {
        override fun debug(msg: String) {
            logger.debug(msg)
        }

        override fun error(msg: String, throwable: Throwable?) {
            logger.error(msg, throwable)
        }

        override fun info(msg: String) {
            logger.info(msg)
        }

        override fun lifecycle(msg: String) {
            logger.info(msg)
        }

        override fun warn(msg: String, throwable: Throwable?) {
            logger.warn(msg, throwable)
        }

        override val isDebugEnabled: Boolean
            get() = logger.isDebugEnabled
    }

    private data class CompilerMessage(
        val severity: CompilerMessageRenderer.Severity,
        val message: String,
        val location: CompilerMessageRenderer.SourceLocation?,
    )

    private val CompilerMessageRenderer.SourceLocation.locationString: String
        get() = buildString {
            val fileUri = Paths.get(path).toUri()
            append(fileUri)
            if (line > 0 && column > 0) {
                append(":$line:$column")
            }
            append(' ')
        }

    private fun CompilerMessageRenderer.SourceLocation.remappedLocation(lineMapping: LineMapping): String =
        lineMapping.remapCompilerOutput(locationString)

    private class RecordingCompilerMessageRenderer : CompilerMessageRenderer {
        private val bufferedDiagnostics = ConcurrentLinkedQueue<CompilerMessage>()

        override fun render(
            severity: CompilerMessageRenderer.Severity,
            message: String,
            location: CompilerMessageRenderer.SourceLocation?
        ): String {
            bufferedDiagnostics.add(CompilerMessage(severity, message, location))

            return buildString {
                location?.apply {
                    val fileUri = Paths.get(path).toUri()
                    append(fileUri)
                    if (line > 0 && column > 0) {
                        append(":$line:$column")
                    }
                    append(' ')
                }
                append(message)
            }
        }

        fun getRecordedMessages(): List<CompilerMessage> = bufferedDiagnostics.toList()
    }
}

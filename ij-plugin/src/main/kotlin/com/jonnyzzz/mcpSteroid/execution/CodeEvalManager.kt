/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.execution.ParametersListUtil
import com.jonnyzzz.mcpSteroid.koltinc.KotlincCommandLine
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.koltinc.builder
import com.jonnyzzz.mcpSteroid.koltinc.kotlincProcessClient
import com.jonnyzzz.mcpSteroid.koltinc.scriptClassLoaderFactory
import com.jonnyzzz.mcpSteroid.koltinc.toArgFile
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.div
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

data class EvalResult(
    val result: List<suspend McpScriptContext.() -> Unit>,
    val lineMapping: LineMapping,
)

inline val Project.codeEvalManager: CodeEvalManager get() = service()

@Service(Service.Level.PROJECT)
class CodeEvalManager(
    private val project: Project,
) : Disposable {
    override fun dispose() = Unit

    private val log = thisLogger()
    private val compilationMutex = Mutex()

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

            (compilerDir / "classpath.txt").writeLines(compileClasspath.map { it.toString() })
            val classpathArgsFile = compilerDir / "kotlinc.args"

            val extraParams = ParametersListUtil.parse(Registry.stringValue("mcp.steroid.kotlinc.parameters"))

            val cmd = KotlincCommandLine
                .builder(outputJar)
                .withNoStdLib(true)
                .withExtraParameters(extraParams)
                .addClasspathEntries(compileClasspath)
                .addSource(inputKt)
                .build()
                .toArgFile(classpathArgsFile)

            val kotlincResult = kotlincProcessClient.kotlinc(
                cmd.args,
                compilerDir,
            )

            run {
                val rawCompilerOutput = kotlincResult.stdout.trim()
                val rawCompilerError = kotlincResult.stderr.trim()

                // Remap line numbers from wrapped-file coordinates to user-code coordinates
                // so agents see meaningful line references instead of wrapper boilerplate offsets.
                val compilerOutput = wrappedCode.lineMapping.remapCompilerOutput(rawCompilerOutput)
                val compilerError = wrappedCode.lineMapping.remapCompilerOutput(rawCompilerError)

                if (compilerOutput.isNotEmpty()) {
                    // Real content, not progress (#154): kotlinc stdout is empty on a clean
                    // compile, so when it IS present the agent must see it in the result.
                    resultBuilder.logMessage("Compiler Output:\n$compilerOutput")
                }

                if (compilerError.isNotEmpty()) {
                    // Log stderr output (warnings/errors) for transparency.
                    // Actual failure is determined by the exit code check below,
                    // since stderr may contain mere warnings that don't block compilation.
                    resultBuilder.logMessage("Compiler Errors/Warnings:\n$compilerError")
                }

                if (rawCompilerOutput.isNotEmpty() || rawCompilerError.isNotEmpty()) {
                    // Write raw (non-remapped) output to storage for debugging purposes
                    project.executionStorage.writeCodeExecutionData(
                        executionId,
                        "kotlinc.txt",
                        "${kotlincResult.exitCode}\n--- STDOUT ---\n$rawCompilerOutput\n\n--- STDERR ---\n$rawCompilerError"
                    )
                }

                if (kotlincResult.isTimeout) {
                    resultBuilder.reportFailed("kotlinc stopped on timeout")
                }

                if (kotlincResult.exitCode != 0) {
                    resultBuilder.reportFailed("kotlinc exited with code: ${kotlincResult.exitCode}\n$compilerError")
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
                kotlinDaemonManager.forceKillKotlinDaemon()
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
}

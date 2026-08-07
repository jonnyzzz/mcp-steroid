/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.execution.ParametersListUtil
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import com.jonnyzzz.mcpSteroid.koltinc.KotlinBuildsSession
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.koltinc.scriptClassLoaderFactory
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.div
import kotlin.io.path.writeText
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
    override fun dispose() = Unit

    private val log = thisLogger()
    private val compilationMutex = Mutex()

    // The BTA implementation jars ship as plain files in the plugin distribution's
    // `kotlinc/` folder (the successor of the old kotlinc dist) — nothing is
    // extracted at runtime, and the compiler impl classes never reach the IDE
    // plugin classloader: KotlinBuildsSession loads these jars into its own
    // isolated classloader.
    private val kotlinBuildsSession = run {
        // TODO(KT-88278): remove this process-global workaround when BTA accepts
        // explicit IntelliJ paths instead of reading/writing the host JVM's IntelliJ
        // system properties.
        // https://youtrack.jetbrains.com/issue/KT-88278/BTA-tools-avoid-usage-of-IntelliJ-system-properties
        // The relocated IdeaStandaloneExecutionSetup.doSetup() — which BTA runs before
        // EVERY in-process compile — writes `idea.config.path=some/non/existent/path`
        // when the property is unset (property names are JVM-global, not relocated;
        // every other property it writes equals the IDE bundle default). Pre-seed it
        // with a dedicated path under the system temp dir so third-party readers never
        // observe the garbage value — and never the IDE's own config folder, which
        // must not be advertised through a property the IDE itself didn't set. When
        // the host IDE already set the property, we leave it untouched; there is no
        // safe per-session redirect until KT-88278 provides one.
        if (System.getProperty("idea.config.path") == null) {
            System.setProperty(
                "idea.config.path",
                Paths.get(System.getProperty("java.io.tmpdir"), "mcp-steroid-kotlin-config").toString(),
            )
        }
        val session = KotlinBuildsSession(
            implClasspath = KotlinBuildsSession.implJarsFrom(
                PluginDescriptorProvider.getInstance().descriptor.pluginPath.resolve("kotlinc")
            ),
            kotlinLogger = KotlinLoggerWrapper(log),
        )
        // The session closes through the Disposer tree rather than an inline
        // dispose() override — children are disposed in a well-defined order
        // before the parent, and the registration is explicit at the same
        // place the resource is created.
        Disposer.register(this) { session.close() }
        session
    }

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

            // Space-separated argv tokens (quoting-aware), e.g. the default
            // "-language-version 2.3 -api-version 2.3 -Xdisable-default-scripting-plugin"
            // becomes 5 tokens — applyArgumentStrings rejects entries with embedded spaces.
            val extraParams = ParametersListUtil.parse(Registry.stringValue("mcp.steroid.kotlinc.parameters"))

            val compilerMessageRenderer = RecordingCompilerMessageRenderer()
            val compileResult = kotlinBuildsSession.compileKotlin(
                sources = listOf(inputKt),
                destinationDir = outputJar,
                compilerMessageRenderer = compilerMessageRenderer,
            ) {
                // Must be applied FIRST: applyArgumentStrings re-applies every
                // argument key and would reset options configured before it back
                // to compiler defaults. Invalid registry parameters throw — the
                // generic handler below surfaces them as an execution error
                // instead of silently compiling without the requested flags.
                applyArgumentStrings(extraParams)
                set(JvmCompilerArguments.CLASSPATH, compileClasspath)
            }

            val compilerMessages = compilerMessageRenderer.getRecordedMessages()
            val renderedErrors = mutableListOf<String>()
            compilerMessages.forEach {
                // User-code coordinates (remapped from the wrapped input.kt); no
                // suffix at all for messages without a source location. The message
                // text itself is remapped too — kotlinc occasionally embeds
                // `input.kt:N:C:` references inside message bodies (#221 semantics).
                val message = wrappedCode.lineMapping.remapCompilerOutput(it.message)
                val where = it.location?.let { loc -> " at ${loc.remappedLocation(wrappedCode.lineMapping)}" }.orEmpty()
                // The offending source line, as old kotlinc console output printed it.
                // K2 diagnostics can be name-less ("Unresolved label.") — the line
                // shows the agent WHAT failed, and downstream hint matching
                // (ExecutionSuggestionService) relies on seeing the actual code.
                val sourceLine = it.location?.lineContent
                    ?.takeIf { line -> line.isNotBlank() }
                    ?.let { line -> "\n    $line" }
                    .orEmpty()
                when(it.severity) {
                    CompilerMessageRenderer.Severity.DEBUG,
                    CompilerMessageRenderer.Severity.INFO -> resultBuilder
                        .logProgress("Compiler output: $message$where")
                    CompilerMessageRenderer.Severity.WARNING -> resultBuilder
                        .logMessage("Compiler warning: $message$where$sourceLine")
                    CompilerMessageRenderer.Severity.ERROR ->
                        renderedErrors.add("Compiler error: $message$where$sourceLine")
                }
            }

            persistCompilerMessages(executionId, compileResult, compilerMessages)

            when (compileResult) {
                CompilationResult.COMPILATION_SUCCESS -> Unit
                CompilationResult.COMPILATION_ERROR -> {
                    // Compiler errors ride in the FAILED message (not separate
                    // logMessage lines): reportFailed feeds the error-hint engine
                    // (ExecutionSuggestionService matches on errorMessages), and
                    // the agent sees one coherent failure block — same shape the
                    // old kotlinc-exit-code path had.
                    resultBuilder.reportFailed(buildString {
                        appendLine("Kotlin compilation has failed")
                        renderedErrors.forEach { appendLine(it) }
                    }.trimEnd())
                    return null
                }
                CompilationResult.COMPILATION_OOM_ERROR -> {
                    resultBuilder.reportFailed(buildString {
                        appendLine("Kotlin compilation has failed with OutOfMemoryException")
                        renderedErrors.forEach { appendLine(it) }
                    }.trimEnd())
                    return null
                }
                CompilationResult.COMPILER_INTERNAL_ERROR -> {
                    resultBuilder.reportFailed(buildString {
                        appendLine("Kotlin compiler has crashed")
                        renderedErrors.forEach { appendLine(it) }
                    }.trimEnd())
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


    /**
     * Persists the compiler verdict + all recorded messages as `kotlin.txt` under
     * the exec folder. Raw (non-remapped) locations on purpose — this file is for
     * debugging the wrapped input.kt, unlike the agent-visible remapped messages.
     */
    private suspend fun persistCompilerMessages(
        executionId: ExecutionId,
        compileResult: CompilationResult,
        compilerMessages: List<CompilerMessage>,
    ) {
        if (compilerMessages.isEmpty() && compileResult == CompilationResult.COMPILATION_SUCCESS) return
        project.executionStorage.writeCodeExecutionData(
            executionId,
            "kotlin.txt",
            buildString {
                appendLine(compileResult)
                appendLine("---")
                for (message in compilerMessages) {
                    val where = message.location?.let { " at ${it.locationString}" }.orEmpty()
                    appendLine("${message.severity}: ${message.message}$where")
                }
            }
        )
    }

    private class KotlinLoggerWrapper(
        private val logger: Logger,
    ) : KotlinLogger {
        override fun debug(msg: String) {
            logger.debug(msg)
        }

        override fun error(msg: String, throwable: Throwable?) {
            // Deliberately warn, not error: BTA routes every compiler ERROR message
            // (i.e. a plain compilation failure of an agent script — normal operation
            // here) through KotlinLogger.error, and IntelliJ's Logger.error files an
            // IDE fatal-error report (and throws in tests). Actual failures surface
            // via CompilationResult and the recorded compiler messages.
            logger.warn(msg, throwable)
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

    /**
     * Agent-visible location: `input.kt:LINE:COL` with LINE remapped from
     * wrapped-file to user-code coordinates. BTA hands us a structured
     * [CompilerMessageRenderer.SourceLocation], so the line is remapped
     * directly — the textual `LineMapping.remapCompilerOutput` regex expects
     * kotlinc's `file:line:col:` output shape and would not match here.
     * Wrapper-boilerplate lines (no mapping) keep their raw coordinates,
     * matching the old kotlinc-output behavior.
     */
    private fun CompilerMessageRenderer.SourceLocation.remappedLocation(lineMapping: LineMapping): String {
        val fileName = Paths.get(path).fileName.toString()
        val position = when {
            line > 0 && column > 0 -> ":${lineMapping.remapLine(line) ?: line}:$column"
            else -> ""
        }
        return "$fileName$position"
    }

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

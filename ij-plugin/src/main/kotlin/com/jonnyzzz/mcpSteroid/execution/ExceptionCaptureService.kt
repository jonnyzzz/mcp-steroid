/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.ide.plugins.PluginUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.ExceptionUtil
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Captured exception information from the IDE's logger error pipeline.
 */
data class CapturedIdeException(
    val timestamp: Instant,
    val throwable: Throwable,
    val message: String?,
    val stacktrace: String,
    val pluginId: String?
)

/**
 * Application-level service that captures IDE exceptions from the [Logger].
 *
 * This service installs a j.u.l. [Handler] on the root logger lazily on first use and emits
 * exceptions to a SharedFlow with no buffer - only active subscribers receive
 * exceptions at the moment they occur.
 *
 * ## Usage
 *
 * ```kotlin
 * val service = service<ExceptionCaptureService>()
 *
 * // Subscribe to exceptions flow during execution
 * service.exceptions.collect { exception ->
 *     // Handle exception
 * }
 * ```
 */
@Service(Service.Level.APP)
class ExceptionCaptureService : Disposable {
    private val initialized = AtomicBoolean(false)
    private val rootLogger = Logger.getLogger("")
    private val handler = CapturingIdeErrorsHandler(::captureException)

    /**
     * SharedFlow of captured IDE exceptions.
     * No replay, no buffer - only delivers to currently subscribed collectors.
     */
    private val _exceptions = MutableSharedFlow<CapturedIdeException>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Public flow of captured exceptions.
     * Subscribers only receive exceptions that occur while they are subscribed.
     */
    val exceptions: Flow<CapturedIdeException>
        get() {
            ensureInitialized()
            return _exceptions.asSharedFlow()
        }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            try {
                registerJulHandler()
            } catch (e: ProcessCanceledException) {
                initialized.set(false)
                throw e
            } catch (e: Exception) {
                initialized.set(false)
                thisLogger().warn("Failed to install IDE exception capture JUL handler", e)
            }
        }
    }

    private fun registerJulHandler() {
        rootLogger.addHandler(handler)
    }

    private fun captureException(record: LogRecord) {
        val throwable = record.thrown ?: return
        val stacktrace = ExceptionUtil.getThrowableText(throwable)

        // Try to find the plugin that caused the exception
        val pluginId = runCatching {
            PluginUtil.getInstance().findPluginId(throwable)?.idString
        }.getOrNull()

        var msg = ""
        record.message?.let {
            msg += it
        }
        if (msg.isNotEmpty()) {
            msg += ": "
        }
        throwable.message?.let {
            msg += it
        }
        if (record.parameters.isNotEmpty()) {
            msg += "\n" + record.parameters.joinToString(", ")
        }

        val captured = CapturedIdeException(
            timestamp = Instant.now(),
            throwable = throwable,
            message = msg,
            stacktrace = stacktrace,
            pluginId = pluginId
        )

        // Try to emit - if no subscribers, the exception is dropped (no buffer)
        _exceptions.tryEmit(captured)
    }

    override fun dispose() {
        rootLogger.removeHandler(handler)
    }
}

private class CapturingIdeErrorsHandler(private val onRecord: (LogRecord) -> Unit) : Handler() {
    init {
        level = Level.SEVERE
    }

    override fun publish(record: LogRecord?) {
        if (record == null) return
        if (!isLoggable(record)) return
        if (record.thrown == null) return

        onRecord(record)
    }

    override fun flush() = Unit

    override fun close() = Unit
}

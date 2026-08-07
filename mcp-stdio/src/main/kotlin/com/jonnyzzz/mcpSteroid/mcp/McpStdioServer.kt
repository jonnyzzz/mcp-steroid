/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Generic MCP stdio transport.
 *
 * Mirrors [McpHttpTransport][com.jonnyzzz.mcpSteroid.mcp.McpHttpTransport] for HTTP: it reads framed
 * (or NDJSON) JSON-RPC messages from [input], dispatches each through an
 * [McpServerCore], and writes responses to [output] using the same framing the peer
 * is using. Server-initiated notifications and server-to-client requests (e.g.
 * `sampling/createMessage`) are streamed concurrently from the session's channels.
 *
 * The class is transport-only: it has no opinion about which tools, resources, or
 * prompts are exposed — those are configured on the [server] argument by the caller.
 *
 * Both streams are constructor-injected to keep the class testable. Production
 * callers typically pass `System.in` / `System.out`; tests pass
 * `ByteArrayInputStream` / `ByteArrayOutputStream`.
 *
 * Lifecycle:
 *   1. Caller invokes [run]. A fresh [McpSession] is created. Two coroutines pump the
 *      session's outgoing notification and outgoing-request channels into framed
 *      bytes on [output].
 *   2. The reader loop pulls bytes from [input] via `runInterruptible(Dispatchers.IO)`
 *      so cancellation closes the read thread, feeds them to a [FramingBuffer], and
 *      dispatches **each parsed frame in its own child coroutine**. Concurrent dispatch
 *      is required to avoid deadlock when a tool handler suspends on a server-to-client
 *      request (e.g. `sampling/createMessage`) — the reader must keep accepting the
 *      client's response while the handler is parked.
 *   3. The output framing mode is locked on the **first inbound frame** ("framed" or
 *      "ndjson"); subsequent writes match that mode.
 *   4. Bytes that can never begin a frame (a stray log line, a BOM, a header block with no
 *      usable `Content-Length`) are discarded a run at a time, each answered with a
 *      `-32700` parse error and logged at WARN. The session keeps serving — the frames
 *      queued behind the garbage still get answered (jonnyzzz/mcp-steroid#461).
 *   5. On EOF (or [IOException]) the reader exits, any unparsed residue is reported the
 *      same way, and the session is closed (which drains its channels). The surrounding
 *      `coroutineScope` waits for all in-flight dispatch coroutines and pump coroutines to
 *      finish before returning.
 */
class McpStdioServer(
    private val server: McpServerCore,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) {
    private val log = thisLogger()

    @Volatile
    private var outputMode: String? = null

    private val outputLock = Any()

    /**
     * Run the stdio loop until [input] reaches EOF or the surrounding coroutine is
     * cancelled. Suspends; safe to call from `runBlocking { ... }` or `runTest { ... }`.
     */
    suspend fun run(): Unit = coroutineScope {
        val session = server.sessionManager.createSession()
        val dispatchJobs = Collections.synchronizedSet(mutableSetOf<Job>())
        try {
            launchOutgoingPumps(session)
            readLoop(this, session, dispatchJobs)

            // Grace period for in-flight dispatches to drain final
            // notifications / responses before `removeSession` tears down
            // the session's outgoing channels. Without this, a dispatch that
            // calls `session.sendNotification(...)` just before returning
            // races with the EOF → finally → channel-close path and the
            // notification is silently dropped — observed on TC under real
            // multi-threaded scheduling (test
            // `McpStdioServerProtocolTest."session sendNotification appears
            // on stdout as JSON-RPC notification"` passes locally under
            // `runTest` because TestCoroutineScheduler serialises the order,
            // but fails when `Dispatchers.IO` runs on real threads).
            //
            // Bounded so a dispatch parked on `session.sendRequest`
            // (e.g. waiting for `sampling/createMessage` after stdin EOF —
            // the no-client-ever-responds case) is still cancelled by
            // `removeSession` after the grace window closes.
            withTimeoutOrNull(GRACE_PERIOD_MS) {
                dispatchJobs.toList().joinAll()
            }
        } finally {
            // Close the session BEFORE the enclosing scope's implicit "wait for
            // children": closing cancels any pending Deferred in `session.sendRequest`,
            // which unblocks tool handlers parked on `sampling/createMessage` so their
            // dispatch coroutines can exit instead of holding `run()` open until the
            // sampling timeout fires after stdin EOF.
            server.sessionManager.removeSession(session.id)
        }
    }

    /**
     * Upper bound on time spent waiting for in-flight dispatch coroutines to
     * drain final notifications / responses after stdin EOF. 500 ms is
     * comfortably above the latency a "compute response then emit
     * notification then return" path needs (microseconds in practice), and
     * still small enough that a stuck sampling/createMessage dispatch — which
     * has no client to respond to it after EOF — doesn't keep the process
     * alive significantly longer than the original "close immediately on EOF"
     * behaviour.
     */
    private val GRACE_PERIOD_MS = 500L

    private fun CoroutineScope.launchOutgoingPumps(session: McpSession) {
        launch {
            session.notifications().collect { notification ->
                writeFrame(McpJson.encodeToString(JsonRpcNotification.serializer(), notification))
            }
        }
        launch {
            session.outgoingRequests().collect { request ->
                writeFrame(McpJson.encodeToString(JsonRpcRequest.serializer(), request))
            }
        }
    }

    /**
     * Reads frames until EOF and launches a child coroutine per frame for dispatch.
     * Dispatches are launched into [scope] (the parent scope from [run]) so that
     * EOF can flow through `finally → removeSession → channel close → Deferred cancel`
     * without being blocked by an inner `coroutineScope`'s wait-for-children.
     *
     * The single exception is `initialize`: it MUST complete before subsequent
     * dispatches read `clientCapabilities` from the session. We detect it cheaply at
     * the wire level and run it synchronously.
     */
    private suspend fun readLoop(
        scope: CoroutineScope,
        session: McpSession,
        dispatchJobs: MutableSet<Job>,
    ) {
        val buffer = FramingBuffer()
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (currentCoroutineContext().isActive) {
            val n = readChunk(buf)
            if (n < 0) break          // EOF
            if (n == 0) continue       // 0-byte read is unusual for blocking InputStream; defend against it
            buffer.append(buf, n)
            while (true) {
                val frame = buffer.readNextFrame()
                if (frame == null) {
                    // Bytes that can never start a frame used to sit at the head of the
                    // buffer forever, hiding every valid frame queued behind them (#461).
                    // Discard the run, answer it, and keep reading.
                    val unparsable = buffer.readNextUnparsableChunk() ?: break
                    reportUnparsableInput(unparsable, "input is not a JSON-RPC message")
                    continue
                }
                if (outputMode == null) outputMode = frame.mode
                if (frame.payloadText.isBlank()) continue
                val payload = frame.payloadText
                if (isInitializeRequest(payload)) {
                    dispatch(payload, session)
                } else {
                    val job = scope.launch { dispatch(payload, session) }
                    dispatchJobs.add(job)
                    job.invokeOnCompletion { dispatchJobs.remove(job) }
                }
            }
        }
        // Anything left over means stdin ended mid-frame: an unterminated line, or a frame
        // whose declared body never arrived. Reported, never swallowed — silence here is
        // what made #461 undiagnosable. Skipped under cancellation: the streams are being
        // torn down and a diagnostic write would only fail.
        if (currentCoroutineContext().isActive) {
            reportUnparsableInput(buffer.drain().trim(), "stdin ended mid-frame")
        }
    }

    /**
     * Answers input that never formed a frame with the JSON-RPC 2.0 §4.2 parse error
     * (`-32700`, `id: null`) and records the offending bytes at WARN — which reaches both
     * stderr and the log file, the two places a stuck client is debugged from. Blank
     * [text] is stray line breaks between frames: noise, not a protocol error.
     */
    private suspend fun reportUnparsableInput(text: String, reason: String) {
        if (text.isBlank()) return
        val excerpt = text.take(MAX_REPORTED_CHARS)
        val elision = if (text.length > MAX_REPORTED_CHARS) " ...(${text.length} chars total)" else ""
        log.warn("[MCP stdio] $reason, discarding unparsable input: $excerpt$elision")
        writeFrame(encodeError(JsonRpcErrorCodes.PARSE_ERROR, "Parse error: $reason: $excerpt$elision"))
    }

    /**
     * Cheap, parse-tolerant peek at the wire payload to detect an `initialize` request.
     * Returns false on parse errors or when `method` is structurally invalid (object /
     * array / boolean / etc.) — those go through the normal dispatch path and produce
     * a JSON-RPC compliant error response. Notifications never affect session state in
     * a way subsequent requests depend on, so only the request form (with `id`) needs
     * serialization.
     */
    private fun isInitializeRequest(payload: String): Boolean {
        val element = try { McpJson.parseToJsonElement(payload) } catch (_: Exception) { return false }
        if (element !is JsonObject) return false
        val methodElement = element["method"] ?: return false
        val method = (methodElement as? JsonPrimitive)?.contentOrNull ?: return false
        val hasId = element["id"] != null
        return hasId && method == McpMethods.INITIALIZE
    }

    /**
     * Performs the blocking [InputStream.read] on [Dispatchers.IO] inside
     * [runInterruptible] so coroutine cancellation interrupts the read thread.
     * Returns -1 on EOF or [IOException]; -1 also bubbles out as EOF on cancellation.
     */
    private suspend fun readChunk(buf: ByteArray): Int = runInterruptible(Dispatchers.IO) {
        try {
            input.read(buf)
        } catch (e: IOException) {
            log.info("[MCP stdio] read terminated: ${e.message}")
            -1
        }
    }

    private suspend fun dispatch(payload: String, session: McpSession) {
        val response = try {
            server.handleMessage(payload, session)
        } catch (e: CancellationException) {
            // Never swallow cancellation — propagate so the parent scope shuts down cleanly.
            throw e
        } catch (e: Exception) {
            log.warn("[MCP stdio] handler failed", e)
            encodeError(JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
        }
        if (response != null) writeFrame(response)
    }

    /**
     * Serializes the framed bytes onto [output] under [outputLock] (so concurrent
     * dispatchers and outgoing pumps don't interleave bytes), with the blocking
     * `write`/`flush` itself dispatched on [Dispatchers.IO].
     */
    private suspend fun writeFrame(text: String) {
        // Default to NDJSON: the MCP 2025-11-25 stdio transport spec mandates NDJSON,
        // and any peer that speaks Content-Length will set `outputMode` on its first
        // inbound frame before responses are emitted. Defaulting to framed would corrupt
        // a spec-only NDJSON peer if the server emits a notification before any input.
        val frame = when (outputMode ?: FrameMode.NDJSON) {
            FrameMode.FRAMED -> encodeFramedMessage(text)
            else -> encodeNdjsonMessage(text)
        }
        val bytes = frame.toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) {
            synchronized(outputLock) {
                output.write(bytes)
                output.flush()
            }
        }
    }

    /** JSON-RPC 2.0 error response with a null id — for failures with no addressable request. */
    private fun encodeError(code: Int, message: String): String {
        val response = JsonRpcResponse(
            id = JsonNull,
            error = JsonRpcError(code = code, message = message)
        )
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    private companion object {
        private const val READ_BUFFER_SIZE = 8192

        /**
         * How much of an unparsable input run is echoed into the WARN and the `-32700`
         * message. Enough to recognise a BOM, a CRLF, or a stray log line; short enough
         * that a megabyte of garbage can't blow up the log or the response.
         */
        private const val MAX_REPORTED_CHARS = 200
    }
}

/**
 * Wire-level framing mode. Values match the [FrameResult.mode] strings produced by
 * [FramingBuffer] so we can compare on identity (and dodge the stringly-typed pitfall
 * of `if (outputMode == "ndjson")` typos).
 */
internal object FrameMode {
    const val NDJSON = "ndjson"
    const val FRAMED = "framed"
}


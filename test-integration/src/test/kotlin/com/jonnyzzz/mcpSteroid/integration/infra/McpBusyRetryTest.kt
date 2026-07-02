/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The "IDE is still busy — call again" retry that `mcpExecuteCode` relies on while a big-project
 * import/indexing saturates the IDE (see jonnyzzz/mcp-steroid#169). The exception types drive the wait —
 * `mcpExecuteCode` needs no try/catch. The two busy signals are retried:
 *  - a clean result carrying the INDEXING IN PROGRESS marker ([isIndexingInProgress]) → `waitForValue` null
 *    → call again, and
 *  - a [TransientMcpRequestException] (the request's curl was killed, exit -1, because the IDE could not
 *    even answer) — a plain exception that [waitFor] swallows-and-retries.
 *
 * A full MCP failure throws [McpRequestFailedError] — a [WaitAbortedError] (an Error) — which
 * [waitFor] rethrows to stop at once, so a genuine crash is never masked by an hour of polling.
 */
class McpBusyRetryTest {

    @Test
    fun `isIndexingInProgress detects the busy marker`() {
        assertTrue(isIndexingInProgress("...\n$INDEXING_IN_PROGRESS_MARKER\n..."))
        assertFalse(isIndexingInProgress("done"))
        assertFalse(isIndexingInProgress(""))
    }

    @Test
    fun `waitFor retries a TransientMcpRequestException and then succeeds`() {
        var calls = 0
        waitFor(5_000, "transient MCP retry") {
            calls++
            if (calls < 3) throw TransientMcpRequestException("IDE too busy to answer (curl killed, exit -1)")
            true
        }
        assertEquals(3, calls, "a transient MCP failure must be retried, not abort the loop")
    }

    @Test
    fun `waitFor aborts at once on McpRequestFailedError (a WaitAbortedError)`() {
        var calls = 0
        val thrown = assertThrows<WaitAbortedError> {
            waitFor(5_000, "fatal MCP failure") {
                calls++
                throw McpRequestFailedError("MCP request failed (exit 7): connection refused")
            }
        }
        assertEquals(1, calls, "a full MCP failure must stop the loop immediately, not retry")
        assertInstanceOf(McpRequestFailedError::class.java, thrown)
    }

    @Test
    fun `parseMcpResponseOrFail returns the parsed element on valid JSON`() {
        val element = parseMcpResponseOrFail("""{"jsonrpc":"2.0","id":1}""")
        assertEquals("2.0", element.jsonObject["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseMcpResponseOrFail throws a terminal McpRequestFailedError on a malformed envelope`() {
        // A deterministic protocol breakage must fail fast, not be retried for the indexing budget: the
        // typed boundary lets the malformed parse propagate as a WaitAbortedError out of any waitFor.
        var calls = 0
        assertThrows<McpRequestFailedError> {
            waitFor(5_000, "malformed MCP envelope") {
                calls++
                parseMcpResponseOrFail("not json at all <html>503</html>")
                true
            }
        }
        assertEquals(1, calls, "a malformed MCP envelope must stop the loop immediately, not retry")
    }

    @Test
    fun `parseMcpToolResultBody returns the content texts of a well-formed result`() {
        val body = parseMcpToolResultBody(
            """{"result":{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"isError":false}}""",
        )
        assertEquals(listOf("hello", "world"), body.lines().filter { it.isNotBlank() })
    }

    @Test
    fun `parseMcpToolResultBody returns the body even when the tool reported an error`() {
        // The error text IS the payload (INDEXING IN PROGRESS marker, compile failure...) — the caller
        // inspects it; the flag is read separately via parseMcpToolResultIsError.
        val response = """{"result":{"content":[{"type":"text","text":"boom"}],"isError":true}}"""
        assertTrue(parseMcpToolResultBody(response).contains("boom"))
        assertTrue(parseMcpToolResultIsError(response))
        assertFalse(parseMcpToolResultIsError("""{"result":{"content":[],"isError":false}}"""))
    }

    @Test
    fun `a missing result is a graceful error result (no throw)`() {
        // Missing optional fields are the normal "script returned an error" path — NOT a protocol breakage:
        // empty body + isError=true, no exception (so mcpExecuteCode returns exitCode 1 immediately).
        val response = """{"jsonrpc":"2.0","id":2}"""
        assertTrue(parseMcpToolResultBody(response).isBlank())
        assertTrue(parseMcpToolResultIsError(response))
    }

    @Test
    fun `a malformed tool-result shape aborts, even inside a waitFor`() {
        // Valid JSON but wrong shape (result is a string, content is not an array) is a protocol breakage:
        // a terminal McpRequestFailedError, so it stops a waitFor at once instead of retrying for an hour.
        var calls = 0
        assertThrows<McpRequestFailedError> {
            waitFor(5_000, "malformed MCP tool-result shape") {
                calls++
                parseMcpToolResultBody("""{"result":"oops"}""")
                true
            }
        }
        assertEquals(1, calls, "a malformed tool-result shape must stop the loop immediately, not retry")
        // content present but not an array is likewise terminal
        assertThrows<McpRequestFailedError> { parseMcpToolResultBody("""{"result":{"content":"bad"}}""") }
        // a top-level non-object envelope
        assertThrows<McpRequestFailedError> { parseMcpToolResultBody("""["unexpected","array"]""") }
        // and the isError read is guarded the same way
        assertThrows<McpRequestFailedError> { parseMcpToolResultIsError("""{"result":"oops"}""") }
    }
}

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
    fun `parseMcpToolResultTexts reads content texts and isError from a well-formed tool result`() {
        val (texts, isError) = parseMcpToolResultTexts(
            """{"result":{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"isError":false}}""",
        )
        assertEquals(listOf("hello", "world"), texts)
        assertFalse(isError)
    }

    @Test
    fun `parseMcpToolResultTexts treats a missing result as a graceful error result (no throw)`() {
        // Missing optional fields are the normal "script returned an error" path — NOT a protocol breakage:
        // degrade to isError=true so mcpExecuteCode returns exitCode 1 immediately (never retried).
        val (texts, isError) = parseMcpToolResultTexts("""{"jsonrpc":"2.0","id":2}""")
        assertTrue(texts.isEmpty())
        assertTrue(isError)
    }

    @Test
    fun `parseMcpToolResultTexts aborts on valid JSON with a malformed tool-result shape`() {
        // Valid JSON but wrong shape (result is a string, content is not an array) is a protocol breakage:
        // a terminal McpRequestFailedError, so it stops a waitFor at once instead of retrying for an hour.
        var calls = 0
        assertThrows<McpRequestFailedError> {
            waitFor(5_000, "malformed MCP tool-result shape") {
                calls++
                parseMcpToolResultTexts("""{"result":"oops"}""")
                true
            }
        }
        assertEquals(1, calls, "a malformed tool-result shape must stop the loop immediately, not retry")
        // content present but not an array is likewise terminal
        assertThrows<McpRequestFailedError> { parseMcpToolResultTexts("""{"result":{"content":"bad"}}""") }
        // and a top-level non-object envelope
        assertThrows<McpRequestFailedError> { parseMcpToolResultTexts("""["unexpected","array"]""") }
    }

    @Test
    fun `firstMcpToolText returns the single content text, or aborts when there is none`() {
        assertEquals(
            """{"windows":[]}""",
            firstMcpToolText("""{"result":{"content":[{"type":"text","text":"{\"windows\":[]}"}]}}"""),
        )
        // a well-formed envelope with an empty content array is a terminal protocol breakage for these tools
        assertThrows<McpRequestFailedError> { firstMcpToolText("""{"result":{"content":[]}}""") }
    }
}

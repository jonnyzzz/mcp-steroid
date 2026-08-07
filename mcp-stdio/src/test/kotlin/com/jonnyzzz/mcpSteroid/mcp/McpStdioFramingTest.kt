/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class McpStdioFramingTest {

    // -------------------------------------------------------------------------
    // readNextFrame — Content-Length framing
    // -------------------------------------------------------------------------

    @Test
    fun `empty buffer returns null`() {
        val buf = FramingBuffer()
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `framed message with CRLF separator is parsed`() {
        val buf = FramingBuffer()
        val body = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        val bytes = encodeFramedMessage(body).toByteArray(Charsets.UTF_8)
        buf.append(bytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("framed", frame.mode)
    }

    @Test
    fun `framed message with LF-only separator is parsed`() {
        val buf = FramingBuffer()
        val body = """{"id":1}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bodyBytes.size}\n\n"
        buf.append(header.toByteArray(Charsets.UTF_8))
        buf.append(bodyBytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("framed", frame.mode)
    }

    @Test
    fun `partial headers return null until separator arrives`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 5".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `partial body returns null until full payload arrives`() {
        val buf = FramingBuffer()
        val header = "Content-Length: 20\r\n\r\n".toByteArray(Charsets.UTF_8)
        buf.append(header)
        buf.append("partial".toByteArray(Charsets.UTF_8))  // only 7 of 20 bytes
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `full body after partial delivers frame`() {
        val buf = FramingBuffer()
        val body = "12345678901234567890"  // exactly 20 chars (ASCII)
        val header = "Content-Length: 20\r\n\r\n".toByteArray(Charsets.UTF_8)
        buf.append(header)
        buf.append("12345".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        buf.append("678901234567890".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `two framed messages read in sequence`() {
        val buf = FramingBuffer()
        val msg1 = """{"id":"1"}"""
        val msg2 = """{"id":"2"}"""
        buf.append(encodeFramedMessage(msg1).toByteArray(Charsets.UTF_8))
        buf.append(encodeFramedMessage(msg2).toByteArray(Charsets.UTF_8))

        val frame1 = buf.readNextFrame()
        assertNotNull(frame1)
        assertEquals(msg1, frame1.payloadText)

        val frame2 = buf.readNextFrame()
        assertNotNull(frame2)
        assertEquals(msg2, frame2.payloadText)

        assertNull(buf.readNextFrame())
    }

    @Test
    fun `consumed bytes are removed from buffer after read`() {
        val buf = FramingBuffer()
        val msg = """{"id":"1"}"""
        buf.append(encodeFramedMessage(msg).toByteArray(Charsets.UTF_8))
        buf.readNextFrame()
        assertTrue(buf.isEmpty())
    }

    @Test
    fun `buffer not empty until frame is read`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 5\r\n\r\nhe".toByteArray(Charsets.UTF_8))
        assertFalse(buf.isEmpty())
    }

    @Test
    fun `content-length uses byte count for unicode multibyte chars`() {
        val buf = FramingBuffer()
        val body = "α"  // U+03B1, 2 bytes in UTF-8
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bodyBytes.size}\r\n\r\n"
        buf.append(header.toByteArray(Charsets.UTF_8))
        buf.append(bodyBytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `unicode payload fully preserved in framed mode`() {
        val buf = FramingBuffer()
        val body = """{"text":"Hello 世界 🌍"}"""
        buf.append(encodeFramedMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `frame result records framed mode`() {
        val buf = FramingBuffer()
        buf.append(encodeFramedMessage("{}").toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("framed", frame.mode)
    }

    // -------------------------------------------------------------------------
    // readNextFrame — NDJSON framing
    // -------------------------------------------------------------------------

    @Test
    fun `ndjson message terminated by newline is parsed`() {
        val buf = FramingBuffer()
        val body = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body.trim(), frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `partial ndjson line without newline returns null`() {
        val buf = FramingBuffer()
        buf.append("""{"jsonrpc":"2.0""".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson frame delivered after newline appended`() {
        val buf = FramingBuffer()
        val body = """{"id":"1"}"""
        buf.append(body.toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        buf.append("\n".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `two ndjson messages read in sequence`() {
        val buf = FramingBuffer()
        val msg1 = """{"id":"1"}"""
        val msg2 = """{"id":"2"}"""
        buf.append(encodeNdjsonMessage(msg1).toByteArray(Charsets.UTF_8))
        buf.append(encodeNdjsonMessage(msg2).toByteArray(Charsets.UTF_8))

        val frame1 = buf.readNextFrame()
        assertNotNull(frame1)
        assertEquals(msg1, frame1.payloadText)
        assertEquals("ndjson", frame1.mode)

        val frame2 = buf.readNextFrame()
        assertNotNull(frame2)
        assertEquals(msg2, frame2.payloadText)

        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson mode not triggered for non-json start`() {
        val buf = FramingBuffer()
        buf.append("plain text\n".toByteArray(Charsets.UTF_8))
        // No Content-Length header, doesn't start with { or [ => no NDJSON parsing
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson array message is parsed`() {
        val buf = FramingBuffer()
        val body = """[{"id":"1"},{"id":"2"}]"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `unicode payload fully preserved in ndjson mode`() {
        val buf = FramingBuffer()
        val body = """{"text":"こんにちは 🎌"}"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body.trim(), frame.payloadText)
    }

    @Test
    fun `frame result records ndjson mode`() {
        val buf = FramingBuffer()
        buf.append(encodeNdjsonMessage("{}").toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("ndjson", frame.mode)
    }

    // -------------------------------------------------------------------------
    // encodeFramedMessage
    // -------------------------------------------------------------------------

    @Test
    fun `encodeFramedMessage produces Content-Length header`() {
        val encoded = encodeFramedMessage("""{"id":1}""")
        assertTrue(encoded.startsWith("Content-Length:"))
    }

    @Test
    fun `encodeFramedMessage content length matches utf8 byte count`() {
        val body = "α"  // 2 UTF-8 bytes
        val encoded = encodeFramedMessage(body)
        val lengthLine = encoded.lines().first()
        val claimedLength = lengthLine.substringAfter("Content-Length:").trim().toInt()
        assertEquals(2, claimedLength)
    }

    @Test
    fun `encodeFramedMessage uses CRLF separator`() {
        val encoded = encodeFramedMessage("{}")
        assertTrue(encoded.contains("\r\n\r\n"), "Expected CRLF separator")
    }

    @Test
    fun `encodeFramedMessage round-trip via FramingBuffer`() {
        val original = """{"jsonrpc":"2.0","id":"42","method":"ping"}"""
        val encoded = encodeFramedMessage(original)
        val buf = FramingBuffer()
        buf.append(encoded.toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(original, frame.payloadText)
    }

    // -------------------------------------------------------------------------
    // encodeNdjsonMessage
    // -------------------------------------------------------------------------

    @Test
    fun `encodeNdjsonMessage appends trailing newline`() {
        val encoded = encodeNdjsonMessage("{}")
        assertTrue(encoded.endsWith("\n"))
    }

    @Test
    fun `encodeNdjsonMessage does not add Content-Length`() {
        val encoded = encodeNdjsonMessage("{}")
        assertFalse(encoded.contains("Content-Length"))
    }

    @Test
    fun `encodeNdjsonMessage round-trip via FramingBuffer`() {
        val original = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        val encoded = encodeNdjsonMessage(original)
        val buf = FramingBuffer()
        buf.append(encoded.toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(original, frame.payloadText)
    }

    // -------------------------------------------------------------------------
    // Edge cases: Content-Length parsing
    // -------------------------------------------------------------------------

    @Test
    fun `content-length 0 yields empty payload frame`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("", frame.payloadText)
        assertEquals("framed", frame.mode)
    }

    @Test
    fun `content-length header is case insensitive`() {
        val buf = FramingBuffer()
        val body = """{"x":1}"""
        val bodyLen = body.toByteArray(Charsets.UTF_8).size
        buf.append("CONTENT-LENGTH: $bodyLen\r\n\r\n$body".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `content-length with extra whitespace around value`() {
        val buf = FramingBuffer()
        val body = """{"x":1}"""
        val bodyLen = body.toByteArray(Charsets.UTF_8).size
        buf.append("Content-Length:   $bodyLen  \r\n\r\n$body".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `additional headers before content-length are ignored`() {
        val buf = FramingBuffer()
        val body = """{"x":1}"""
        val bodyLen = body.toByteArray(Charsets.UTF_8).size
        val headers = "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: $bodyLen\r\n\r\n"
        buf.append(headers.toByteArray(Charsets.UTF_8))
        buf.append(body.toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `non-numeric content-length falls through to ndjson fallback`() {
        val buf = FramingBuffer()
        // "Content-Length: xyz" doesn't parse -> impl tries NDJSON; data doesn't
        // start with {/[, so readNextFrame returns null.
        buf.append("Content-Length: xyz\r\n\r\n{}\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `negative content-length is rejected`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: -5\r\n\r\n{}\n".toByteArray(Charsets.UTF_8))
        // Negative value is rejected by decodeContentLength; buffer does NOT
        // start with JSON-like byte, so NDJSON fallback also fails -> null.
        assertNull(buf.readNextFrame())
    }

    // -------------------------------------------------------------------------
    // Edge cases: NDJSON
    // -------------------------------------------------------------------------

    @Test
    fun `ndjson with leading whitespace is accepted and trimmed`() {
        val buf = FramingBuffer()
        // Leading spaces before the JSON object.
        buf.append("   {\"id\":\"1\"}\n".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("""{"id":"1"}""", frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `ndjson batch array over multiple appends`() {
        val buf = FramingBuffer()
        // Simulate partial delivery.
        buf.append("[{\"id\":\"1\"},".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        buf.append("{\"id\":\"2\"}]\n".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("""[{"id":"1"},{"id":"2"}]""", frame.payloadText)
    }

    // -------------------------------------------------------------------------
    // append(bytes, length) overload
    // -------------------------------------------------------------------------

    @Test
    fun `append with shorter length only copies requested prefix`() {
        val buf = FramingBuffer()
        val fullBody = """{"id":"1","junk":"ignoreme"}"""
        val shortBody = """{"id":"1"}"""
        val encoded = encodeNdjsonMessage(shortBody).toByteArray(Charsets.UTF_8)
        // Pad the source array with trailing bytes that must NOT be read.
        val padded = encoded + fullBody.toByteArray(Charsets.UTF_8)
        buf.append(padded, encoded.size)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(shortBody, frame.payloadText)
        assertTrue(buf.isEmpty())
    }

    // -------------------------------------------------------------------------
    // encodeFramedMessage edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `encodeFramedMessage empty payload produces zero-length header`() {
        val encoded = encodeFramedMessage("")
        assertEquals("Content-Length: 0\r\n\r\n", encoded)
    }

    @Test
    fun `encodeNdjsonMessage empty payload is just a newline`() {
        assertEquals("\n", encodeNdjsonMessage(""))
    }

    // -------------------------------------------------------------------------
    // readNextUnparsableChunk — jonnyzzz/mcp-steroid#461
    //
    // A leading byte run that can never begin a frame used to jam the buffer
    // forever: `readNextFrame` kept returning null, so every valid frame queued
    // behind the garbage was never seen. These tests pin the discard contract
    // that lets the transport report the garbage and carry on.
    // -------------------------------------------------------------------------

    @Test
    fun `garbage line is discarded so the frame behind it can be read`() {
        val buf = FramingBuffer()
        buf.append("this is not json\n{\"id\":\"1\"}\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame(), "a non-JSON prefix is not a frame")
        assertEquals("this is not json", buf.readNextUnparsableChunk())
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("""{"id":"1"}""", frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `garbage line with CRLF ending is discarded without the line break`() {
        val buf = FramingBuffer()
        buf.append("this is not json\r\n{\"id\":\"1\"}\n".toByteArray(Charsets.UTF_8))
        assertEquals("this is not json", buf.readNextUnparsableChunk())
        assertEquals("""{"id":"1"}""", buf.readNextFrame()?.payloadText)
    }

    @Test
    fun `a BOM before the payload is discarded as one garbage line`() {
        val buf = FramingBuffer()
        buf.append("\uFEFF{\"id\":\"1\"}\n{\"id\":\"2\"}\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame(), "a BOM-prefixed line does not start like JSON")
        assertEquals("\uFEFF{\"id\":\"1\"}", buf.readNextUnparsableChunk())
        assertEquals("""{"id":"2"}""", buf.readNextFrame()?.payloadText)
    }

    @Test
    fun `empty buffer has nothing to discard`() {
        assertNull(FramingBuffer().readNextUnparsableChunk())
    }

    @Test
    fun `a partial ndjson line is never discarded`() {
        val buf = FramingBuffer()
        buf.append("{\"id\":\"1\",".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextUnparsableChunk(), "the rest of the line may still arrive")
    }

    @Test
    fun `partial framed headers are never discarded`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 10\r\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextUnparsableChunk(), "the header block is still arriving")
    }

    @Test
    fun `a framed message whose body is still arriving is never discarded`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 20\r\n\r\npartial".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextUnparsableChunk(), "the declared body is still arriving")
    }

    @Test
    fun `a leading Content-Type header is never discarded`() {
        val buf = FramingBuffer()
        buf.append("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextUnparsableChunk(), "Content-Type may precede Content-Length")
    }

    @Test
    fun `a header block without a usable content-length is discarded whole`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: xyz\r\n\r\n{}\n".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        assertEquals("Content-Length: xyz", buf.readNextUnparsableChunk())
        assertEquals("{}", buf.readNextFrame()?.payloadText)
    }

    @Test
    fun `a blank leading line is discarded as empty text`() {
        val buf = FramingBuffer()
        buf.append("\nthis is not json\n".toByteArray(Charsets.UTF_8))
        assertEquals("", buf.readNextUnparsableChunk(), "a stray blank line is noise, not a protocol error")
        assertEquals("this is not json", buf.readNextUnparsableChunk())
        assertTrue(buf.isEmpty())
    }

    // -------------------------------------------------------------------------
    // drain — residue diagnostics at EOF (jonnyzzz/mcp-steroid#461)
    // -------------------------------------------------------------------------

    @Test
    fun `an unterminated garbage line is only surfaced by drain`() {
        val buf = FramingBuffer()
        buf.append("this is not json".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextUnparsableChunk(), "an unterminated line may still grow")
        assertEquals("this is not json", buf.drain())
        assertTrue(buf.isEmpty())
    }

    @Test
    fun `drain surfaces a truncated framed body`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 20\r\n\r\npartial".toByteArray(Charsets.UTF_8))
        assertEquals("Content-Length: 20\r\n\r\npartial", buf.drain())
        assertTrue(buf.isEmpty())
    }

    @Test
    fun `drain on an empty buffer is empty text`() {
        assertEquals("", FramingBuffer().drain())
    }
}

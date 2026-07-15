/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64

/** Pins the shared [renderTo] / [toEnvelopeJson] contract reused by every tool-backed command. */
class CliToolSupportTest {

    private fun buffers() = ByteArrayOutputStream() to ByteArrayOutputStream()
    private fun ByteArrayOutputStream.text() = toString(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `success text goes to stdout with exit OK`() {
        val (out, err) = buffers()
        val result = ToolCallResult(content = listOf(ContentItem.Text("hello world")))
        val exit = result.renderTo("demo", json = false, out = PrintStream(out), err = PrintStream(err))
        assertEquals(CliExit.OK, exit)
        assertEquals("hello world", out.text().trim())
        assertEquals("", err.text())
    }

    @Test
    fun `error content goes to stderr and stdout stays clean`() {
        val (out, err) = buffers()
        val result = ToolCallResult(content = listOf(ContentItem.Text("ERROR: boom")), isError = true)
        val exit = result.renderTo("demo", json = false, out = PrintStream(out), err = PrintStream(err))
        assertEquals(CliExit.TOOL_ERROR, exit)
        assertEquals("", out.text())
        assertTrue(err.text().contains("boom"))
    }

    @Test
    fun `image data is carried as-is in the json envelope`() {
        val raw = ByteArray(9) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val result = ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))

        val obj = Json.parseToJsonElement(result.toEnvelopeJson("shot")).jsonObject
        val item = obj["data"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject
        assertEquals("image", item["type"]!!.jsonPrimitive.content)
        // C7: image data is carried as-is (base64), not summarized to a byte count.
        assertEquals(b64, item["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", item["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `console image is written to a temp file and the path is printed`() {
        val raw = ByteArray(9) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val result = ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))
        val tmp = java.nio.file.Files.createTempDirectory("shot")
        val (out, err) = buffers()

        Presentation.Console { tmp }.render(result, "shot", PrintStream(out), PrintStream(err))

        val printed = out.text().trim()
        val path = java.nio.file.Path.of(printed.substringAfterLast(' ').ifBlank { printed })
        assertTrue(java.nio.file.Files.exists(path), "expected a written file, printed: $printed")
        assertEquals(9, java.nio.file.Files.size(path).toInt())
        assertEquals("image/png", result.content.filterIsInstance<ContentItem.Image>().first().mimeType)
    }

    @Test
    fun `console image with undecodable base64 logs to stderr and prints a clear line, never crashes`() {
        val result = ToolCallResult(content = listOf(ContentItem.Image(data = "!!!not base64!!!", mimeType = "image/png")))
        val tmp = java.nio.file.Files.createTempDirectory("shot")
        val (out, err) = buffers()

        // The undecodable-base64 log line goes to the real System.err (not the `err` render param — same
        // logging contract the old decodedByteCount() used), so redirect it to observe it.
        val originalErr = System.err
        System.setErr(PrintStream(err))
        val exit = try {
            Presentation.Console { tmp }.render(result, "shot", PrintStream(out), PrintStream(err))
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(CliExit.OK, exit)
        assertTrue(out.text().contains("undecodable"), out.text())
        assertTrue(err.text().contains("not valid base64"), err.text())
    }

    @Test
    fun `json envelope carries tool identity, command and isError`() {
        val result = ToolCallResult(content = listOf(ContentItem.Text("x")), isError = false)
        val obj = Json.parseToJsonElement(result.toEnvelopeJson("execute_code")).jsonObject
        assertEquals("devrig", obj["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("execute_code", obj["command"]!!.jsonPrimitive.content)
        assertEquals(false, obj["isError"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `json presentation emits one envelope, console emits plain text`() {
        val result = ToolCallResult(content = listOf(ContentItem.Text("hi")))
        val (jo, je) = buffers()
        Presentation.Json().render(result, "demo", PrintStream(jo), PrintStream(je))
        val (co, ce) = buffers()
        Presentation.Console { java.nio.file.Files.createTempDirectory("t") }
            .render(result, "demo", PrintStream(co), PrintStream(ce))

        assertTrue(jo.text().trim().startsWith("{"), jo.text())
        assertEquals("hi", co.text().trim())
    }
}

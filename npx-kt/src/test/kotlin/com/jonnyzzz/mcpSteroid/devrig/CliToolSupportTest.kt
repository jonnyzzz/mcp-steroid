/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
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
    fun `image renders a byte-count placeholder and the envelope reports bytes`() {
        val (out, err) = buffers()
        val raw = ByteArray(9) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val result = ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))

        result.renderTo("shot", json = false, out = PrintStream(out), err = PrintStream(err))
        assertTrue(out.text().contains("[image: image/png, 9 bytes]"), out.text())

        val obj = Json.parseToJsonElement(result.toEnvelopeJson("shot")).jsonObject
        val item = obj["data"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject
        assertEquals("image", item["type"]!!.jsonPrimitive.content)
        assertEquals(9, item["bytes"]!!.jsonPrimitive.int)
    }

    @Test
    fun `json envelope carries tool identity, command and isError`() {
        val result = ToolCallResult(content = listOf(ContentItem.Text("x")), isError = false)
        val obj = Json.parseToJsonElement(result.toEnvelopeJson("execute_code")).jsonObject
        assertEquals("devrig", obj["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("execute_code", obj["command"]!!.jsonPrimitive.content)
        assertEquals(false, obj["isError"]!!.jsonPrimitive.booleanOrNull)
    }
}

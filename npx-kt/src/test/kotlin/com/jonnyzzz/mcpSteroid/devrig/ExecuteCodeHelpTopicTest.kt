/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Layered CLI help for `execute_code`: `devrig help execute_code` / `devrig execute_code --help` give a
 * concise entry that points deeper via `devrig prompt <uri>` — not the full 6k-token tool description.
 */
class ExecuteCodeHelpTopicTest {

    private fun render(topic: String?): String {
        val buf = ByteArrayOutputStream()
        printTopicHelp(topic, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ------------------------------ parsing ------------------------------

    @Test
    fun `help execute_code parses to a topic-scoped help`() {
        val command = parseDevrigCommand(arrayOf("help", "execute_code"))
        assertTrue(command is DevrigCommand.DevrigCommandHelp)
        assertEquals("execute_code", (command as DevrigCommand.DevrigCommandHelp).topic)
    }

    @Test
    fun `execute_code --help routes to the execute_code topic, not the missing-args error`() {
        val command = parseDevrigCommand(arrayOf("execute_code", "--help"))
        assertTrue(command is DevrigCommand.DevrigCommandHelp, "got $command")
        assertEquals("execute_code", (command as DevrigCommand.DevrigCommandHelp).topic)
    }

    @Test
    fun `bare help has no topic (global banner)`() {
        val command = parseDevrigCommand(arrayOf("help"))
        assertTrue(command is DevrigCommand.DevrigCommandHelp)
        assertNull((command as DevrigCommand.DevrigCommandHelp).topic)
    }

    // ------------------------------ rendering ------------------------------

    @Test
    fun `execute_code help shows flags, must-know rules and drill-down prompts`() {
        val text = render("execute_code")
        assertTrue(text.contains("--project_name"), text)
        assertTrue(text.contains("--code-file"), text)
        assertTrue(text.contains("--timeout") && text.contains("600"), text)
        assertTrue(text.contains("runBlocking"), "must-know rule missing: $text")
        assertTrue(text.contains("devrig prompt mcp-steroid://"), "drill-down pointers missing: $text")
    }

    @Test
    fun `topic help is a concise entry, not the full tool description`() {
        val topicHelp = render("execute_code")
        // The full execute_code tool description is ~27k chars; the layered entry must stay small.
        assertTrue(topicHelp.length < 3000, "topic help should be concise, was ${topicHelp.length} chars")
    }

    @Test
    fun `unknown or absent topic falls back to the global banner`() {
        val global = render(null)
        assertNotEquals(render("execute_code"), global)
        assertEquals(global, render("no-such-command"), "unknown topic must fall back to the global help")
    }

    @Test
    fun `execute_code help documents stdin via code-file dash`() {
        val out = ByteArrayOutputStream()
        printExecuteCodeHelp(PrintStream(out))
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.contains("--code-file=-") || text.contains("\"-\""),
            "expected stdin affordance documented, got:\n$text")
        assertTrue(text.contains("stdin"), text)
        // C17: readBytes() reads to EOF (blocks until the stream closes) — no partial-read risk
        // for a slow producer. The help must say so.
        assertTrue(text.contains("EOF"), "expected the blocks-until-EOF note documented, got:\n$text")
    }
}

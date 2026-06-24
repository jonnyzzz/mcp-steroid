/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.process.assertExitCode
import com.jonnyzzz.mcpSteroid.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.process.assertOutputContains
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ProcessResultAssertionsTest {

    @Test
    fun `assertOutputContains passes when text in stdout`() {
        val result = ProcessResultValue(0, "hello world", "")
        result.assertOutputContains("hello")
    }

    @Test
    fun `assertOutputContains passes when text in stderr`() {
        val result = ProcessResultValue(0, "", "error details")
        result.assertOutputContains("error details")
    }

    @Test
    fun `assertOutputContains fails when text not found`() {
        val result = ProcessResultValue(0, "hello", "world")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertOutputContains("missing-text")
        }
    }

    @Test
    fun `assertOutputContains checks multiple strings`() {
        val result = ProcessResultValue(0, "hello world", "error details")
        result.assertOutputContains("hello", "error")
    }

    @Test
    fun `assertOutputContains fails on first missing string`() {
        val result = ProcessResultValue(0, "hello", "world")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertOutputContains("hello", "missing")
        }
    }

    @Test
    fun `assertExitCode passes on matching code`() {
        val result = ProcessResultValue(0, "", "")
        result.assertExitCode(0)
    }

    @Test
    fun `assertExitCode fails on mismatched code`() {
        val result = ProcessResultValue(1, "", "")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertExitCode(0)
        }
    }

    @Test
    fun `assertExitCode with lambda message fails with custom message`() {
        val result = ProcessResultValue(1, "out", "err")
        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertExitCode(0) { "custom-context" }
        }
        Assertions.assertTrue(ex.message!!.contains("custom-context"))
    }

    @Test
    fun `assertNoErrorsInOutput passes when no errors`() {
        val result = ProcessResultValue(0, "all good", "clean")
        result.assertNoErrorsInOutput("test")
    }

    @Test
    fun `assertNoErrorsInOutput fails on ERROR pattern in stdout`() {
        val result = ProcessResultValue(0, "ERROR: something broke", "")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoErrorsInOutput fails on tool not available`() {
        val result = ProcessResultValue(0, "tool xyz is not available", "")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoErrorsInOutput fails on failed to connect`() {
        val result = ProcessResultValue(0, "", "Failed to connect to server")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoMessageInOutput passes when pattern not found`() {
        val result = ProcessResultValue(0, "clean output", "clean err")
        result.assertNoMessageInOutput("forbidden")
    }

    @Test
    fun `assertNoMessageInOutput fails when pattern found`() {
        val result = ProcessResultValue(0, "contains forbidden-word here", "")
        Assertions.assertThrows(IllegalStateException::class.java) {
            result.assertNoMessageInOutput("forbidden-word")
        }
    }

    @Test
    fun `assertOutputContains returns the same result for chaining`() {
        val result = ProcessResultValue(0, "hello", "")
        val returned = result.assertOutputContains("hello")
        Assertions.assertSame(result, returned)
    }

    @Test
    fun `assertExitCode returns the same result for chaining`() {
        val result = ProcessResultValue(0, "", "")
        val returned = result.assertExitCode(0)
        Assertions.assertSame(result, returned)
    }
}

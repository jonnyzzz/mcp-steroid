/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * Fuzz: `parseDevrigCommand` must never throw on any argument vector. A single declared `@Test` drives
 * a deterministic corpus through one declared helper (no `@ParameterizedTest`/`@MethodSource` machinery)
 * — a failure rethrows with the exact iteration + args, which is far easier to debug than a parameterized
 * case label.
 */
class DevrigCommandFuzzTest {
    @Test
    fun `command selection never throws on any fuzzed invocation`() {
        val random = Random(0x5EED_2026)
        repeat(FUZZ_ITERATIONS) { iteration ->
            val size = random.nextInt(from = 0, until = 8)
            val args = Array(size) { ALPHABET[random.nextInt(ALPHABET.size)] }
            assertParsesWithoutThrowing(args, iteration)
        }
    }

    /** Declared per-case check: parse [args]; on any throwable, fail naming the exact fuzz case. */
    private fun assertParsesWithoutThrowing(args: Array<String>, iteration: Int) {
        try {
            parseDevrigCommand(args)
        } catch (e: Throwable) {
            throw AssertionError("parseDevrigCommand threw on fuzz #$iteration ${args.toList()}: $e", e)
        }
    }

    companion object {
        private const val FUZZ_ITERATIONS = 1_000

        private val ALPHABET = listOf(
            "--debug",
            "--json",
            "--home",
            "--version",
            "--help",
            "-h",
            "-v",
            "--frobnicate",
            "mcp",
            "mpc",
            "backend",
            "project",
            "install",
            "upgrade",
            "claude",
            "codex",
            "gemini",
            "download",
            "start",
            "stop",
            "provision",
            "idea-community",
            "pid-1",
            "port-63342",
            "xyzzy",
        )
    }
}

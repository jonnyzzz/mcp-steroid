/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class DevrigCommandFuzzTest {
    @ParameterizedTest
    @MethodSource("fuzzedInvocations")
    fun `command selection never throws`(args: Array<String>) {
        assertDoesNotThrow { parseDevrigCommand(args) }
    }

    companion object {
        @JvmStatic
        fun fuzzedInvocations(): Stream<Arguments> {
            val alphabet = listOf(
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
            val random = Random(0x5EED_2026)
            return (0 until 1_000).map {
                val size = random.nextInt(from = 0, until = 8)
                val args = Array(size) { alphabet[random.nextInt(alphabet.size)] }
                Arguments.of(args)
            }.stream()
        }
    }
}

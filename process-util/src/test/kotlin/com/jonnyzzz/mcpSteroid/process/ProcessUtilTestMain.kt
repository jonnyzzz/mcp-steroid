/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

/**
 * Tiny cross-platform child program for [ProcessRunnerTest]. It is launched by spawning the
 * SAME JVM the test runs on (`java -cp <test classpath> <thisMain> <mode> ...`), so the tests
 * are hermetic and behave identically on Linux, macOS, and Windows — no `echo`/`sleep`/shell
 * semantics that differ across platforms.
 *
 * Modes (first arg):
 *  - `both`              — write a line to stdout AND a line to stderr, exit 0.
 *  - `exit <code>`       — exit with the given code.
 *  - `readline`          — read ONE line from stdin and echo it; with closed/empty stdin the
 *                          read returns immediately (EOF) and the process exits, so it must NOT
 *                          hang. This is the #150 root-cause guard.
 *  - `sleep <millis>`    — sleep for the given duration (used to exercise the timeout path).
 */
object ProcessUtilTestMain {
    const val STDOUT_MARKER = "child-stdout-line"
    const val STDERR_MARKER = "child-stderr-line"
    const val NO_STDIN_MARKER = "child-saw-no-stdin"

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "both" -> {
                System.out.println(STDOUT_MARKER)
                System.out.flush()
                System.err.println(STDERR_MARKER)
                System.err.flush()
            }

            "exit" -> {
                System.exit(args[1].toInt())
            }

            "readline" -> {
                val line = readlnOrNull()
                if (line == null) {
                    println(NO_STDIN_MARKER)
                } else {
                    println("child-read:$line")
                }
            }

            "sleep" -> {
                Thread.sleep(args[1].toLong())
            }

            else -> {
                System.err.println("unknown mode: ${args.joinToString(" ")}")
                System.exit(2)
            }
        }
    }
}

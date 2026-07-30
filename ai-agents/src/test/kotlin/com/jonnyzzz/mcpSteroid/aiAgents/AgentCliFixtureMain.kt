/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Child-JVM stand-in for an agent CLI (`claude`/`codex`/`gemini`) in
 * [ProcessAiAgentCliRunner] tests. A real JVM process gives deterministic,
 * OS-independent behavior — shell commands differ per OS and are banned for
 * behavioral tests by the process-runner design doc.
 */
object AgentCliFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            // echo <exitCode>: one line to each stream, then exit.
            "echo" -> {
                System.out.println("fixture-stdout-line")
                System.err.println("fixture-stderr-line")
                System.out.flush()
                System.err.flush()
                Runtime.getRuntime().exit(args[1].toInt())
            }
            // stdin: report how many bytes stdin delivered before EOF, exit 0.
            "stdin" -> {
                var count = 0L
                val buf = ByteArray(8192)
                while (true) {
                    val n = System.`in`.read(buf)
                    if (n < 0) break
                    count += n
                }
                System.out.println("stdin-eof:$count")
                System.out.flush()
            }
            // sleep <pidFile>: report our pid to the file, then hang forever.
            "sleep" -> {
                Files.writeString(Paths.get(args[1]), ProcessHandle.current().pid().toString())
                while (true) Thread.sleep(60_000)
            }
            else -> {
                System.err.println("unknown fixture mode: ${args.toList()}")
                Runtime.getRuntime().exit(64)
            }
        }
    }
}

/**
 * Builds an [AiAgentCliInvocation] that launches the fixture as a child JVM.
 * The classpath travels via a java @argfile (forward slashes inside a quoted
 * token) so the command stays under the Windows ~32k command-line limit.
 */
fun agentCliFixtureInvocation(vararg mode: String): AiAgentCliInvocation {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
    val argFile: Path = Files.createTempFile("agent-cli-fixture-", ".args")
    argFile.toFile().deleteOnExit()
    Files.writeString(argFile, "-cp \"${System.getProperty("java.class.path").replace('\\', '/')}\"\n")
    return AiAgentCliInvocation(
        binary = javaBin.toString(),
        args = listOf("@$argFile", AgentCliFixtureMain::class.java.name) + mode,
    )
}

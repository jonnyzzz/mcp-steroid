/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Child-JVM test fixture for the process runner. Launched as a real OS
 * process by [processFixtureCommand]; emits deterministic bytes on every OS
 * (shell commands like `echo` are code-page- and quoting-dependent — banned
 * by the design doc's test plan).
 */
object ProcessFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "exit" -> {
                // exit <code>: fixed lines to both streams, then exit.
                System.out.println("out-first")
                System.err.println("err-first")
                System.out.println("out-second")
                System.out.flush()
                System.err.flush()
                Runtime.getRuntime().exit(args[1].toInt())
            }
            "utf8" -> {
                // Exact UTF-8 bytes incl. multi-byte, plus one malformed byte line.
                System.out.write("héllo-你好-😀\n".toByteArray(Charsets.UTF_8))
                System.out.write(byteArrayOf('b'.code.toByte(), 0xFF.toByte(), 'd'.code.toByte(), '\n'.code.toByte()))
                System.out.flush()
            }
            "stdin" -> {
                // Report how many bytes stdin delivered before EOF.
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
            "sleep" -> {
                while (true) Thread.sleep(60_000)
            }
            "grandchild" -> {
                // Spawn a sleeping grandchild JVM, report its pid, then sleep
                // forever ourselves (used for tree-kill verification).
                val pid = spawnSleeperGrandchild()
                System.out.println("grandchild-pid:$pid")
                System.out.flush()
                while (true) Thread.sleep(60_000)
            }
            "env" -> {
                System.out.println("env:${System.getenv(args[1]) ?: "<null>"}")
                System.out.flush()
            }
            "cwd" -> {
                System.out.println("cwd:${Paths.get("").toAbsolutePath()}")
                System.out.flush()
            }
            "flood" -> {
                // flood <lines>: deterministic volume for read-limit tests.
                val n = args[1].toInt()
                val out = System.out
                for (i in 0 until n) out.println("flood-$i-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                out.flush()
            }
            else -> {
                System.err.println("unknown fixture mode: ${args.toList()}")
                Runtime.getRuntime().exit(64)
            }
        }
    }

    private fun spawnSleeperGrandchild(): Long {
        val javaBin = currentJavaBinary()
        val argFile = Files.createTempFile("fixture-grandchild-", ".args")
        argFile.toFile().deleteOnExit()
        Files.writeString(argFile, classpathArgFileContent())
        val pb = ProcessBuilder(javaBin.toString(), "@$argFile", ProcessFixtureMain::class.java.name, "sleep")
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        return pb.start().pid()
    }
}

fun currentJavaBinary(): Path {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    return Paths.get(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
}

/**
 * `-cp "<classpath>"` in java @argfile syntax: forward slashes avoid
 * backslash-escape rules inside quoted argfile tokens; quoting covers
 * spaces. The @argfile keeps us under the Windows ~32k command-line limit.
 */
fun classpathArgFileContent(): String {
    val cp = System.getProperty("java.class.path").replace('\\', '/')
    return "-cp \"$cp\"\n"
}

/** Command line to launch the fixture as a child JVM. */
fun processFixtureCommand(vararg mode: String): List<String> {
    val argFile = Files.createTempFile("process-fixture-", ".args")
    argFile.toFile().deleteOnExit()
    Files.writeString(argFile, classpathArgFileContent())
    return listOf(currentJavaBinary().toString(), "@$argFile", ProcessFixtureMain::class.java.name) + mode
}

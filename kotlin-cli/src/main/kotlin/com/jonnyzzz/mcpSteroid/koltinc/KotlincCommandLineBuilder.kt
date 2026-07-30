/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class KotlincCommandLine(
    val args: List<String>,
    val outputJar: Path,
) {
    companion object
}

fun KotlincCommandLine.Companion.builder(outputJar: Path) = KotlincCommandLineBuilder(outputJar)

/**
 * Writes all args to a kotlinc @argfile and returns a new [KotlincCommandLine]
 * whose args reference the argfile. Uses kotlinc-compatible quoting via [writeKotlincArgFile].
 */
fun KotlincCommandLine.toArgFile(argFile: Path): KotlincCommandLine {
    argFile.parent?.let { Files.createDirectories(it) }
    writeKotlincArgFile(argFile, args)
    return KotlincCommandLine(
        args = listOf("@${argFile.toAbsolutePath()}"),
        outputJar = outputJar,
    )
}

class KotlincCommandLineBuilder(
    private val outputJar: Path,
) {
    private val sources = mutableListOf<Path>()
    private val classpath = linkedSetOf<Path>()
    private var jvmTarget: String = DEFAULT_JVM_TARGET
    private var noStdLib: Boolean = false
    private val extraParameters = mutableListOf<String>()

    fun addSource(source: Path) = apply {
        require(Files.exists(source)) { "Source file does not exist: $source" }
        sources.add(source)
    }

    fun addClasspathEntries(classpath: Collection<Path>) = apply {
        classpath.forEach { addClasspathEntry(it) }
    }

    fun addClasspathEntry(entry: Path) = apply {
        require(Files.exists(entry)) { "Classpath entry does not exist: $entry" }
        require(Files.isDirectory(entry) || Files.isRegularFile(entry)) {
            "Classpath entry must be a directory or file: $entry"
        }
        classpath.add(entry)
    }

    fun withJvmTarget(target: String) = apply {
        require(target.isNotBlank()) { "JVM target must not be blank" }
        jvmTarget = target
    }

    fun withNoStdLib(enabled: Boolean) = apply {
        noStdLib = enabled
    }

    fun withExtraParameters(params: List<String>) = apply {
        extraParameters.addAll(params)
    }

    fun build(): KotlincCommandLine {
        require(sources.isNotEmpty()) { "No Kotlin source files provided" }
        outputJar.parent?.let { Files.createDirectories(it) }

        val args = mutableListOf<String>()

        args.addAll(extraParameters)

        if (classpath.isNotEmpty()) {
            val cp = classpath.joinToString(File.pathSeparator) { it.toString() }
            args.add("-classpath")
            args.add(cp)
        }

        args.add("-jvm-target")
        args.add(jvmTarget)

        if (noStdLib) {
            args.add("-no-stdlib")
        }

        args.add("-d")
        args.add(outputJar.toString())

        for (source in sources) {
            args.add(source.toString())
        }

        return KotlincCommandLine(args = args, outputJar = outputJar)
    }

    companion object {
        /**
         * Default `-jvm-target` for the kotlinc subprocess, derived from the
         * JVM that owns this process — `java.specification.version` is `"21"`
         * on JDK 21, `"25"` on JDK 25, etc. The kotlinc inline-bytecode
         * compatibility rule requires the script's target to be ≥ the target
         * of any inline function it calls. Platform class-file versions as of
         * 2026-07-30: 261.* is v65 (Java 21), 262.* and 263.* are v69
         * (Java 25) — so a fixed target of "21" would reject 262+ platform
         * inline functions with `cannot inline bytecode built with JVM
         * target 25 into bytecode that is being built with JVM target 21`.
         * Deriving from the live JVM keeps the script's target in lock-step
         * with the JVM the IDE / Gradle / the test runner actually runs on
         * (an IDE never runs on a JBR older than its own bytecode), so the
         * host JVM is the single lever that controls the kotlinc target.
         *
         * Defaults to `"21"` only as a last-resort fallback when the property
         * is unset (e.g. an unusual embedding).
         */
        val DEFAULT_JVM_TARGET: String = System.getProperty("java.specification.version") ?: "21"
    }
}

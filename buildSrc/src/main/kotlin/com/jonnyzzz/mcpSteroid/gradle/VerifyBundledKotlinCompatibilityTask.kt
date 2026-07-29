/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.net.URL
import java.net.URLClassLoader
import java.util.LinkedHashSet

abstract class VerifyBundledKotlinCompatibilityTask : DefaultTask() {
    @get:Classpath
    abstract val mainRuntimeClasspath: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlincHome: DirectoryProperty

    @get:Input
    abstract val kotlinPluginVersion: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val ideKotlinVersion = probeKotlinVersionFromMainRuntimeClasspath(mainRuntimeClasspath.files.map { it.toPath() })
        val bundledKotlincVersion = detectBundledKotlincVersion(kotlincHome.get().asFile.toPath())
        val pluginVersion = KotlinVersionCompatibility.parseStrictVersion(kotlinPluginVersion.get())
            ?: throw GradleException("Cannot parse Kotlin plugin version: ${kotlinPluginVersion.get()}")

        val bundledCompatible = KotlinVersionCompatibility.isCompatible(ideKotlinVersion, bundledKotlincVersion)
        val pluginNotNewer = pluginVersion <= ideKotlinVersion
        val latestStableKotlinVersion = fetchLatestStableKotlinVersionOrNull()
        val latestStableStatus = when {
            latestStableKotlinVersion == null -> "Latest stable Kotlin release: unavailable"
            latestStableKotlinVersion == bundledKotlincVersion ->
                "Latest stable Kotlin release: $latestStableKotlinVersion (bundled kotlinc is up to date)"
            else -> "Latest stable Kotlin release: $latestStableKotlinVersion (WARNING: bundled kotlinc is $bundledKotlincVersion)"
        }

        val report = buildString {
            appendLine("IDE Kotlin version: $ideKotlinVersion")
            appendLine("Kotlin plugin version: $pluginVersion")
            appendLine("Bundled kotlinc version: $bundledKotlincVersion")
            appendLine("Kotlin plugin not newer than IDE: $pluginNotNewer")
            appendLine("Bundled kotlinc compatible (major match, minor diff <= 1): $bundledCompatible")
            appendLine(latestStableStatus)
        }

        val reportPath = reportFile.get().asFile.toPath()
        reportPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(reportPath, report)

        if (!pluginNotNewer) {
            throw GradleException(
                "Kotlin plugin version $pluginVersion is newer than IDE kotlin-stdlib $ideKotlinVersion. " +
                        "The Kotlin plugin version must not exceed the IDE bundled Kotlin.\n$report"
            )
        }

        if (!bundledCompatible) {
            throw GradleException(
                "Bundled kotlinc $bundledKotlincVersion is too far from IDE kotlin-stdlib $ideKotlinVersion. " +
                        "Expected same major and minor distance <= 1.\n$report"
            )
        }

        when {
            latestStableKotlinVersion == null -> logger.warn(
                "Unable to verify latest stable Kotlin release; continuing without failing the build."
            )

            latestStableKotlinVersion != bundledKotlincVersion -> logger.warn(
                "Bundled kotlinc {} is not the latest stable Kotlin release {}.",
                bundledKotlincVersion,
                latestStableKotlinVersion,
            )
        }

        logger.lifecycle(report.trim())
    }

    private fun fetchLatestStableKotlinVersionOrNull(): KotlinVersion? {
        return try {
            KotlinReleaseService.latestStableKotlinVersion()
        } catch (error: Exception) {
            logger.warn(
                "Failed to fetch latest stable Kotlin release metadata: {}",
                error.message ?: error::class.java.name,
            )
            null
        }
    }

    private fun probeKotlinVersionFromMainRuntimeClasspath(classpathEntries: List<Path>): KotlinVersion {
        require(classpathEntries.isNotEmpty()) { "main.runtimeClasspath must not be empty" }

        val classpathUrls = LinkedHashSet<URL>()
        classpathEntries.forEach { classpathUrls += expandClasspathEntry(it) }
        check(classpathUrls.isNotEmpty()) { "Resolved runtime classpath URLs are empty" }

        URLClassLoader(classpathUrls.toTypedArray(), null).use { classLoader ->
            val kotlinVersionClass = Class.forName("kotlin.KotlinVersion", true, classLoader)
            val current = kotlinVersionClass.getField("CURRENT").get(null)
                ?: throw GradleException("kotlin.KotlinVersion.CURRENT is null")

            fun readInt(methodName: String): Int {
                val value = kotlinVersionClass.getMethod(methodName).invoke(current)
                return (value as? Number)?.toInt()
                    ?: throw GradleException("Expected Number from KotlinVersion.$methodName, got ${value?.javaClass}")
            }

            return KotlinVersion(
                readInt("getMajor"),
                readInt("getMinor"),
                readInt("getPatch"),
            )
        }
    }

    private fun expandClasspathEntry(entry: Path): List<URL> {
        if (!Files.exists(entry)) return emptyList()

        if (Files.isRegularFile(entry)) {
            if (isKotlinPluginBundledArtifact(entry)) return emptyList()
            return listOf(entry.toUri().toURL())
        }

        require(Files.isDirectory(entry)) { "Unsupported classpath entry type: $entry" }
        val urls = LinkedHashSet<URL>()
        urls += entry.toUri().toURL()
        Files.walk(entry).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { !isKotlinPluginBundledArtifact(it) }
                .forEach { urls += it.toUri().toURL() }
        }
        require(urls.isNotEmpty()) { "Classpath directory has no classpath URLs: $entry" }
        return urls.toList()
    }

    /**
     * `plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar` (the IDE-bundled Kotlin
     * plugin's editor-time kotlinc distribution) ships its own kotlin-stdlib
     * pinned to whatever version the Kotlin plugin uses for analysis —
     * typically lagging behind the platform's `lib/util-8.jar` runtime
     * stdlib. Per the project's no-dependency-on-plugins/Kotlin rule, the
     * platform stdlib is the source of truth for "what version the IDE
     * runs at". Filter the Kotlin plugin's copy out of the probe classpath
     * so the URLClassLoader resolves kotlin.KotlinVersion.CURRENT from
     * `lib/util-8.jar` only.
     */
    private fun isKotlinPluginBundledArtifact(file: Path): Boolean {
        val s = file.toString().replace('\\', '/')
        return s.contains("/plugins/Kotlin/kotlinc/") || s.contains("/plugins/Kotlin/lib/")
    }

    private fun detectBundledKotlincVersion(kotlincRoot: Path): KotlinVersion {
        val executable = kotlincRoot.resolve(if (isWindows()) "bin/kotlinc.bat" else "bin/kotlinc")
        if (!Files.exists(executable)) {
            throw GradleException("Bundled kotlinc executable not found: $executable")
        }

        val command = if (isWindows()) {
            listOf("cmd", "/c", executable.toString(), "-version")
        } else {
            listOf(executable.toString(), "-version")
        }

        // cwd is irrelevant for `kotlinc -version`; kotlincRoot keeps Task.project out of the
        // execution path (deprecated in Gradle 9, config-cache incompatible, error in Gradle 10).
        val output = runCommand(command, kotlincRoot)
        if (output.exitCode != 0) {
            throw GradleException("Failed to execute bundled kotlinc.\n${output.output}")
        }

        return KotlinVersionCompatibility.parseKotlincVersionOutput(output.output)
            ?: throw GradleException(
                "Cannot parse bundled kotlinc version from output:\n${output.output}"
            )
    }

    private fun runCommand(command: List<String>, workDir: Path): CommandOutput {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        return CommandOutput(exitCode, output)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private data class CommandOutput(
        val exitCode: Int,
        val output: String,
    )
}

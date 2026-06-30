/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if any compiled `.class` shipped in [archives] declares a class-file major version
 * higher than the one allowed by [maxJavaFeature]. This guards a concrete runtime constraint: a plugin
 * (or the devrig binary) must load on the *oldest* JBR it targets. Android Studio on the 261 platform
 * bundles **JBR 21** (class-file major 65), while IntelliJ IDEA 2026.1 bundles JBR 25 (major 69) — so a
 * class compiled to major 69 throws `UnsupportedClassVersionError` in Android Studio. A JDK-25 toolchain
 * with no `-jvm-target` / `-Xjdk-release` override silently produces such classes, and nothing else in
 * the build catches it; this task does.
 *
 * Every bundled jar/zip is scanned recursively at any folder depth (see [ClassFileVersionScanner]) — not
 * just a `lib/` directory. Entries named `*.class` that fail to parse are reported as warnings rather than
 * silently skipped.
 */
abstract class VerifyClassFileVersionTask : DefaultTask() {
    /** Archives to scan. Each is a `.jar` or `.zip`; nested jars/zips inside are scanned recursively. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archives: ConfigurableFileCollection

    /**
     * Maximum Java *feature* release whose class files may appear (e.g. 21 => class-file major 65). This is
     * the feature version of the oldest JBR the artifact must load on. Keep it derived from a single source
     * of truth rather than re-hardcoded per call site, so a future JBR bump is a one-line change.
     */
    @get:Input
    abstract val maxJavaFeature: Property<Int>

    @TaskAction
    fun verify() {
        val maxFeature = maxJavaFeature.get()

        val results = archives.files.map { ClassFileVersionScanner.scanArchive(it, maxFeature) }
        val checked = results.sumOf { it.checked }
        val violations = results.flatMap { it.violations }
        val brokenClasses = results.flatMap { it.brokenClasses }

        // Surface unparseable .class entries (bad magic / truncated) rather than dropping them silently —
        // they usually mean a corrupt or mis-shaded artifact worth a look. Non-fatal: warn, don't fail.
        if (brokenClasses.isNotEmpty()) {
            logger.warn("Class-file version check: ${brokenClasses.size} entry(ies) named *.class did not parse as a class file and were skipped:")
            brokenClasses.take(MAX_REPORTED).forEach { logger.warn("  ! $it") }
            if (brokenClasses.size > MAX_REPORTED) logger.warn("  … and ${brokenClasses.size - MAX_REPORTED} more")
        }

        require(checked > 0) { "Scanned 0 class files across ${archives.files} — the guard verified nothing." }

        if (violations.isNotEmpty()) {
            val maxMajor = maxFeature + ClassFileVersionScanner.CLASS_MAJOR_OFFSET
            throw GradleException(
                buildString {
                    appendLine("Class-file version check failed: ${violations.size} class(es) exceed Java $maxFeature (class-file major $maxMajor).")
                    appendLine("These classes would throw UnsupportedClassVersionError on a JBR $maxFeature runtime (e.g. Android Studio on platform 261).")
                    appendLine("Compile to the target with `-jvm-target $maxFeature` + `-Xjdk-release=$maxFeature` (Kotlin) / `options.release = $maxFeature` (Java).")
                    appendLine("Offending classes (showing up to $MAX_REPORTED):")
                    violations.take(MAX_REPORTED).forEach { appendLine("  - ${it.location} (major ${it.major} / Java ${it.javaFeature})") }
                    if (violations.size > MAX_REPORTED) appendLine("  … and ${violations.size - MAX_REPORTED} more")
                },
            )
        }

        logger.lifecycle(
            "Class-file version OK: $checked class file(s) <= Java $maxFeature " +
                "(major ${maxFeature + ClassFileVersionScanner.CLASS_MAJOR_OFFSET})" +
                if (brokenClasses.isEmpty()) "." else "; ${brokenClasses.size} unparseable entry(ies) warned.",
        )
    }

    companion object {
        private const val MAX_REPORTED = 50
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.Project
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the

/**
 * The pathing-jar file name for an application named [appName] at [version]. Versioned exactly like the
 * other jars in `lib/` (`$appName-$version.jar` + a `-classpath` marker) so nothing is a hand-pinned
 * special case — every reference is generated from the same project version. Identical on every OS.
 */
fun pathingJarFileName(appName: String, version: String): String = "$appName-$version-classpath.jar"

/**
 * Shared logic (devrig + ocr-tesseract): replace the Gradle `application` start script's long inline
 * classpath with a single **pathing JAR** whose `Class-Path:` manifest references every runtime jar
 * *relative to the pathing jar itself* (bare names — both it and the jars live in `lib/`).
 *
 * Why: the stock start scripts emit one `set CLASSPATH=%APP_HOME%\lib\<jar1>;<jar2>;…` line (50+ entries).
 * When `%APP_HOME%` expands to a deep content-addressed install path, that line blows cmd.exe's
 * 8191-char limit ("The input line is too long" — caught on eugene-x220). A manifest `Class-Path` is
 * resolved relative to the JAR's own location (NOT the process CWD, so it never breaks devrig's
 * CWD-relative project paths) → one short classpath entry, path- and CWD-independent, generated ONCE at
 * dist build (no install-time / launch-time generation), the SAME jar on every OS.
 *
 * Requires the `application` plugin. Ships `<applicationName>-classpath.jar` in the distribution `lib/`
 * and repoints `startScripts.classpath` at it.
 */
fun Project.configurePathingJarClasspath() {
    val runtime = configurations.named("runtimeClasspath")
    val mainJar = tasks.named("jar") // the project's OWN jar (carries the main class) — first on the classpath
    val appName = the<JavaApplication>().applicationName
    val appVersion = version.toString()

    val pathingJar = tasks.register<Jar>("pathingJar") {
        description = "Pathing JAR: a manifest-only jar whose Class-Path references the runtime jars (keeps the launcher classpath short)."
        archiveFileName.set(pathingJarFileName(appName, appVersion))
        inputs.files(runtime, mainJar) // re-jar when the project jar or the runtime classpath changes
        // Resolve the classpath at EXECUTION time. The application start script's classpath is the project
        // jar FOLLOWED BY runtimeClasspath — mirror that order. Class-Path entries are relative to lib/
        // (where every jar lives), so just the file names suffice.
        doFirst {
            // Project jar first, then the runtime deps; dedup by name (the project jar can also appear in
            // runtimeClasspath). Class-Path entries are relative to lib/, so bare file names suffice.
            val files = (mainJar.get().outputs.files.files + runtime.get().files).distinctBy { it.name }
            manifest.attributes(mapOf("Class-Path" to files.joinToString(" ") { it.name }))
        }
    }

    // Ship the pathing jar in the distribution's lib/ (alongside the real jars it references)…
    the<DistributionContainer>().named("main") {
        contents { into("lib") { from(pathingJar) } }
    }
    // …and make the launchers reference ONLY it (CreateStartScripts renders each classpath file as
    // `$APP_HOME/lib/<name>`, and the jar IS in lib/ — so this yields one short, correct entry).
    tasks.named<CreateStartScripts>("startScripts") {
        classpath = files(pathingJar)
    }
}

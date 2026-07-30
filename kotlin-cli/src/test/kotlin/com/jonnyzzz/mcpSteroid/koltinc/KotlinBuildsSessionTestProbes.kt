/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.file.Path
import kotlin.io.path.name

/*
 * Reflection probes into a session's isolated BTA impl classloader.
 * Pinned to kotlin-build-tools-impl 2.4.x internals (same contract as
 * CompilerEnvironmentPin): any reflective failure throws loudly.
 * DELETE together with CompilerEnvironmentPin at the 2.5 line.
 */

/**
 * The kotlin-stdlib jar from the session's BTA implementation classpath — the
 * classpath the test snippets compile against. Production compiles against IDE
 * classpaths and never needs this; tests are the only consumer.
 */
val KotlinBuildsSession.defaultStdlibJar: Path
    get() = implClasspath.single { it.name.startsWith("kotlin-stdlib-") }

fun implClassLoaderOf(session: KotlinBuildsSession): ClassLoader =
    KotlinBuildsSession::class.java.getDeclaredField("implClassLoader")
        .apply { isAccessible = true }.get(session) as ClassLoader

/**
 * Reads `KotlinCoreEnvironment.Companion.applicationEnvironment` inside the session's
 * isolated BTA impl classloader — the static the pin keeps alive; null once disposed.
 */
fun applicationEnvironmentOf(session: KotlinBuildsSession): Any? {
    val companion = implClassLoaderOf(session)
        .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
        .getField("Companion").get(null)
    return companion.javaClass.methods
        .single { it.name == "getApplicationEnvironment" && it.parameterCount == 0 }
        .invoke(companion)
}

/** `KotlinCoreEnvironment.ourProjectCount` — the env ref-count. Pin held => 1 at rest; closed => 0. */
fun environmentRefCountOf(session: KotlinBuildsSession): Int =
    implClassLoaderOf(session)
        .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
        .getDeclaredField("ourProjectCount").apply { isAccessible = true }.getInt(null)

/**
 * Size of the live jar-handler cache (`FastJarFileSystem.myHandlers`, falling back to
 * `CoreJarFileSystem.myHandlers` when unmapping is impossible on this JVM).
 * Reads `fastJarFileSystemField` DIRECTLY — `getFastJarFileSystem()` would lazily create it.
 */
fun jarHandlerCountOf(session: KotlinBuildsSession): Int {
    val env = applicationEnvironmentOf(session)
        ?: error("no pinned application environment — compile at least once first")
    val fast = env.javaClass.getDeclaredField("fastJarFileSystemField")
        .apply { isAccessible = true }.get(env)
    val fs = fast ?: env.javaClass.getMethod("getJarFileSystem").invoke(env)
    val handlers = fs.javaClass.getDeclaredField("myHandlers")
        .apply { isAccessible = true }.get(fs) as Map<*, *>
    return handlers.size
}

/** Used heap after a forced-GC stabilization loop. Plain-thread helper (not coroutine code). */
fun usedHeapAfterGc(): Long {
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
    return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Pins the Kotlin compiler application environment inside the BTA implementation
 * classloader for the lifetime of a build session.
 *
 * BTA 2.4.10 tears the application environment down after EVERY compilation: the
 * `KotlinCoreEnvironment` ref-count (`ourProjectCount`) drops to zero per exec, and even
 * `kotlin.environment.keepalive` still clears the FastJarFileSystem handler caches via
 * `idleCleanup()`. For exec_code-shaped workloads — a tiny source against a huge constant
 * classpath — that means re-parsing the central directories of every classpath jar and
 * rebuilding their VFS trees on each compile. Holding ONE extra ref-count for the
 * session's lifetime keeps those caches alive across compilations: measured
 * 367 ms -> 218 ms warm snippet compiles on a 1789-jar IDE classpath (IN_PROCESS).
 *
 * This mirrors what upstream Kotlin master's BTA does itself (`ApplicationEnvironmentPin`
 * in kotlin-build-tools-impl). DELETE this class and the call sites when
 * `mcp.kotlinc.version` reaches the 2.5 line — the pin then comes built in.
 *
 * Everything here is reflective because the compiler classes live in the isolated
 * implementation classloader, and kotlin-compiler-embeddable relocates
 * `com.intellij.*` to `org.jetbrains.kotlin.com.intellij.*`. Any reflective failure
 * throws — a BTA version whose internals moved must fail loudly at session start,
 * not degrade silently back to per-compile cache teardown.
 *
 * Memory contract (verified against the 2.4.10 sources/bytecode): the pinned caches are
 * ALL strong references (`ConcurrentFactoryMap.createMap` → plain ConcurrentHashMap) —
 * the GC can never evict them, and nothing in them grows per compilation. For a CONSTANT
 * classpath the retained heap is flat after the first compile (~250 B/jar entry; ≈275 MB
 * for a full IDE classpath of ~1.1M entries). The one growth hazard is a VARYING jar
 * classpath: every distinct jar path pins a full entry tree until [dispose] — keep
 * snippet classpaths canonical and constant, and never put per-execution jars on them.
 *
 * Upstream issues to REVIEW when updating the kotlinc/BTA logic or bumping
 * mcp.kotlinc.version:
 * - KT-88182 — contention in FastJarHandler during compilation (the very caches this
 *   pin keeps warm; a fix may change the residual per-compile classpath cost).
 * - KT-88183 — compilation via daemon clears the compiler cache after each compilation
 *   (why the daemon flow was removed; a fix makes the daemon viable again).
 */
class CompilerEnvironmentPin private constructor(
    private val disposeMethod: java.lang.reflect.Method,
    private val disposable: Any,
) {
    /**
     * Releases the pin: drops the ref-count, letting the compiler dispose the
     * application environment (and with it the pinned jar caches). Safe to call once;
     * the underlying `Disposer.dispose` tolerates repeated disposal.
     */
    fun dispose() {
        disposeMethod.invoke(null, disposable)
    }

    companion object {
        fun acquire(implClassLoader: ClassLoader): CompilerEnvironmentPin {
            try {
                // The BTA in-process path runs IdeaStandaloneExecutionSetup.doSetup() before
                // EVERY compile anyway (CompilationServiceImpl/JvmCompilationOperationImpl +
                // KotlinCoreEnvironment.createForProduction — verified in 2.4.10 bytecode),
                // so this adds no new global exposure. Creating the environment from OUTSIDE
                // a compile without it dies in the relocated EarlyAccessRegistryManager clinit
                // ("Could not find installation home path"). All properties it writes equal
                // the IDE bundle defaults; the one write that doesn't (idea.config.path,
                // written only when unset) is pre-seeded by the ij-plugin caller.
                val setupClass = implClassLoader
                    .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup")
                setupClass.getMethod("doSetup").invoke(setupClass.getField("INSTANCE").get(null))

                val disposableClass = implClassLoader
                    .loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable")
                val disposerClass = implClassLoader
                    .loadClass("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer")
                val disposable = disposerClass.getMethod("newDisposable").invoke(null)
                val pin = CompilerEnvironmentPin(
                    disposeMethod = disposerClass.getMethod("dispose", disposableClass),
                    disposable = disposable,
                )
                try {
                    val configurationClass = implClassLoader
                        .loadClass("org.jetbrains.kotlin.config.CompilerConfiguration")
                    val environmentCompanion = implClassLoader
                        .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
                        .getField("Companion").get(null)
                    val acquireMethod = environmentCompanion.javaClass.getMethod(
                        "getOrCreateApplicationEnvironmentForProduction",
                        disposableClass,
                        configurationClass,
                    )
                    val environment = acquireMethod.invoke(
                        environmentCompanion,
                        disposable,
                        configurationClass.getDeclaredConstructor().newInstance(),
                    )
                    checkNotNull(environment) {
                        "getOrCreateApplicationEnvironmentForProduction returned null"
                    }
                } catch (t: Throwable) {
                    // Transactional: never keep a half-acquired disposable registered.
                    try {
                        pin.dispose()
                    } catch (disposeFailure: Throwable) {
                        t.addSuppressed(disposeFailure)
                    }
                    throw t
                }
                return pin
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to pin the Kotlin compiler application environment inside the BTA " +
                        "implementation classloader. This reflection targets kotlin-build-tools-impl " +
                        "2.4.x internals; if mcp.kotlinc.version moved to the 2.5 line, DELETE " +
                        "CompilerEnvironmentPin — upstream BTA pins the environment itself there.",
                    t,
                )
            }
        }
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Compile-time guard against accidentally pulling Java-plugin or Kotlin-plugin
 * types into production code paths.
 *
 * ## Why this exists
 *
 * The plugin ships against the IntelliJ Platform 261 build target but must
 * load and run in IDEs that do NOT bundle every optional plugin. PyCharm
 * (Community + Professional) is the canonical example: it does not include
 * `com.intellij.java` or `org.jetbrains.kotlin`. If a production code path
 * holds a static reference to a class from either plugin — even one that's
 * never reached on PyCharm — the JVM's class verifier may load it at
 * link time and throw `NoClassDefFoundError`, breaking plugin startup.
 *
 * The runtime safety net for this is `PluginRuntimeCompatibilityTest`
 * (`*runtime compat pycharm*`) which boots the production .zip inside a
 * PyCharm container and exercises core MCP tools. That test catches the
 * regression at test time. This lint catches it at compile time — minutes
 * earlier in the feedback loop and visible during code review.
 *
 * ## What's banned
 *
 * The conservative-by-design set of import prefixes below. Each entry is a
 * package whose every class is contributed by an optional IDE plugin, not
 * by the IntelliJ Platform itself:
 *
 *  - `org.jetbrains.kotlin.idea.*` — Kotlin plugin editor-side types
 *    (inspection wrappers, configurators, IDE extensions).
 *  - `org.jetbrains.kotlin.psi.*` — Kotlin PSI (`KtFile`, `KtClass`, …);
 *    available only when the Kotlin plugin is loaded.
 *  - `com.intellij.lang.java.*` — Java plugin's language extension layer
 *    (`JavaLanguage`, `JavaParserDefinition`, the Java-specific factories).
 *
 * Other types that look Java-ish but live in the PLATFORM module
 * (`com.intellij.psi.PsiElement`, `PsiFile`, `JavaTokenType`, …) are NOT
 * banned — those are available in every IntelliJ-based IDE.
 *
 * ## How to fix a violation
 *
 * The lint prints `<file>:<line>` for every offending import. Two paths:
 *
 *  1. **The import is for a feature that only makes sense in IDEA.**
 *     Move it behind a runtime gate that returns early on non-IDEA IDEs
 *     (e.g. `if (PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java")) == null) return null`).
 *     The class reference must not appear in any code path that runs on
 *     PyCharm — keeping it inside a class loaded lazily under the gate
 *     (a separate `@Service`, an inner object, a reflective lookup) is the
 *     usual pattern. Even with a gate, holding a typed reference at the
 *     top level (compile-time) still triggers verifier-time loading on
 *     class init. Lazy reflection or `ClassLoader.loadClass` keeps the
 *     typed reference out of the bytecode.
 *
 *  2. **The import is in a test fixture or a build-time script.**
 *     Move that file out of `src/main/kotlin` (test fixtures live under
 *     `src/test/kotlin`; build-time scripts under `buildSrc/` or under
 *     a Gradle source set that isn't shipped with the plugin .zip).
 *
 * The only legitimate production usage of these types is through
 * `McpScriptContext` / `steroid_execute_code` payloads that run INSIDE
 * the user's IDE — those are string-templated Kotlin scripts, not
 * production imports, and are out of this lint's scope by definition.
 *
 * ## ALLOWED_FILES
 *
 * If a file legitimately needs one of these imports (e.g. a typed entry
 * point that's already behind a runtime gate AND is documented to fail
 * fast on PyCharm), add it to [ALLOWED_FILES] below with a comment
 * explaining the gate.
 */
class NoForbiddenPluginImportsTest : BasePlatformTestCase() {

    companion object {
        /**
         * Files allowed to contain forbidden plugin-typed imports.
         * Each entry is a path relative to the project root. The list is
         * intentionally empty: as of 2026-05-26 every production module
         * was audited and zero matches were found. Add files here ONLY
         * with a justification comment that explains how the typed
         * reference avoids triggering JVM verifier-time loading on
         * PyCharm (typically: lazy via reflection, or gated behind a
         * separate classloader / Service that PyCharm never instantiates).
         */
        private val ALLOWED_FILES = emptySet<String>()

        /**
         * Each pattern is checked at the start of a line (after whitespace).
         * Patterns are listed as prefixes — anything starting with the
         * prefix counts as a violation.
         */
        private val FORBIDDEN_IMPORT_PREFIXES = listOf(
            "org.jetbrains.kotlin.idea.",
            "org.jetbrains.kotlin.psi.",
            "com.intellij.lang.java.",
        )

        /**
         * Production source roots. Test sources, build scripts, and
         * `src/main/prompts` are excluded — only Kotlin code that ends
         * up in the shipped plugin .zip is in scope.
         */
        private val PRODUCTION_SOURCE_ROOTS = listOf(
            "ij-plugin/src/main/kotlin",
            "devrig-common/src/main/kotlin",
            "mcp-core/src/main/kotlin",
            "mcp-http/src/main/kotlin",
            "mcp-stdio/src/main/kotlin",
            "mcp-steroid-server/src/main/kotlin",
            "execution-storage/src/main/kotlin",
            "ocr-common/src/main/kotlin",
            "prompts/src/main/kotlin",
            "prompts-api/src/main/kotlin",
            "closeable-stack/src/main/kotlin",
            "agent-output-filter/src/main/kotlin",
        )
    }

    fun testNoForbiddenPluginImportsInProductionKotlin() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val sourceRoots = PRODUCTION_SOURCE_ROOTS.map(projectHome::resolve)

        // Build per-prefix regexes. `^\s*import\s+<prefix>` matches the import
        // statement at the line head; the prefix's trailing `.` is regex-escaped
        // since it's already a literal dot in the package qualifier.
        val importPatterns = FORBIDDEN_IMPORT_PREFIXES.map { prefix ->
            Regex("""^\s*import\s+""" + Regex.escape(prefix))
        }

        // Exclude this file itself so it can document the patterns
        // without self-evasion tricks.
        val selfPath = projectHome
            .resolve("ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoForbiddenPluginImportsTest.kt")
            .normalize()

        val allowedPaths = ALLOWED_FILES.map { projectHome.resolve(it).normalize() }.toSet()

        val violations = mutableListOf<String>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            for (file in collectKotlinFiles(root)) {
                val normalized = file.normalize()
                if (normalized == selfPath) continue
                if (normalized in allowedPaths) continue
                val lines = Files.readAllLines(file)
                lines.forEachIndexed { index, line ->
                    val match = importPatterns.firstOrNull { it.containsMatchIn(line) }
                    if (match != null) {
                        violations.add("${projectHome.relativize(file)}:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }

        assertTrue(
            "Forbidden plugin-typed imports found in production Kotlin code. " +
                "These pull in Java-plugin or Kotlin-plugin types that PyCharm " +
                "does not load, causing NoClassDefFoundError on plugin startup. " +
                "See class-level KDoc for fix patterns.\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun collectKotlinFiles(root: Path): List<Path> {
        return Files.walk(root).use { paths ->
            paths
                .filter { it.isKotlinFile() }
                .collect(Collectors.toList())
        }
    }

    private fun Path.isKotlinFile(): Boolean {
        if (!Files.isRegularFile(this)) return false
        val fileName = fileName.toString()
        return fileName.endsWith(".kt") || fileName.endsWith(".kts")
    }
}

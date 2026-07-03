/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class NoTestModeBranchingTest : BasePlatformTestCase() {
    fun testNoIsUnitTestModeUsageInProject() {
        val sourceRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("ij-plugin").resolve("src")
        check(Files.isDirectory(sourceRoot)) {
            "Project src directory is missing: $sourceRoot"
        }

        val forbiddenToken = "is" + "UnitTestMode"
        // The ban targets test-mode BEHAVIOR BRANCHING. IdeRunMode.kt is the single
        // permitted reader: it performs diagnostics-only run-mode classification for
        // the startup log line and headless warning (#212) and never branches logic.
        val allowlist = setOf(
            "main/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunMode.kt",
        )
        val matches = mutableListOf<String>()

        for (file in collectKotlinFiles(sourceRoot)) {
            val relative = sourceRoot.relativize(file).toString().replace('\\', '/')
            if (relative in allowlist) continue
            val lines = Files.readAllLines(file)
            lines.forEachIndexed { index, line ->
                if (line.contains(forbiddenToken)) {
                    matches.add("$relative:${index + 1}")
                }
            }
        }

        assertTrue(
            "Forbidden test-only branching found in:\n${matches.joinToString("\n")}",
            matches.isEmpty()
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

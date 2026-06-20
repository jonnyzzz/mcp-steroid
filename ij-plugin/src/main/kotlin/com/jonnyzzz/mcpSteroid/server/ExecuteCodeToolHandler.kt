/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import java.nio.file.Files
import java.nio.file.Path


class ExecuteCodeToolHandlerIJ : ExecuteCodeToolHandler {
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ) : ToolCallResult {
        val project = service<OpenProjectsService>().resolveProject(projectName)

        val result = project
            .service<ExecutionManager>()
            .executeWithProgress(execCodeParams, callProgress)

        runCatching {
            analyticsBeacon.capture(
                event = "exec_code",
                project = project,
                properties = mapOf(
                    "result" to if (result.isError) "error" else "success"
                )
            )
        }

        return ExecuteCodeBuildAbortGuidance.appendTo(
            result = result,
            projectBasePath = project.basePath?.let(Path::of),
        )
    }
}

internal object ExecuteCodeBuildAbortGuidance {
    // Refer to the tool by its DECLARED name. The `mcp__<server>__<tool>` form is an agent-specific
    // encoding of the MCP server id + tool (Claude Code), not something we should bake into guidance the
    // server emits to every agent — each agent maps the declared name to its own internal handle.
    private const val FETCH_RESOURCE_TOOL = "steroid_fetch_resource"

    private val abortedWithoutErrorsPattern = Regex(
        pattern = """(?im)\b(?:Build|Compile)\s+errors:\s*false,\s*aborted:\s*true\b"""
    )

    fun appendTo(result: ToolCallResult, projectBasePath: Path?): ToolCallResult {
        val outputText = result.content
            .filterIsInstance<ContentItem.Text>()
            .joinToString("\n") { it.text }
        val guidance = guidanceFor(outputText, projectBasePath) ?: return result
        return result.copy(content = result.content + ContentItem.Text("\n$guidance"))
    }

    fun guidanceFor(outputText: String, projectBasePath: Path?): String? {
        if (!abortedWithoutErrorsPattern.containsMatchIn(outputText)) return null

        val resourceTarget = resourceTargetText(projectBasePath)
        return "REQUIRED ACTION: IDE build was aborted without compiler errors. " +
            "NEXT TOOL CALL must be $FETCH_RESOURCE_TOOL with URI $resourceTarget before using Bash. " +
            "Then run the fetched sync/configuration pattern and retry the IDE build/test. " +
            "Use Bash only if sync fails or times out."
    }

    private fun resourceTargetText(projectBasePath: Path?): String {
        val resourceUris = detectBuildResourceUris(projectBasePath)
        return if (resourceUris.size == 1) {
            resourceUris.single()
        } else {
            "the matching resource (${resourceUris.joinToString(" or ")})"
        }
    }

    private fun detectBuildResourceUris(projectBasePath: Path?): List<String> {
        val gradleUri = ExecuteCodeGradlePromptArticle().uri
        val mavenUri = ExecuteCodeMavenPromptArticle().uri
        if (projectBasePath == null) return listOf(gradleUri, mavenUri)

        val hasGradle = listOf(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts",
            "gradlew",
        ).any { Files.isRegularFile(projectBasePath.resolve(it)) }
        val hasMaven = Files.isRegularFile(projectBasePath.resolve("pom.xml"))

        return buildList {
            if (hasGradle) add(gradleUri)
            if (hasMaven) add(mavenUri)
        }.ifEmpty {
            listOf(gradleUri, mavenUri)
        }
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Files
import java.nio.file.Path

/**
 * Central service for managing MCP storage paths.
 *
 * Default storage location: ~/.mcp-steroid/runs/
 * Execution folders: {storage}/{execution-id}/
 *
 * Registry keys for customization:
 * - mcp.steroid.storage.path - Override storage folder path (empty = ~/.mcp-steroid/runs)
 */
@Service(Service.Level.PROJECT)
class StoragePaths(private val project: Project) {

    fun getMarkerFilePath(): Path? {
        if (!Registry.`is`("mcp.steroid.idea.description.enabled", true)) {
            return null
        }

        return resolveDotIdeaFolder(project)?.resolve("mcp-steroid.md")
    }

    private fun resolveDotIdeaFolder(project: Project) : Path? {
        val basePath = project.basePath ?: return null

        val ideaDir = Path.of(basePath, ".idea")
        if (!Files.exists(ideaDir)) return null

        return ideaDir
    }

    /**
     * The base directory for MCP execution storage.
     * Default: ~/.mcp-steroid/runs/
     *
     * Can be overridden via registry key "mcp.steroid.storage.path".
     */
    fun getGetMcpRunDir(): Path {
        val path = run {
            val customPath = Registry.stringValue("mcp.steroid.storage.path")
            if (customPath.isNotBlank()) {
                return@run Path.of(customPath)
            }

            return@run Path.of(System.getProperty("user.home"), ".mcp-steroid", "runs")
        }

        Files.createDirectories(path)
        return path
    }
}

inline val Project.storagePaths: StoragePaths get() = service()

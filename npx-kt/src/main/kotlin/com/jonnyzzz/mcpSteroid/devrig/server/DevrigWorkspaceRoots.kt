/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.Root
import com.jonnyzzz.mcpSteroid.thisLogger
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies the candidate directories devrig matches against open projects when a tool call omits
 * `project_name`. Prefers the MCP client's advertised workspace roots (via [rootsProvider]); always
 * appends the devrig process working directory ([processCwd]) as a fallback so a client that advertises
 * no roots still gets cwd-based detection. Result is cached for the (single, per-process) session.
 */
class DevrigWorkspaceRoots(
    private val rootsProvider: suspend () -> List<Root>?,
    private val processCwd: () -> String = { System.getProperty("user.dir") },
) {
    private val log = thisLogger()
    private val mutex = Mutex()

    @Volatile
    private var cached: List<Path>? = null

    suspend fun candidateDirs(): List<Path> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: computeDirs().also { cached = it }
        }
    }

    /** Clears the cache; a subsequent [candidateDirs] re-queries the client. */
    fun invalidate() {
        cached = null
    }

    private suspend fun computeDirs(): List<Path> {
        val roots = try {
            rootsProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to fetch MCP roots; falling back to process cwd", e)
            null
        }

        val rootDirs = roots.orEmpty().mapNotNull { rootUriToPath(it.uri) }
        val cwd = try {
            Path.of(processCwd())
        } catch (e: Exception) {
            log.warn("Invalid process cwd", e)
            null
        }
        return (rootDirs + listOfNotNull(cwd)).distinct()
    }

    private fun rootUriToPath(uri: String): Path? = try {
        Paths.get(URI(uri))
    } catch (e: Exception) {
        log.warn("Ignoring non-file MCP root uri: $uri", e)
        null
    }
}

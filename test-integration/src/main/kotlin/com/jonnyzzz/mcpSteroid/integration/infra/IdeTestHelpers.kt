/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val DOCKER_HOST_PATH_MAP_ENV = "MCP_STEROID_DOCKER_HOST_PATH_MAP"

private fun readFilePathFromSystemProperties(
    key: String,
    fallback: ((missingPath: String) -> File?)? = null,
): File {
    val path = System.getProperty(key)
        ?: error("$key system property not set — run via Gradle")
    val file = File(path)
    if (file.exists()) return file

    val fallbackFile = fallback?.invoke(path)
    if (fallbackFile != null && fallbackFile.exists()) {
        println("[IDE-AGENT] $key fallback: using ${fallbackFile.absolutePath} (missing original: $path)")
        return fallbackFile
    }

    require(file.exists()) { "Path not found: $file, from system properties: $key" }
    return file
}

private fun findLatestPluginZipFromDist(): File? {
    val distributionsDir = File("build/distributions")
    if (!distributionsDir.isDirectory) return null

    return distributionsDir.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension == "zip" && it.name.startsWith("mcp-steroid-") }
        ?.maxByOrNull { it.lastModified() }
}

internal fun remapPathForDockerHost(path: File, mappingSpec: String?): File {
    val mappings = parseDockerHostPathMappings(mappingSpec)
    if (mappings.isEmpty()) return path

    val absolutePath = path.absoluteFile
    val normalizedPath = normalizePathPrefix(absolutePath.path)
    for ((sourcePrefix, targetPrefix) in mappings) {
        if (normalizedPath == sourcePrefix) return File(targetPrefix)

        val sourceWithSlash = "$sourcePrefix/"
        if (normalizedPath.startsWith(sourceWithSlash)) {
            val suffix = normalizedPath.removePrefix(sourceWithSlash)
            return File(targetPrefix, suffix)
        }
    }

    return path
}

internal fun parseDockerHostPathMappings(mappingSpec: String?): List<Pair<String, String>> {
    if (mappingSpec.isNullOrBlank()) return emptyList()

    val mappings = mutableListOf<Pair<String, String>>()
    for (entry in mappingSpec.split(';', ',')) {
        val raw = entry.trim()
        if (raw.isEmpty()) continue

        val delimiterIndex = raw.indexOf('=')
        require(delimiterIndex > 0 && delimiterIndex < raw.lastIndex) {
            "Invalid docker host path mapping entry '$raw'. Expected format '<source>=<target>'."
        }

        val source = normalizePathPrefix(raw.substring(0, delimiterIndex).trim())
        val target = normalizePathPrefix(raw.substring(delimiterIndex + 1).trim())
        require(source.isNotEmpty()) { "Invalid docker host path mapping source in '$raw'." }
        require(target.isNotEmpty()) { "Invalid docker host path mapping target in '$raw'." }

        mappings += source to target
    }

    return mappings.sortedByDescending { (source, _) -> source.length }
}

private fun normalizePathPrefix(path: String): String {
    if (path.isEmpty()) return path
    if (path == "/") return path
    return path.trimEnd('/')
}

/**
 * Make [this] host path readable/writable/executable by any user (chmod a+rwX equivalent). Required before
 * bind-mounting a host dir into a container whose user (uid 1000) differs from the host uid — Linux bind
 * mounts do not remap UIDs (see [allocRunDirAndTitle]).
 */
internal fun File.makeContainerWritable(): File = apply {
    setReadable(true, /* ownerOnly = */ false)
    setWritable(true, /* ownerOnly = */ false)
    setExecutable(true, /* ownerOnly = */ false)
}

object IdeTestFolders {
    val pluginZip = readFilePathFromSystemProperties("test.integration.plugin.zip") {
        findLatestPluginZipFromDist()
    }
    val agentOutputFilterZip = readFilePathFromSystemProperties("test.integration.agent.output.filter.zip")
    val devrigPackageZip = readFilePathFromSystemProperties("test.integration.devrig.package.zip")
    val ideChannel: String = System.getProperty("test.integration.ide.channel", "stable").trim().lowercase()
    val dockerDir = readFilePathFromSystemProperties("test.integration.docker")

    /**
     * Host-side bare git repository cache directory, or null if not configured.
     * Set via `test.integration.repo.cache.dir` system property.
     * When non-null, it is mounted read-only at `/repo-cache` inside containers so
     * [com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver.cloneFromCachedBare] can be used.
     */
    val repoCacheDir: File = System.getProperty("test.integration.repo.cache.dir")
        ?.let { File(it).also { dir -> dir.mkdirs() } }
        ?: error("Failed to configure repo cache directory, set \"test.integration.repo.cache.dir\" ")

    /**
     * Host directory caching IDE archives (e.g. `ideaIC-<version>.tar.gz`). Shared by the IDE-image
     * download and the devrig managed-backend downloads RW-mount — an archive fetched by either is reused
     * by the other (IdeDownloader skips when the file already exists). Created on first use.
     * Set via `test.integration.ide.download.dir` (default `build/ide-download`).
     */
    val ideDownloadDir: File = File(System.getProperty("test.integration.ide.download.dir", "build/ide-download"))

    /**
     * Host directory persisting the container's Maven (`~/.m2`) and Gradle (`~/.gradle`) caches across
     * runs, so library jars + their sources/javadoc (always auto-downloaded on import) are resolved once
     * and reused — turning a multi-minute cold Keycloak import into a warm one. Set via
     * `test.integration.dependency.cache.dir` (the Gradle build points it at a root-shared dir so the
     * integration and experiments suites — which never run concurrently — share one cache).
     */
    val dependencyCacheDir: File =
        File(System.getProperty("test.integration.dependency.cache.dir", "build/test-dependency-cache"))

    /**
     * Bind mounts that persist the container's `~/.m2` and `~/.gradle` to host dirs under
     * [dependencyCacheDir]. The host dirs are made world-writable: Linux bind mounts do not remap UIDs, so
     * the in-container `agent` (uid 1000) must be able to write them (see [allocRunDirAndTitle]). `agent`'s
     * home is `/home/agent` (see the ide-base Dockerfile).
     */
    fun dependencyCacheVolumes(): List<ContainerVolume> =
        mapOf(
            "/home/agent/.m2" to dependencyCacheDir.resolve("m2"),
            "/home/agent/.gradle" to dependencyCacheDir.resolve("gradle"),
        ).map { (guest, host) ->
            require(host.isDirectory || host.mkdirs()) { "Could not create dependency cache dir: $host" }
            ContainerVolume(host.makeContainerWritable(), guest, "rw")
        }

    val testOutputDir = remapPathForDockerHost(
        readFilePathFromSystemProperties("test.integration.testOutput"),
        System.getenv(DOCKER_HOST_PATH_MAP_ENV),
    ).also { mapped ->
        if (!mapped.exists()) {
            mapped.mkdirs()
        }

        val configured = System.getProperty("test.integration.testOutput")
        if (configured != null && mapped.absolutePath != File(configured).absolutePath) {
            println("[IDE-AGENT] test.integration.testOutput remapped for Docker mounts: $configured -> ${mapped.absolutePath}")
        }
    }

    fun copyDockerFiles(containerName: String, destinationDir: File) {
        val sourcePath = dockerDir.resolve(containerName)
        require(sourcePath.exists()) { "Directory $containerName already exists" }
        copyRecursively(sourcePath, destinationDir)
    }

    fun copyProjectFiles(containerName: String, destinationDir: File) {
        val sourcePath = dockerDir.resolve(containerName)
        require(sourcePath.exists()) { "Directory $containerName already exists" }
        copyRecursively(sourcePath, destinationDir)
    }
}

fun copyRecursively(source: File, destination: File) {
    if (source.isFile) {
        destination.parentFile.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        destination.setLastModified(source.lastModified())
        return
    }

    destination.mkdirs()

    val sourceFiles = source.listFiles() ?: error("Failed to list directory $source")

    sourceFiles.forEach { sourceFile ->
        copyRecursively(sourceFile, destination.resolve(sourceFile.name))
    }
}

/**
 * Thrown from a [waitFor] action to stop polling immediately: a known terminal problem was observed (the
 * awaited condition can never be reached), so retrying until the timeout is pointless. Extends [Error] so
 * it is never confused with the transient [Exception]s [waitFor] swallows-and-retries; [waitFor] catches
 * it explicitly and rethrows.
 */
class WaitAbortedException(message: String, cause: Throwable? = null) : Error(message, cause)

fun waitFor(timeoutMillis: Long, condition: String = "condition", action: () -> Boolean) {
    println("Waiting $condition for $timeoutMillis ms...")
    val now = System.currentTimeMillis()
    Thread.sleep(50)
    var lastException: Exception? = null
    while (System.currentTimeMillis() - now < timeoutMillis) {
        try {
            if (action()) return
            lastException = null
        } catch (e: WaitAbortedException) {
            // Known terminal problem — the wait can never succeed. Stop now instead of polling to timeout.
            throw e
        } catch (e: Exception) {
            lastException = e
        }
        Thread.sleep(50)
    }
    val elapsed = System.currentTimeMillis() - now
    val msg = buildString {
        append("Failed waiting for $condition after ${elapsed}ms!")
        val exc = lastException
        if (exc != null) append(" Last error: ${exc.message}")
    }
    throw RuntimeException(msg, lastException)
}

fun <T : Any> waitForValue(timeoutMillie: Long, condition: String = "condition", action: () -> T?): T {
    var value: T? = null
    waitFor(timeoutMillie, condition) {
        value = action()
        value != null
    }
    return value ?: throw RuntimeException("Failed waiting for $condition!")
}

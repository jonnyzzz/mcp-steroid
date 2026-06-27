/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    // If the path is under a bind mount, create it on the HOST first so the directory is host-owned.
    // Creating it only via in-container `mkdir` makes it container-owned, after which the host JVM
    // cannot add subdirectories under it — which broke writeTrustedPaths with a FileNotFoundException
    // on `ide-config/options/trusted-paths.xml` (the host-mapped copyToContainer write could not create
    // the `options/` dir inside the container-owned `ide-config`). The in-container `mkdir -p` below then
    // no-ops on the now-existing directory, leaving ownership with the host.
    mapGuestPathToHostPathOrNull(guestPath)?.mkdirs()
    return startProcessInContainer {
        this
            .args("mkdir", "-p", guestPath)
            .description("Create directory $guestPath in the container")
            .quietly()
    }.awaitForProcessFinish()
}

fun ContainerDriver.copyFromContainer(containerPath: String, localPath: File) {
    localPath.parentFile?.mkdirs()
    // Direct host access when the guest path is under a bind mount — no `docker cp` process.
    mapGuestPathToHostPathOrNull(containerPath)?.let { hostPath ->
        require(hostPath.exists()) { "Mapped host path does not exist for $containerPath: $hostPath" }
        hostPath.copyTo(localPath, overwrite = true)
        return
    }
    newRunOnHost()
        .command("docker", "cp", "$containerId:$containerPath", localPath.absolutePath)
        .description("Copy container:$containerPath to ${localPath.name}")
        .timeoutSeconds(300L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy from container: $containerPath to $localPath: $stderr" }
}

fun ContainerDriver.copyToContainer(localPath: File, containerPath: String) {
    require(localPath.exists()) { "Local path does not exist: $localPath" }
    // Direct host access when the guest path is under a bind mount — no `docker cp` process.
    mapGuestPathToHostPathOrNull(containerPath)?.let { hostPath ->
        hostPath.parentFile?.mkdirs()
        localPath.copyTo(hostPath, overwrite = true)
        return
    }
    newRunOnHost()
        .command("docker", "cp", localPath.absolutePath, "$containerId:$containerPath")
        .description("Copy ${localPath.name} to container:$containerPath")
        .timeoutSeconds(120L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
}

/**
 * Read the text content of [containerPath] into the test JVM. Implemented over [copyFromContainer] —
 * the file is staged to a host temp file then read — so there is no content-size limit and it
 * transparently uses direct host-filesystem access when the path is under a bind mount (no
 * `docker exec`/`cat`). Prefer this over a shell `grep`/`cat` exec when a test needs to assert on a
 * container file's content: the assertions run in Kotlin with clear failure messages instead of a
 * cryptic non-zero exit code.
 */
fun ContainerDriver.readFromContainer(containerPath: String): String {
    val tmp = File.createTempFile("read-from-container-", ".tmp")
    try {
        copyFromContainer(containerPath, tmp)
        return tmp.readText()
    } finally {
        tmp.delete()
    }
}

/**
 * Write [content] to [containerPath]. Implemented over [copyToContainer] — a host temp file is
 * staged then copied in — so there is no content-size limit and it transparently uses direct
 * host-filesystem access when the path is under a bind mount (no `docker exec`/heredoc).
 */
fun ContainerDriver.writeFileInContainer(
    containerPath: String,
    content: String,
    executable: Boolean = false,
) {
    val tmp = File.createTempFile("write-in-container-", ".tmp")
    try {
        tmp.writeText(content)
        // Set the exec bit on the source: `docker cp` preserves file mode, so the container-local
        // target ends up executable without a separate (ownership-sensitive) chmod.
        if (executable) tmp.setExecutable(true, false)
        // `docker cp` (container-local target) needs the parent dir to exist; the host-mapped
        // branch of copyToContainer creates the host parent itself.
        val parentDir = containerPath.substringBeforeLast('/')
        if (parentDir.isNotEmpty() && mapGuestPathToHostPathOrNull(containerPath) == null) {
            mkdirs(parentDir).assertExitCode(0) { "Failed to create directory in container $parentDir: $stderr" }
        }
        copyToContainer(tmp, containerPath)
        // Host-mapped copyTo does NOT carry the exec bit; set it on the host destination directly
        // (the file is host-owned there, so a docker-exec chmod by the container user would fail).
        if (executable) mapGuestPathToHostPathOrNull(containerPath)?.setExecutable(true, false)
    } finally {
        tmp.delete()
    }
}

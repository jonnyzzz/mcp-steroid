/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

/**
 * Create this directory (and any missing parents) on the host, marking every newly created level
 * world readable/writable/executable (a+rwx).
 *
 * The IDE container runs as the image's baked-in `agent` uid (1000) while the host (e.g. the CI agent,
 * uid 999) is a DIFFERENT uid and `docker run` is deliberately invoked without `--user`. Per the run-dir
 * bind-mount contract (see run-dir.kt and the notes in docker-container-start.kt), any directory under
 * the run mount that the container must write into has to be a+rwx — otherwise a host-side `mkdir`
 * leaves it host-owned with mode 755 and the uid-1000 IDE cannot write into it (which manifested as the
 * MCP Steroid plugin never reaching ready: its config/log writes silently failed).
 */
private fun File.mkdirsWorldWritable() {
    if (exists()) return
    val createdLevels = generateSequence(this) { it.parentFile }.takeWhile { !it.exists() }.toList()
    mkdirs()
    for (dir in createdLevels) {
        dir.setReadable(true, /* ownerOnly = */ false)
        dir.setWritable(true, /* ownerOnly = */ false)
        dir.setExecutable(true, /* ownerOnly = */ false)
    }
}

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    // If the path is under a bind mount, create it on the HOST first so the host JVM can later add
    // subdirectories under it (an in-container `mkdir` would make it container-owned, after which a
    // host-mapped write could not create child dirs — that broke writeTrustedPaths with a
    // FileNotFoundException on `ide-config/options/trusted-paths.xml`). Created levels are made a+rwx so
    // the uid-1000 container can still write into them; the in-container `mkdir -p` below then no-ops.
    mapGuestPathToHostPathOrNull(guestPath)?.mkdirsWorldWritable()
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
        // Make the parent dir(s) a+rwx so the uid-1000 container can write alongside the host-written file.
        hostPath.parentFile?.mkdirsWorldWritable()
        localPath.copyTo(hostPath, overwrite = true)
        // The container runs as the image's uid (1000), the host as a different uid — so make the
        // host-written file group/other-writable too. Otherwise a host-owned 0644 file the IDE needs to
        // REWRITE on startup (e.g. options/*.xml it loads then persists) fails with EACCES inside the
        // container, stalling IDE/plugin startup. a+rw matches the run-dir mount contract.
        hostPath.setReadable(true, /* ownerOnly = */ false)
        hostPath.setWritable(true, /* ownerOnly = */ false)
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

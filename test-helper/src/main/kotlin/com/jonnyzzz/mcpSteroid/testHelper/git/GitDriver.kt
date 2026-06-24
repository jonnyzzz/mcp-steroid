/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/**
 * Reusable Git operations for Docker containers.
 * Works with any [com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver] instance.
 */
//TODO: We need to cache repositories on the host machine as bare checkout to make deployment faster
class GitDriver(
    private val driver: ContainerDriver,
) {
    /**
     * Clone a git repository into [targetDir] inside the container.
     * Creates the parent directory if needed.
     *
     * @param repoUrl repository URL (https, ssh, or file)
     * @param targetDir guest path for the cloned repository
     * @param shallow use `--depth 1` for a shallow clone (default true)
     * @param timeoutSeconds timeout for the clone operation
     */
    fun clone(
        repoUrl: String,
        targetDir: String,
        shallow: Boolean = true,
        timeoutSeconds: Long = 300,
    ) {
        // Ensure parent directory exists
        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        val args = mutableListOf("git", "clone")
        if (shallow) {
            args += listOf("--depth", "1")
        }
        args += listOf(repoUrl, targetDir)

        println("[GIT] Cloning $repoUrl into $targetDir (shallow=$shallow)...")
        driver.startProcessInContainer {
            this
                .args(args)
                .timeoutSeconds(timeoutSeconds)
                .description("git clone $repoUrl into $targetDir")
        }.assertExitCode(0) { "git clone $repoUrl failed" }
    }

    /**
     * Checkout a specific commit or ref in a repository.
     */
    fun checkout(repoDir: String, ref: String) {
        println("[GIT] Checking out $ref in $repoDir...")
        driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "checkout", ref)
                .timeoutSeconds(30)
                .description("git checkout $ref in $repoDir")
        }.assertExitCode(0) { "git checkout $ref failed" }
    }

    /**
     * Try to clone a repository from a host-side bare cache mounted inside the container.
     *
     * Checks whether `{cacheGuestPath}/{ownerAndRepo}.git` exists in the container.
     * If it does, clones from it using a fast `file://` local clone.
     * If it does not, returns false so the caller can fall back to a remote clone.
     *
     * @param ownerAndRepo repo identifier without `.git`, e.g. `"dpaia/feature-service"`
     * @param targetDir guest path for the cloned repository
     * @param cacheGuestPath guest path where the host repo cache is mounted (default `/repo-cache`)
     * @return true if cloned from cache; false if the bare repo was not found in the cache
     */
    fun cloneFromCachedBare(
        ownerAndRepo: String,
        targetDir: String,
        cacheGuestPath: String = "/repo-cache",
    ): Boolean {
        val bareGuestPath = "$cacheGuestPath/$ownerAndRepo.git"

        val check = driver.startProcessInContainer {
            this
                .args("test", "-d", bareGuestPath)
                .timeoutSeconds(5)
                .quietly()
                .description("test -d $bareGuestPath")
        }.awaitForProcessFinish()
        if (check.exitCode != 0) {
            println("[GIT] No cached bare repo at $bareGuestPath, will clone from remote")
            return false
        }

        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        // Bypass git's "dubious ownership" check by seeding the safe.directory
        // whitelist in the container's ~/.gitconfig BEFORE the clone. Observed
        // on TC CI:
        //   fatal: detected dubious ownership in repository at
        //     '/repo-cache/dpaia/feature-service.git'
        //   git config --global --add safe.directory /repo-cache/dpaia/…
        //
        // Root cause: the /repo-cache bind mount is owned by the host TC-agent
        // user (uid e.g. 999) while git inside the container runs as `agent`
        // (uid 1000). Linux bind mounts don't do UID remapping, so git refuses
        // without an explicit safe.directory entry.
        //
        // `-c safe.directory=<x>` on the clone command itself was tried first
        // (both `*` wildcard and the specific path, belt-and-suspenders) and
        // did NOT work on the TC agents' git version — the in-memory config
        // set by `-c` apparently isn't honored for the owner check on some
        // git builds. Running `git config --global --add` in a separate exec
        // persists the setting to ~/.gitconfig inside the ephemeral container,
        // which the subsequent clone picks up reliably. The container's home
        // is wiped when it's torn down so this doesn't leak anywhere.
        println("[GIT] Registering safe.directory=$bareGuestPath in container ~/.gitconfig")
        driver.startProcessInContainer {
            this
                .args("git", "config", "--global", "--add", "safe.directory", bareGuestPath)
                .timeoutSeconds(5)
                .quietly()
                .description("git config safe.directory for $bareGuestPath")
        }.awaitForProcessFinish().assertExitCode(0, "git config safe.directory")

        println("[GIT] Cloning from bare cache: $bareGuestPath -> $targetDir ...")
        driver.startProcessInContainer {
            this
                .args("git", "clone", "file://$bareGuestPath", targetDir)
                .timeoutSeconds(120)
                .description("git clone from bare cache $bareGuestPath")
        }.awaitForProcessFinish().assertExitCode(0, "git clone from bare cache $bareGuestPath")

        return true
    }

    /**
     * Apply a patch to a repository using `git apply`.
     *
     * @param repoDir guest path of the repository
     * @param patchContent the patch text (unified diff format)
     */
    fun applyPatch(repoDir: String, patchContent: String) {
        if (patchContent.isBlank()) {
            println("[GIT] No patch to apply")
            return
        }

        val patchPath = "/tmp/_tmp_patch_${System.currentTimeMillis()}.diff"
        println("[GIT] Applying patch to $repoDir...")
        // Ensure patch ends with a trailing newline — git apply requires it,
        // but some dataset entries omit the final newline causing "corrupt patch" errors.
        val normalizedPatch = if (patchContent.endsWith("\n")) patchContent else patchContent + "\n"
        driver.writeFileInContainer(patchPath, normalizedPatch, executable = false)

        driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "apply", "--allow-empty", patchPath)
                .timeoutSeconds(30)
                .description("git apply patch in $repoDir")
        }.awaitForProcessFinish().assertExitCode(0, "git apply patch")
    }
}

package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.deployZipAndUnpack
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.streams.asSequence
import kotlinx.coroutines.runBlocking

/**
 * devrig's home is hardcoded to `~/.mcp-steroid` (NOT overridable — see `docs/devrig-deployment-spec.md`,
 * and note that even with the `DEVRIG_HOME` override, the backend markers always stay under
 * `~/.mcp-steroid/markers`). So in the container the home is always `/home/agent/.mcp-steroid` and we never
 * set `DEVRIG_HOME`. Its `logs` dir is bind-mounted to the run-dir and its `downloads` dir to the shared
 * IDE-archive cache (see [create]); the rest of the home stays inside the container.
 */
const val DEVRIG_GUEST_HOME: String = "/home/agent/.mcp-steroid"
const val DEVRIG_GUEST_LOGS_DIR: String = "$DEVRIG_GUEST_HOME/logs"
const val DEVRIG_GUEST_DOWNLOADS_DIR: String = "$DEVRIG_GUEST_HOME/downloads"

data class DevrigContainerOpts(
    val consoleTitle: String,
    val dockerFileBase: String = "managed-backend-host",
    val layoutManager: LayoutManager = HorizontalLayoutManager(),
    /**
     * Mount the host bare-repo cache ([IdeTestFolders.repoCacheDir]) read-only at `/repo-cache` so a test
     * can clone a large project (e.g. Keycloak) from a local `file://` bare repo via
     * [com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver.cloneFromCachedBare] instead of a full network fetch.
     */
    val mountRepoCache: Boolean = false,
    /**
     * Persist Maven and Gradle dependency caches across containers. This mounts only `/home/agent/.m2` and
     * `/home/agent/.gradle`; devrig's backend markers and configuration under [DEVRIG_GUEST_HOME] remain
     * container-local so every scenario still starts with clean devrig state.
     */
    val mountDependencyCaches: Boolean = false,
)

class DevrigContainer(
    val scope: ContainerDriver,
    val gui: GuiContainer,
    val devrig: String,
    /** Host run directory for this container (logs, screenshots, video, agent NDJSON). */
    val runDir: File,
) {
    val console: ConsoleDriver by gui::console

    companion object

    fun execAndAssert(description: String,
                              script: String,
                              timeoutSeconds: Long = 300,
    ) = execAndAssert(scope, description, script, timeoutSeconds)

    /**
     * Runs a devrig script inside this container while live-streaming stdout/stderr
     * to the on-video xterm. The full transcript is still returned for assertions.
     */
    fun execAndAssertWithConsoleStream(
        description: String,
        script: String,
        timeoutSeconds: Long = 120,
    ): ProcessResult {
        // A per-command STEP, not a full header banner — the test writes the single header for the whole run.
        console.writeStep(description)

        val wrappedScript = """
        set -euo pipefail
        (
        ${script.trimIndent()}
        ) 2>&1
    """.trimIndent()

        val streamedStdoutLines = AtomicInteger(0)
        val streamedStderrLines = AtomicInteger(0)
        val process = scope.startProcessInContainer {
            args("bash", "-lc", wrappedScript)
                .timeoutSeconds(timeoutSeconds)
                .description(description)
        }

        val streamer = thread(
            start = true,
            isDaemon = true,
            name = "devrig-console-stream-${System.nanoTime()}",
        ) {
            try {
                runBlocking {
                    process.messagesFlow.collect { line ->
                        when (line.type) {
                            ProcessStreamType.STDOUT -> {
                                streamedStdoutLines.incrementAndGet()
                                console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} ${line.line}")
                            }

                            ProcessStreamType.STDERR -> {
                                streamedStderrLines.incrementAndGet()
                                console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} ${line.line}")
                            }

                            ProcessStreamType.INFO -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to stream devrig output to the visible console: ${e.message}")
                console.writeError("Failed to stream devrig output: ${e.message}")
            }
        }

        val result = process.awaitForProcessFinish()
        try {
            streamer.join(3_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            System.err.println("Interrupted while waiting for devrig console streamer: ${e.message}")
        }

        result.stdout.lineSequence()
            .drop(streamedStdoutLines.get())
            .filter { line -> line.isNotEmpty() }
            .forEach { line -> console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} $line") }
        result.stderr.lineSequence()
            .drop(streamedStderrLines.get())
            .filter { line -> line.isNotEmpty() }
            .forEach { line -> console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} $line") }

        return result.assertExitCode(0) { "$description failed\nstdout=$stdout\nstderr=$stderr" }
    }
}

/**
 * Continuously tee devrig's DEBUG log file(s) to the on-video [console] for the rest of the test —
 * the same JVM-side approach used to pump the IntelliJ `idea.log` (a daemon thread polling a
 * host-mapped file with a buffered reader), NOT a `docker exec tail`.
 *
 * devrig ALWAYS writes DEBUG logs to files, so this works regardless of how devrig was launched —
 * including when it is started ONLY as the `mpc` MCP server (the agent's `Bash` tool buffers a
 * command's stdout until it finishes, so the log file is the only live window into devrig's activity).
 *
 * [hostLogsDir] must be the HOST path of devrig's `logs` dir (devrig's `DEVRIG_HOME/logs` placed under a
 * bind-mounted dir, so the JVM can read the files directly). New session files are detected as they
 * appear — every devrig process writes its own `devrig-<ts>-pid<PID>.log`, and each line carries the PID
 * (so interleaved output from multiple devrig processes stays attributable).
 */
fun streamDevrigLogsToConsole(lifetime: CloseableStack, hostLogsDir: File, console: ConsoleDriver) {
    val realLogsRoot = hostLogsDir.toPath().toRealPath()
    require(Files.isDirectory(realLogsRoot, LinkOption.NOFOLLOW_LINKS)) {
        "devrig log root is not a real directory: $realLogsRoot"
    }
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    val followed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val followers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
    val warnedUnsafe = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val logName = Regex("""devrig-.*\.log""")

    fun warnUnsafe(path: Path, reason: String) {
        if (warnedUnsafe.add(path.toString())) {
            System.err.println("devrig log scanner rejected $path: $reason")
        }
    }

    fun follow(file: Path) {
        val follower = thread(start = false, isDaemon = true, name = "devrig-log-tee-${file.fileName}") {
            try {
                Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val reader = Channels.newReader(channel, StandardCharsets.UTF_8).buffered()
                    while (!stopped.get()) {
                        val line = reader.readLine()
                        if (line == null) {
                            Thread.sleep(150)
                            continue
                        }
                        if (line.isNotEmpty()) {
                            console.writeLine("${ConsoleDriver.CYAN}[devrig-log]${ConsoleDriver.RESET} $line")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                System.err.println("devrig log tee for ${file.fileName} was interrupted (teardown=${stopped.get()})")
            } catch (e: Exception) {
                System.err.println("devrig log tee for ${file.fileName} stopped (teardown=${stopped.get()}): ${e.message}")
            }
        }
        followers += follower
        follower.start()
    }

    val scanner = thread(start = true, isDaemon = true, name = "devrig-log-tee-scan") {
        try {
            while (!stopped.get()) {
                try {
                    Files.list(realLogsRoot).use { paths ->
                        paths.forEach { candidate ->
                            if (!logName.matches(candidate.fileName.toString())) return@forEach
                            try {
                                val normalized = candidate.toAbsolutePath().normalize()
                                if (normalized.parent != realLogsRoot) {
                                    warnUnsafe(candidate, "path escapes the real log root")
                                    return@forEach
                                }
                                if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                                    warnUnsafe(candidate, "not a no-follow regular file")
                                    return@forEach
                                }
                                val resolved = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS)
                                if (resolved.parent != realLogsRoot || !resolved.startsWith(realLogsRoot)) {
                                    warnUnsafe(candidate, "resolved path escapes the real log root")
                                    return@forEach
                                }
                                if (followed.add(resolved.toString())) follow(resolved)
                            } catch (e: Exception) {
                                System.err.println("devrig log scanner failed to inspect $candidate: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("devrig log scan iteration failed and will retry: ${e.message}")
                }
                Thread.sleep(1_000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            System.err.println("devrig log scan was interrupted (teardown=${stopped.get()})")
        } catch (e: Exception) {
            System.err.println("devrig log scan error: ${e.message}")
        }
    }
    lifetime.registerCleanupAction {
        stopped.set(true)
        joinDevrigLogThread(scanner, 2_500)
        followers.toList().forEach { follower -> joinDevrigLogThread(follower, 1_500) }
    }
}

private fun joinDevrigLogThread(thread: Thread, timeoutMillis: Long) {
    try {
        thread.join(timeoutMillis)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        System.err.println("interrupted while joining ${thread.name} during devrig log teardown: ${e.message}")
        throw IllegalStateException("interrupted while joining ${thread.name}", e)
    }
    check(!thread.isAlive) { "devrig log thread did not stop within ${timeoutMillis}ms: ${thread.name}" }
}

fun devrigContainerVolumes(runDir: File, opts: DevrigContainerOpts): List<ContainerVolume> = buildList {
    add(ContainerVolume(runDir, "/mcp-run-dir", "rw"))
    if (opts.mountRepoCache) {
        // repoCacheDir mkdirs()-es itself and errors if `test.integration.repo.cache.dir` is unset,
        // so the mount source always exists (no silent skip on a missing dir).
        add(ContainerVolume(IdeTestFolders.repoCacheDir, "/repo-cache", "ro"))
    }
    if (opts.mountDependencyCaches) {
        addAll(IdeTestFolders.dependencyCacheVolumes())
    }

    // ALWAYS bind-mount ONLY devrig's logs dir out to THIS RUN's folder (`<runDir>/devrig-logs`) — never
    // to the host's real `~/.mcp-steroid` (would trigger a macOS trust prompt and pollute the user's
    // home), and never the whole home (the multi-GB downloaded IDE + caches would make a bind mount far
    // too slow). The JVM-side monitor ([streamDevrigLogsToConsole]) tails these files directly.
    val hostLogs = File(runDir, "devrig-logs").also {
        it.mkdirs()
        // a+rwx so the in-container `agent` (uid 1000) can write log files through the bind mount
        // on Linux CI (no UID remap); macOS virtiofs maps the uid transparently.
        it.setReadable(true, false); it.setWritable(true, false); it.setExecutable(true, false)
    }
    add(ContainerVolume(hostLogs, DEVRIG_GUEST_LOGS_DIR, "rw"))

    // Bind-mount the shared host IDE-archive cache ([IdeTestFolders.ideDownloadDir], the same dir the
    // IDE-image download uses) RW at devrig's downloads dir. The first managed-backend run downloads the
    // ~1.5GB IDE archive into it; subsequent runs (and the IDE-image build) reuse it via IdeDownloader's
    // skip-if-exists. RW so the cache self-populates. IdeDownloader names archives `<hash>-<filename>`
    // (hash of URL + checksum), so Community and Ultimate — which BOTH ship as `idea-<version>.tar.gz` —
    // never collide in this shared dir.
    val ideDownloadsCache = IdeTestFolders.ideDownloadDir.also {
        it.mkdirs()
        // a+rwx (same as devrig-logs above) so the in-container `agent` (uid 1000) can create the
        // `.tmp` download file through the bind mount on Linux CI (no UID remap). Must run even when
        // the dir pre-exists: the host-side IDE-image download creates it first with default 0755
        // (intelliJ-download.kt), which leaves it read-only for uid 1000 -> devrig's backend download
        // fails with FileNotFoundException (Permission denied) and exit code 64.
        it.setReadable(true, false); it.setWritable(true, false); it.setExecutable(true, false)
    }
    add(ContainerVolume(ideDownloadsCache, DEVRIG_GUEST_DOWNLOADS_DIR, "rw"))
}

fun DevrigContainer.Companion.create(lifetime: CloseableStack, opts: DevrigContainerOpts) : DevrigContainer {
    val (runDir, realConsoleTitle) = allocRunDirAndTitle(lifetime, opts.consoleTitle)

    val imageId = run {
        // Unique suffix ensures parallel test runs each builds their own image and context dir,
        // preventing races in buildIdeImage when multiple tests start concurrently.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val imageName = "${opts.dockerFileBase}-test-$uniqueSuffix"
        buildDevrigImage(opts.dockerFileBase, imageName)
    }

    val containerMountedPath = "/mcp-run-dir"

    val volumes = devrigContainerVolumes(runDir, opts)

    val containerEnv = buildMap {
        // Every devrig process in this container is debuggable: DEVRIG_DEBUG makes the devrig start script
        // pick a FREE, PID-seeded JDWP port from the published 23900-23999 range, so the concurrent devrig
        // processes a managed-backend test spawns (mpc + backend download/start + the agent's CLI calls)
        // never clash on a port. quiet=y/suspend=n keep stdout clean and never block. See bin/devrig.
        // We do NOT set DEVRIG_HOME — devrig's home is always `~/.mcp-steroid` (= DEVRIG_GUEST_HOME).
        put("DEVRIG_DEBUG", "1")
    }

    var container: ContainerDriver = startDockerContainerAndDispose(
        lifetime,
        StartContainerRequest()
            .image(imageId)
            .enableInit()
            .extraEnvVars(containerEnv)
            .volumes(volumes)
            .ports(
                buildList {
                    add(XcvbVideoDriver.VIDEO_STREAMING_PORT)
                    add(McpSteroidDriver.MCP_STEROID_PORT)
                    add(IDE_DEBUG_PORT)
                    add(DEVRIG_DEBUG_PORT_RANGE)
                },
            ),
    )

    val gui = setupGuiContainerServices(lifetime, container, opts.layoutManager, containerMountedPath, realConsoleTitle)
    val console = gui.console
    container = gui.container

    console.writeInfo("Preparing devrig...")

    val devrigLauncher = deployDevrigLauncher(container)
    val driver = DevrigContainer(
        container,
        gui,
        devrigLauncher,
        runDir,
    )

    return driver
}

private fun deployDevrigLauncher(scope: ContainerDriver, packageZip: File = IdeTestFolders.devrigPackageZip): String {
    require(packageZip.isFile) { "devrig distribution ZIP does not exist: ${packageZip.absolutePath}" }

    val targetDir = "/home/agent/devrig-cli"
    scope.deployZipAndUnpack(packageZip, targetDir)

    val relPath = ZipFile(packageZip).use { zip ->
        val allFiles by lazy {
            zip.stream().asSequence().filter { !it.isDirectory }.map { it.name }.toSortedSet().joinToString("\n")
        }
        zip.stream().asSequence().singleOrNull { it.name.endsWith("/bin/devrig") }?.name ?: error(
            "Failed to resolve the devrig.sh in the package:\n$allFiles"
        )
    }

    return targetDir.trimEnd('/') + "/" + relPath.trimStart('/')
}

private fun execAndAssert(
    scope: ContainerDriver,
    description: String,
    script: String,
    timeoutSeconds: Long = 300,
): ProcessResult {
    return scope.startProcessInContainer {
        this
            .args("bash", "-lc", script)
            .timeoutSeconds(timeoutSeconds)
            .description(description)
    }.awaitForProcessFinish()
        .assertExitCode(0) { "$description failed\nstdout=$stdout\nstderr=$stderr" }
}

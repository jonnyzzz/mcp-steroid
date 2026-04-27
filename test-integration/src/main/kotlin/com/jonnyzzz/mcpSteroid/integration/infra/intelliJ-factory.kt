/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.Set
import kotlin.collections.asSequence
import kotlin.collections.buildMap
import kotlin.collections.buildList
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.getOrPut
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.linkedSetOf
import kotlin.collections.maxByOrNull
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.orEmpty
import kotlin.collections.setOf
import kotlin.collections.sorted

//TODO: refactor parameters to a builder object, so we can update easily in the future, add "registerMcpSteroidToAgents" to the builder
fun IntelliJContainer.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String = "ide-agent",
    consoleTitle: String,
    project : IntelliJProject = IntelliJProject.TestProject,
    layoutManager : LayoutManager = HorizontalLayoutManager(),
    distribution: IdeDistribution = IdeDistribution.fromSystemProperties(),
    aiMode: AiMode = AiMode.AI_MCP,
    /**
     * Explicit MCP connection mode for AI agents.
     *
     * When provided, takes precedence over [aiMode] for MCP connectivity.
     * Use [McpConnectionMode.None] to create a container where agents have NO MCP registered
     * (baseline / control group) while still producing real-time console output and log files.
     *
     * When null (default), the mode is derived from [aiMode].
     */
    mcpConnectionMode: McpConnectionMode? = null,
    repoCacheDir: File? = IdeTestFolders.repoCacheDirOrNull,
    /**
     * When true, mounts the host Docker socket (`/var/run/docker.sock`) into the container
     * at the same path so Testcontainers-based tests can start sibling Docker containers.
     *
     * Requirements:
     * - The host Docker socket must exist at `/var/run/docker.sock`
     * - The `ide-base` Docker image must have `docker-ce-cli` installed (already done) and
     *   the `agent` user must be in the `docker` group (already done in the Dockerfile)
     *
     * Default: `false` (Docker socket not mounted — arena tests that use Testcontainers
     * will fail with "Could not find a valid Docker environment" unless this is enabled).
     */
    mountDockerSocket: Boolean = false,
    /**
     * When true, forwards the host SSH agent socket into the container and sets SSH_AUTH_SOCK.
     * Required for git operations that use SSH remotes/private keys from inside the container.
     */
    mountSshAgent: Boolean = true,
    /**
     * Optional prebuilt image to start from (for warm snapshot reuse).
     * When provided, IDE archive download/build is skipped and this image is used directly.
     */
    sourceImage: ImageDriver? = null,
    /**
     * Reuse project sources from [sourceImage] instead of re-deploying project files/clone.
     * Use together with warm snapshot images that already contain project checkout + ide-system.
     */
    reuseProjectFromImage: Boolean = false,
    /**
     * Default true keeps ordinary Docker tests immune to trust prompts. Tests that validate
     * project-trust behavior can set this false and rely on explicit trusted paths.
     */
    disableProjectTrustChecks: Boolean = true,
    /**
     * Default true mirrors the historical test image setup that trusts every path. Tests that
     * need an actually-untrusted secondary project can set this false.
     */
    trustAllProjectPaths: Boolean = true,
): IntelliJContainer {
    val ideProduct = distribution.product
    val selectedDockerBase = if (dockerFileBase == "ide-agent") ideProduct.dockerImageBase else dockerFileBase
    val selectedProject = when {
        project != IntelliJProject.TestProject -> project
        ideProduct == IdeProduct.PyCharm -> IntelliJProject.PyCharmTestProject
        ideProduct == IdeProduct.GoLand -> IntelliJProject.GoLandTestProject
        ideProduct == IdeProduct.WebStorm -> IntelliJProject.WebStormTestProject
        ideProduct == IdeProduct.Rider -> IntelliJProject.RiderTestProject
        else -> project
    }

    val (runDir, realConsoleTitle) = run {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runIdName = consoleTitle.split(" ").joinToString("-") { it.lowercase() }
        val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runIdName}")
        file.mkdirs()
        // Make the runDir world-writable on the HOST before we bind-mount it at
        // /mcp-run-dir inside the container. Linux bind mounts do not do UID
        // remapping: whatever host uid owns the dir controls write access from
        // inside the container. On TC Linux agents the dir is created by the
        // TC-agent user (uid e.g. 999), but the container process runs as
        // `agent` (uid 1000) — EACCES on every `mkdir /mcp-run-dir/<subdir>`.
        // On macOS/Docker Desktop the virtiofs / osxfs layer does this mapping
        // transparently, which is why the same code works locally but fails on
        // CI with: `mkdir -p /mcp-run-dir/video → exit 1` followed eventually
        // by `docker cp: Could not find the file /mcp-run-dir/intellij/temp`.
        //
        // `setWritable(true, ownerOnly=false)` maps to chmod a+w on Linux.
        // Add read+execute too via setReadable / setExecutable so the
        // container user can traverse the tree and read existing files.
        file.setReadable(true, /* ownerOnly = */ false)
        file.setWritable(true, /* ownerOnly = */ false)
        file.setExecutable(true, /* ownerOnly = */ false)
        file to "$consoleTitle $timestamp"
    }

    println("[IDE-AGENT] Run directory: $runDir")
    // Register a cleanup action that emits the TC artifact-publish service
    // message AT TEARDOWN, once the run directory has been populated with
    // session-info.txt, IDE logs, agent NDJSON, decoded logs, screenshots
    // and the video recording. Emitting it at creation-time was a no-op on
    // TC because `publishArtifacts` is processed immediately and found the
    // runDir empty ("Artifacts path … not found" warning). Registering
    // a cleanup action ensures the spec is emitted on every close path:
    // test pass, test fail, or exception during setup.
    lifetime.registerCleanupAction {
        TeamCityServiceMessages.publishRunDirArtifact(runDir)
    }
    val imageId = sourceImage ?: run {
        val ideArchive = distribution.resolveAndDownload()
        // Unique suffix ensures parallel test runs each build their own image and context dir,
        // preventing races in buildIdeImage when multiple tests start concurrently.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val imageName = "$selectedDockerBase-test-$uniqueSuffix"
        buildIdeImage(selectedDockerBase, imageName, ideArchive)
    }
    if (sourceImage != null) {
        println("[IDE-AGENT] Using prebuilt image: ${sourceImage.imageIdToLog}")
    }

    val containerMountedPath = "/mcp-run-dir"

    fun readHostCredential(propertyName: String, vararg envNames: String): String? {
        val fromProperty = System.getProperty(propertyName)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (fromProperty != null) return fromProperty
        return envNames
            .asSequence()
            .mapNotNull { envName ->
                System.getenv(envName)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    val hostHomeDir = File(System.getProperty("user.home"))
    val dockerSocketFile = File("/var/run/docker.sock")
    val sshAgentHostMountPath = "/tmp/host-ssh-agent.sock"
    val sshAgentGuestPath = "/tmp/ssh-agent.sock"
    val hostNetrcGuestPath = "/tmp/host-netrc"
    val hostNetrcFile = File(hostHomeDir, ".netrc").takeIf { it.isFile }
    val hostM2SettingsGuestPath = "/tmp/host-m2-settings.xml"
    val hostM2SettingsFile = File(hostHomeDir, ".m2/settings.xml").takeIf { it.isFile }
    val hostJetBrainsTokensGuestPath = "/tmp/host-jb-tokens"
    val hostJetBrainsTokensDir = File(hostHomeDir, ".jb/tokens").takeIf { it.isDirectory }
    val hostJetBrainsTeamTokenGuestPath = "/tmp/host-jb-jetbrains-team-token.json"
    val hostJetBrainsTeamTokenFile = File(hostHomeDir, ".jb/tokens/jetbrains.team.json").takeIf { it.isFile }
    val hostPrivatePackagesAuthCacheGuestPath = "/tmp/host-private-packages-authorizer-cache"
    val hostPrivatePackagesAuthCacheDir = sequenceOf(
        File(hostHomeDir, "Library/Caches/JetBrains/private-packages-authorizer"),
        File(hostHomeDir, ".cache/JetBrains/private-packages-authorizer"),
    ).firstOrNull { it.isDirectory }
    val hostPackagesClientId = readHostCredential(
        "test.integration.intellij.packages.client.id",
        "JB_SPACE_CLIENT_ID",
        "TEST_INTEGRATION_JB_SPACE_CLIENT_ID",
    )
    val hostPackagesClientSecret = readHostCredential(
        "test.integration.intellij.packages.client.secret",
        "JB_SPACE_CLIENT_SECRET",
        "TEST_INTEGRATION_JB_SPACE_CLIENT_SECRET",
    )
    val hasPackagesEnvCredentials = hostPackagesClientId != null && hostPackagesClientSecret != null
    val sshAgentSocketFile: File? = if (mountSshAgent) {
        val sshAuthSock = System.getenv("SSH_AUTH_SOCK")?.trim()?.takeIf { it.isNotBlank() }
        if (sshAuthSock == null) {
            // No ssh-agent on host (typical on TC / CI agents) — skip the forward
            // rather than hard-failing. Tests that actually need SSH (e.g. git
            // clone from a private remote) will fail later with a clearer error;
            // tests that don't (DPAIA arena clones from public HTTPS remotes,
            // bright scenarios drive Maven/Gradle locally inside the container)
            // proceed cleanly. Pass `mountSshAgent = false` explicitly to quell
            // this notice in tests that intentionally run without SSH.
            println("[IDE-AGENT] SSH_AUTH_SOCK not set — skipping SSH agent socket forward")
            null
        } else {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val isMacHost = osName.contains("mac")
            if (isMacHost) {
                // Docker Desktop provides a stable SSH agent socket proxy that is more reliable
                // than mounting launchd paths like /private/tmp/com.apple.launchd.../Listeners.
                File("/run/host-services/ssh-auth.sock")
            } else {
                val socket = File(sshAuthSock)
                if (socket.exists()) {
                    socket
                } else {
                    println("[IDE-AGENT] SSH_AUTH_SOCK points to a missing socket (${socket.absolutePath}) — skipping SSH agent forward")
                    null
                }
            }
        }
    } else {
        null
    }

    if (mountDockerSocket) {
        require(dockerSocketFile.exists()) {
            "mountDockerSocket=true but Docker socket not found at ${dockerSocketFile.absolutePath}. " +
            "Ensure Docker is running on the host."
        }
        println("[IDE-AGENT] Docker socket mount enabled: ${dockerSocketFile.absolutePath}")
    }
    if (sshAgentSocketFile != null) {
        println(
            "[IDE-AGENT] SSH agent mount enabled: ${sshAgentSocketFile.absolutePath} -> $sshAgentHostMountPath " +
                    "(exported as $sshAgentGuestPath)"
        )
    }
    if (hostNetrcFile != null) {
        println("[IDE-AGENT] Host netrc mount enabled: ${hostNetrcFile.absolutePath} -> $hostNetrcGuestPath")
    }
    if (hostM2SettingsFile != null) {
        println("[IDE-AGENT] Host Maven settings mount enabled: ${hostM2SettingsFile.absolutePath} -> $hostM2SettingsGuestPath")
    }
    if (hostJetBrainsTeamTokenFile != null) {
        println(
            "[IDE-AGENT] Host jb token cache mount enabled: " +
                    "${hostJetBrainsTeamTokenFile.absolutePath} -> $hostJetBrainsTeamTokenGuestPath"
        )
    }
    if (hostJetBrainsTokensDir != null) {
        println(
            "[IDE-AGENT] Host jb tokens dir mount enabled: " +
                    "${hostJetBrainsTokensDir.absolutePath} -> $hostJetBrainsTokensGuestPath"
        )
    }
    if (hostPrivatePackagesAuthCacheDir != null) {
        println(
            "[IDE-AGENT] Host private-packages auth cache mount enabled: " +
                    "${hostPrivatePackagesAuthCacheDir.absolutePath} -> $hostPrivatePackagesAuthCacheGuestPath"
        )
    }
    if (hasPackagesEnvCredentials) {
        println("[IDE-AGENT] JB_SPACE credentials pass-through enabled (source: host env/system properties)")
    } else if (hostPackagesClientId != null || hostPackagesClientSecret != null) {
        println("[IDE-AGENT] WARNING: Incomplete JB_SPACE credentials, ignoring host pass-through")
    }

    val volumes = buildList {
        add(ContainerVolume(runDir, containerMountedPath, "rw"))
        if (repoCacheDir != null) add(ContainerVolume(repoCacheDir, "/repo-cache", "ro"))
        if (mountDockerSocket) add(ContainerVolume(dockerSocketFile, "/var/run/docker.sock", "rw"))
        if (sshAgentSocketFile != null) add(ContainerVolume(sshAgentSocketFile, sshAgentHostMountPath, "rw"))
        if (hostNetrcFile != null) add(ContainerVolume(hostNetrcFile, hostNetrcGuestPath, "ro"))
        if (hostM2SettingsFile != null) add(ContainerVolume(hostM2SettingsFile, hostM2SettingsGuestPath, "ro"))
        if (hostJetBrainsTokensDir != null) add(ContainerVolume(hostJetBrainsTokensDir, hostJetBrainsTokensGuestPath, "ro"))
        if (hostJetBrainsTeamTokenFile != null) {
            add(ContainerVolume(hostJetBrainsTeamTokenFile, hostJetBrainsTeamTokenGuestPath, "ro"))
        }
        if (hostPrivatePackagesAuthCacheDir != null) {
            add(
                ContainerVolume(
                    hostPrivatePackagesAuthCacheDir,
                    hostPrivatePackagesAuthCacheGuestPath,
                    "ro",
                )
            )
        }
    }
    val containerEnv = buildMap {
        if (sshAgentSocketFile != null) {
            put("SSH_AUTH_SOCK", sshAgentGuestPath)
        }
        if (hasPackagesEnvCredentials) {
            put("JB_SPACE_CLIENT_ID", hostPackagesClientId!!)
            put("JB_SPACE_CLIENT_SECRET", hostPackagesClientSecret!!)
        }
        if (mountDockerSocket) {
            // Testcontainers creates new containers on the HOST Docker daemon (via the mounted socket).
            // Those containers expose ports on the host network, not inside this container.
            // Without this override, Testcontainers connects to "localhost" which is the container
            // itself — causing connection failures. host.docker.internal resolves to the host gateway
            // (set via --add-host in docker-container-start.kt), bridging the gap.
            put("TESTCONTAINERS_HOST_OVERRIDE", "host.docker.internal")
        }
    }

    var container = startDockerContainerAndDispose(
        lifetime,
        StartContainerRequest()
            .image(imageId)
            .extraEnvVars(containerEnv)
            .volumes(volumes)
            .ports(
                XcvbVideoDriver.VIDEO_STREAMING_PORT,
                McpSteroidDriver.MCP_STEROID_PORT,
            ),
    )

    // Register the TC artifact post-process AFTER the container has been
    // started (so its cleanup is also registered — container teardown runs
    // BEFORE this callback in LIFO) but BEFORE the video driver is started
    // (so the video-finalize cleanup is registered LATER and therefore
    // runs AFTER this callback in LIFO). Resulting cleanup order:
    //
    //   1. (latest-registered callbacks — screenshot/rsync drivers, …)
    //   2. video ffmpeg stop + copy-out to /mcp-run-dir/video/recording.mp4
    //   3. <this post-process>  ← final mp4 is on the mount, container still alive
    //   4. container stop + remove
    //   5. publishRunDirArtifact(runDir)  ← sees <runDir>/publish/ tree
    //
    // The post-process is a no-op outside TeamCity; it gates on
    // `TEAMCITY_VERSION` internally so local dev keeps the raw runDir.
    lifetime.registerCleanupAction {
        TeamCityArtifactPostProcess.buildPublishTree(container, containerMountedPath)
    }

    if (
        hasPackagesEnvCredentials ||
        hostNetrcFile != null ||
        hostM2SettingsFile != null ||
        hostJetBrainsTokensDir != null ||
        hostJetBrainsTeamTokenFile != null ||
        hostPrivatePackagesAuthCacheDir != null
    ) {
        val installHostAuthArtifactsResult = container.startProcessInContainer {
            this
                .user("0:0")
                .args(
                    "bash", "-lc",
                    """
                    set -euo pipefail
                    mkdir -p /home/agent/.m2 /home/agent/.jb/tokens
                    chown -R agent:agent /home/agent/.m2 /home/agent/.jb
                    if [ -f "$hostNetrcGuestPath" ]; then
                      install -m 600 -o agent -g agent "$hostNetrcGuestPath" /home/agent/.netrc
                    fi
                    if [ -f "$hostM2SettingsGuestPath" ]; then
                      install -D -m 600 -o agent -g agent "$hostM2SettingsGuestPath" /home/agent/.m2/settings.xml
                    fi
                    if [ -f "$hostJetBrainsTeamTokenGuestPath" ]; then
                      install -D -m 600 -o agent -g agent "$hostJetBrainsTeamTokenGuestPath" /home/agent/.jb/tokens/jetbrains.team.json
                    fi
                    if [ -d "$hostJetBrainsTokensGuestPath" ]; then
                      cp -R "$hostJetBrainsTokensGuestPath"/. /home/agent/.jb/tokens/
                    fi
                    read_netrc_field_for_machine() {
                      machineName="${'$'}1"
                      field="${'$'}2"
                      netrcPath="${'$'}3"
                      awk -v machineName="${'$'}machineName" -v field="${'$'}field" '
                        BEGIN { in_host = 0 }
                        ${'$'}1 == "machine" {
                          if (${ '$'}2 == machineName) {
                            in_host = 1
                          } else if (in_host) {
                            exit
                          } else {
                            in_host = 0
                          }
                        }
                        in_host {
                          for (i = 1; i <= NF; i++) {
                            if (${ '$'}i == field && i + 1 <= NF) {
                              print ${ '$'}(i + 1)
                              exit
                            }
                          }
                        }
                      ' "${'$'}netrcPath"
                    }

                    upsert_netrc_machine() {
                      machineName="${'$'}1"
                      machineLogin="${'$'}2"
                      machinePassword="${'$'}3"
                      netrcFile="/home/agent/.netrc"
                      tmpNetrc="$(mktemp)"
                      {
                        printf "machine %s login %s password %s\n" "${'$'}machineName" "${'$'}machineLogin" "${'$'}machinePassword"
                        if [ -f "${'$'}netrcFile" ]; then
                          awk -v machineName="${'$'}machineName" '
                            BEGIN { skip = 0 }
                            ${'$'}1 == "machine" {
                              if (${ '$'}2 == machineName) {
                                skip = 1
                                next
                              }
                              skip = 0
                            }
                            !skip { print }
                          ' "${'$'}netrcFile"
                        fi
                      } > "${'$'}tmpNetrc"
                      chmod 600 "${'$'}tmpNetrc"
                      mv "${'$'}tmpNetrc" "${'$'}netrcFile"
                      chown agent:agent "${'$'}netrcFile"
                    }

                    packagesNetrc="/home/agent/.netrc"
                    packagesUser="${'$'}{JB_SPACE_CLIENT_ID:-}"
                    packagesPassword="${'$'}{JB_SPACE_CLIENT_SECRET:-}"
                    if [ -z "${'$'}packagesUser" ] || [ -z "${'$'}packagesPassword" ]; then
                      packagesUser=""
                      packagesPassword=""
                    fi
                    if [ -z "${'$'}packagesUser" ] && [ -f "${'$'}packagesNetrc" ]; then
                      packagesUser="$(read_netrc_field_for_machine "packages.jetbrains.team" "login" "${'$'}packagesNetrc")"
                      packagesPassword="$(read_netrc_field_for_machine "packages.jetbrains.team" "password" "${'$'}packagesNetrc")"
                    fi

                    # Host ~/.m2/settings.xml often contains an expired private packages token.
                    # Prefer current packages.jetbrains.team credentials from ~/.netrc for all
                    # IntelliJ private Maven server IDs used during project import/indexing.
                    if [ -n "${'$'}packagesUser" ] && [ -n "${'$'}packagesPassword" ]; then
                      touch /home/agent/.netrc
                      chmod 600 /home/agent/.netrc
                      upsert_netrc_machine "packages.jetbrains.team" "${'$'}packagesUser" "${'$'}packagesPassword"
                      upsert_netrc_machine "cache-redirector.jetbrains.com" "${'$'}packagesUser" "${'$'}packagesPassword"
                      upsert_netrc_machine "ultimate-bazel-cache-http.labs.jb.gg" "${'$'}packagesUser" "${'$'}packagesPassword"
                      cat > /home/agent/.m2/settings.xml <<EOF
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
                      <servers>
                        <server>
                          <id>code-with-me-lobby-server-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>intellij-private-dependencies</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>grazie-platform-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>jcp-github-mirror-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                      </servers>
                    </settings>
                    EOF
                      chown agent:agent /home/agent/.m2/settings.xml
                      chmod 600 /home/agent/.m2/settings.xml
                    fi
                    if [ -d "$hostPrivatePackagesAuthCacheGuestPath" ]; then
                      mkdir -p /home/agent/.cache/JetBrains
                      rm -rf /home/agent/.cache/JetBrains/private-packages-authorizer
                      cp -R "$hostPrivatePackagesAuthCacheGuestPath" /home/agent/.cache/JetBrains/private-packages-authorizer
                    fi
                    if [ -d /home/agent/.cache ]; then
                      chown -R agent:agent /home/agent/.cache
                    fi
                    """.trimIndent()
                )
                .timeoutSeconds(30)
                .description("Install host auth artifacts for agent user")
                .quietly()
        }.awaitForProcessFinish()
        require(installHostAuthArtifactsResult.exitCode == 0) {
            "Failed installing host auth artifacts for agent user: ${installHostAuthArtifactsResult.stderr}"
        }
    }

    if (mountDockerSocket) {
        // Fix docker socket permissions: the GID of /var/run/docker.sock on the host
        // won't match the GID of the 'docker' group inside the container (created by
        // groupadd without a specific GID). chmod 666 makes it accessible to the agent user.
        val fixDockerSocketResult = container.startProcessInContainer {
            this
                .user("0:0")
                .args(
                    "bash", "-lc",
                    """
                    set -euo pipefail
                    if [ ! -S "/var/run/docker.sock" ]; then
                      echo "Mounted Docker socket is missing: /var/run/docker.sock" >&2
                      exit 1
                    fi
                    chmod 666 /var/run/docker.sock
                    """.trimIndent()
                )
                .timeoutSeconds(10)
                .description("Fix docker socket permissions for TestContainers")
                .quietly()
        }.awaitForProcessFinish()
        require(fixDockerSocketResult.exitCode == 0) {
            "Failed to fix docker socket permissions: ${fixDockerSocketResult.stderr}"
        }
    }

    if (sshAgentSocketFile != null) {
        val initSshAgentSocketResult = container.startProcessInContainer {
            this
                .user("0:0")
                .args(
                    "bash", "-lc",
                    """
                    set -euo pipefail
                    if [ ! -S "$sshAgentHostMountPath" ]; then
                      echo "Mounted SSH agent socket is missing: $sshAgentHostMountPath" >&2
                      exit 1
                    fi
                    chmod 666 "$sshAgentHostMountPath" || true
                    ln -sfn "$sshAgentHostMountPath" "$sshAgentGuestPath"
                    """.trimIndent()
                )
                .timeoutSeconds(30)
                .description("Initialize SSH agent socket path in container")
                .quietly()
        }.awaitForProcessFinish()
        require(initSshAgentSocketResult.exitCode == 0) {
            "Failed to initialize SSH agent socket in container: ${initSshAgentSocketResult.stderr}"
        }
    }

    val xcvb = XcvbDriver(
        lifetime,
        container,
        layoutManager
    )

    xcvb.startDisplayServer()
    container = xcvb.withDisplay(container)

    val windowsDriver = XcvbWindowDriver(lifetime, container, xcvb.wholeScreenAreal())
    windowsDriver.startWindowManager()

    val videoDriver = XcvbVideoDriver(lifetime, container, windowsDriver, xcvb, "$containerMountedPath/video", realConsoleTitle)
    videoDriver.startVideoService()

    val screenshotDriver = XcvbScreenshotDriver(lifetime, container, "$containerMountedPath/screenshot")
    screenshotDriver.startScreenshotCapture()

    val windowsLayout = WindowLayoutManager(windowsDriver, layoutManager)

    val consoleDriver = XcvbConsoleDriver(lifetime, container, windowsDriver)
    val console = consoleDriver.createConsoleDriver(container, realConsoleTitle, windowsLayout.layoutStatusConsoleWindow())

    console.writeInfo("Preparing ${ideProduct.displayName}...")

    val inputDriver = XcvbInputDriver(container)
    val skillDriver = XcvbSkillDriver(lifetime, container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
        ideProduct,
        skipChangedFilesScanOnStartup = reuseProjectFromImage,
        disableProjectTrustChecks = disableProjectTrustChecks,
        trustAllProjectPaths = trustAllProjectPaths,
    )
    console.writeInfo("Deploying MCP Steroid plugin...")
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)


    if (!reuseProjectFromImage) {
        // Warm project cache artifacts on host before deploying:
        // bare repos, IntelliJ clone ZIPs, etc. mounted at /repo-cache.
        if (repoCacheDir != null) {
            println("[IDE-AGENT] Warming project cache artifacts in ${repoCacheDir.absolutePath} ...")
            try {
                selectedProject.warmRepoCache(repoCacheDir)
            } catch (e: Exception) {
                println("[IDE-AGENT] WARNING: Failed to warm project cache artifacts: ${e.message}")
            }
        }

        val ijProjectDriver = IntelliJProjectDriver(lifetime, container, ijDriver, console)
        ijProjectDriver.deployProject(selectedProject)
    } else {
        console.writeInfo("Reusing project checkout from source image; skipping project deployment")
    }

    console.writeInfo("Starting ${ideProduct.displayName}...")
    val ijProcess = ijDriver.startIde()
    console.writeSuccess("${ideProduct.displayName} process started")

    require(ijProcess.isRunning()) { "${ideProduct.displayName} process finished" }

    var trackedPids = setOf(ijProcess.pid)
    var lastPidRefreshAt = 0L
    var lastWindows = emptyList<WindowInfo>()
    var lastWindowDiagnosticsAt = 0L
    val ijWindowInfo = try {
        // 60s is safe: X11 frame appears before most startup work completes; the only
        // confirmed long blocker (AIPromoWindowAdvisor, 480s) is suppressed by our 4-layer fix.
        // Research confirmed no other pre-frame blocking network calls in the startup path.
        waitForValue(60_000, "Waiting for ${ideProduct.displayName} window") {
            val now = System.currentTimeMillis()
            if (now - lastPidRefreshAt >= 1_000) {
                trackedPids = discoverProcessFamilyPids(container, ijProcess.pid)
                lastPidRefreshAt = now
            }

            lastWindows = windowsDriver.listWindows()

            if (now - lastWindowDiagnosticsAt >= 5_000) {
                lastWindowDiagnosticsAt = now
                println("[IDE-AGENT] Waiting for ${ideProduct.displayName} window: PIDs=${trackedPids.sorted()}, visible=${lastWindows.size}")
                lastWindows.forEach { w ->
                    println("[IDE-AGENT]   id=${w.id} pid=${w.pid} ${w.rect.width}x${w.rect.height} title='${w.title}'")
                }
            }

            pickIdeWindow(lastWindows, trackedPids, realConsoleTitle)
        }
    } catch (t: RuntimeException) {
        val windowsSnapshot = lastWindows.joinToString(separator = "\n") { info ->
            "id=${info.id} pid=${info.pid} rect=${info.rect.width}x${info.rect.height}+${info.rect.x}+${info.rect.y} title='${info.title}'"
        }
        throw RuntimeException(
            buildString {
                append("Failed waiting for ${ideProduct.displayName} window.")
                append(" trackedPids=${trackedPids.sorted()}")
                if (windowsSnapshot.isNotEmpty()) {
                    appendLine()
                    append("Visible windows:")
                    appendLine()
                    append(windowsSnapshot)
                }
            },
            t,
        )
    }

    windowsDriver.updateLayout(ijWindowInfo, windowsLayout.layoutIntelliJWindow())

    // Re-layout the console window now that fluxbox is fully settled (decorations applied).
    // The first updateLayout call in createConsoleDriver races with fluxbox applying the
    // apps file {NONE} decorations — by this point IntelliJ is up, so fluxbox has had
    // 30+ seconds to settle and the console position is corrected.
    val consoleWindow = windowsDriver.listWindows()
        .firstOrNull { it.title.contains(realConsoleTitle, ignoreCase = true) }
    if (consoleWindow != null) {
        windowsDriver.updateLayout(consoleWindow, windowsLayout.layoutStatusConsoleWindow())
    }

    // Wait for MCP server readiness
    val mcpSteroidDriver = McpSteroidDriver(container, ijDriver)
    console.writeInfo("Waiting for MCP Steroid server...")
    mcpSteroidDriver.waitForMcpReady()

    // Register JDKs as early as possible — racing against IntelliJ's async `SdkLookup`
    // which fires when project-open's `UnknownSdkStartupChecker` + Gradle auto-import
    // activities run. If `SdkLookup.findJdk(sdkName)` runs before our JDK registration
    // hits `ProjectJdkTable`, it proposes a download and blocks the EDT on a
    // `MessageDialogBuilder$YesNo.ask` consent modal — making the test un-runnable.
    // Only runs for Java-capable IDEs: `mcpListJdks`/`mcpAddJdk` import
    // `com.intellij.openapi.projectRoots.JavaSdk`, which is only on the classpath
    // when the target IDE bundles `com.intellij.java` (see IdeProduct.hasJavaSdk).
    if (ideProduct.hasJavaSdk) {
        console.writeInfo("Registering JDKs early (racing project-open SdkLookup)...")
        try {
            waitFor(30_000L, "project appears in MCP list") {
                mcpSteroidDriver.mcpListProjects().any { it.path == ijDriver.getGuestProjectDir() }
            }
            mcpSteroidDriver.mcpRegisterJdks(ijDriver.getGuestProjectDir())
            console.writeSuccess("Early JDK registration complete")
        } catch (e: Throwable) {
            console.writeInfo("Early JDK registration failed: ${e.message} (will retry in waitForProjectReady)")
        }
    } else {
        console.writeInfo("Skipping early JDK registration — ${ideProduct.displayName} has no Java plugin (IdeProduct.hasJavaSdk=false)")
    }

    val resolvedMcpConnectionMode: McpConnectionMode = mcpConnectionMode ?: when (aiMode) {
        AiMode.NONE -> McpConnectionMode.None
        AiMode.AI_MCP -> McpConnectionMode.Http
        AiMode.AI_NPX -> McpConnectionMode.Npx(NpxSteroidDriver.deploy(container, mcpSteroidDriver))
    }

    val aiAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        console = console,
        mcp = mcpSteroidDriver,
        mcpConnection = resolvedMcpConnectionMode,
        logDir = runDir,
    )

    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapGuestPortToHostPort(XcvbVideoDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = mcpSteroidDriver.hostMcpUrl
    val infoString = buildString {
        appendLine("=".repeat(20))
        appendLine("Use these parameters to debug the test")
        appendLine("RUN_DIR=$runDir")
        appendLine("CONTAINER_ID=${container.containerId}")
        appendLine("DISPLAY=${xcvb.DISPLAY}")
        appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
        appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
        appendLine("MCP_STEROID=$mcpUrl")
        appendLine("=".repeat(20))
    }
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(infoString)

    val session = IntelliJContainer(
        lifetime = lifetime,
        runDirInContainer = runDir,
        scope = container,
        intellijDriver = ijDriver,
        console = console,
        input = inputDriver,
        mcpSteroid = mcpSteroidDriver,
        aiAgents = aiAgentDriver,
        intellij = ijProcess,
        windows = windowsDriver,
        windowLayout = windowsLayout,
        openFileOnStart = selectedProject.openFileOnStart,
    )

    println("[IDE-AGENT] Session ready: $runDir")
    return session
}

fun IntelliJContainer.Companion.createFromSnapshot(
    lifetime: CloseableStack,
    snapshotImage: ImageDriver,
    consoleTitle: String,
    project: IntelliJProject = IntelliJProject.IntelliJMasterProject,
    layoutManager: LayoutManager = HorizontalLayoutManager(),
    distribution: IdeDistribution = IdeDistribution.fromSystemProperties(),
    aiMode: AiMode = AiMode.AI_MCP,
    mcpConnectionMode: McpConnectionMode? = null,
    repoCacheDir: File? = IdeTestFolders.repoCacheDirOrNull,
    mountDockerSocket: Boolean = false,
    mountSshAgent: Boolean = true,
): IntelliJContainer = create(
    lifetime = lifetime,
    dockerFileBase = "ide-agent",
    consoleTitle = consoleTitle,
    project = project,
    layoutManager = layoutManager,
    distribution = distribution,
    aiMode = aiMode,
    mcpConnectionMode = mcpConnectionMode,
    repoCacheDir = repoCacheDir,
    mountDockerSocket = mountDockerSocket,
    mountSshAgent = mountSshAgent,
    sourceImage = snapshotImage,
    reuseProjectFromImage = true,
)

private fun pickIdeWindow(
    windows: List<WindowInfo>,
    candidatePids: Set<Long>,
    consoleTitle: String,
): WindowInfo? {
    val sizableWindows = windows
        .asSequence()
        .filter { it.rect.width > 300 && it.rect.height > 300 }
        .filter { it.title.isNotBlank() }
        .filterNot { it.title.equals("Desktop", ignoreCase = true) }
        .toList()
    if (sizableWindows.isEmpty()) return null

    // First preference: match by known process family PID
    val byProcessFamily = sizableWindows.filter { window ->
        val pid = window.pid ?: return@filter false
        pid in candidatePids
    }
    if (byProcessFamily.isNotEmpty()) {
        return byProcessFamily.maxByOrNull { it.rect.width * it.rect.height }
    }

    // Fallback: largest sizable non-console window (covers windows without exposed PID)
    return sizableWindows
        .asSequence()
        .filterNot { it.title.contains(consoleTitle, ignoreCase = true) }
        .maxByOrNull { it.rect.width * it.rect.height }
}

private fun discoverProcessFamilyPids(container: ContainerDriver, rootPid: Long): Set<Long> {
    val processMap = container.startProcessInContainer {
        this
            .args("bash", "-c", "ps -eo pid=,ppid=")
            .timeoutSeconds(5)
            .quietly()
            .description("ps -eo pid=,ppid=")
    }.awaitForProcessFinish()
    if (processMap.exitCode != 0) return setOf(rootPid)

    val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
    processMap.stdout.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 2) return@forEach
        val pid = parts[0].toLongOrNull() ?: return@forEach
        val ppid = parts[1].toLongOrNull() ?: return@forEach
        childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
    }

    val discovered = linkedSetOf(rootPid)
    val queue = ArrayDeque<Long>()
    queue.add(rootPid)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in childrenByParent[current].orEmpty()) {
            if (discovered.add(child)) {
                queue.add(child)
            }
        }
    }

    return discovered
}

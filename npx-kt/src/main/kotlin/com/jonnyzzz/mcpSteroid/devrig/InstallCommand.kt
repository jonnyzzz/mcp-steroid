/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.ProcessAiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.mcpAddStdioInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.mcpListInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.mcpRemoveInvocation
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private const val DEVRIG_MCP_SERVER_NAME = "mcp-steroid"
private const val DEVRIG_LEGACY_SERVER_NAME = "devrig"

/** Exit code of `devrig install <agent> --check` when install would change anything (drift detected). */
const val INSTALL_CHECK_DRIFT_EXIT_CODE = 1

/**
 * `devrig install devrig` — generate + register devrig's own `~/.mcp-steroid/bin/devrig`(`.cmd`) launcher
 * and put it on PATH. This is devrig's normal launcher self-registration ([ensureBinLauncher], which runs
 * on every start), here driven from the install script's EXPLICIT parameters: `--install-script` (the
 * unpacked install-tree launcher the wrapper execs) and `--jdk-home` (pinned as `DEVRIG_JAVA_HOME`). It
 * does ONLY that — no agent registration. Quiet: best-effort registration, one confirmation line.
 *
 * **Non-interactive contract.** This command is invoked by the generated bootstrap installers
 * (`install.sh` / `install.ps1`), typically run as `curl | sh` / `irm | iex`. Those installers hand it a
 * stdin that reads EOF immediately (`< /dev/null` on POSIX, `$null |` on PowerShell) so it can never
 * inherit a live input stream: on POSIX the `sh` process's own stdin is the pipe from `curl`, and on
 * Windows the child would otherwise inherit the caller's PowerShell host stdin. In either shape a
 * subprocess that read stdin — or waited on a prompt — could block with no user recourse. So this code
 * path (and everything it calls) MUST be fully non-interactive: NO stdin reads, NO prompts, NO
 * `Console.readLine` / `readln`. Every input arrives as an explicit flag; any missing input must fail
 * fast (return non-zero) rather than block waiting for a user who cannot answer.
 */
fun DevrigServices.runInstallDevrigCommand(command: DevrigCommand.DevrigCommandInstallDevrig): Int {
    val installScript = command.installScript
    if (installScript.isNullOrBlank()) {
        System.err.println("[mcp-steroid] 'devrig install devrig' requires --install-script=<full path>")
        return 64
    }
    // --jdk-home is what the wrapper pins as DEVRIG_JAVA_HOME; fall back to the JDK we run under.
    val jdkHome = command.jdkHome?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of(System.getProperty("java.home"))
    val script = Path.of(installScript)
    return runInstallDevrigCore(
        homePaths = homePaths,
        installScript = script,
        ownVersion = DevrigVersionMetadata.getBuildVersion(),
        autoUpdateRun = parseUpdateEnvFlag(System.getenv(ENV_DEVRIG_AUTO_UPDATE)),
        isWin = isWindows(),
        writeLauncher = { bypass ->
            ensureBinLauncherForInstallScript(
                homePaths, script, jdkHome,
                force = true, registerWindowsPath = true, bypassNoDowngradeGuard = bypass,
            )
        },
    )
}

/** Distinct exit code: an auto-update-spawned `devrig install devrig` refused to downgrade past a newer completed update. */
const val INSTALL_DEVRIG_NO_DOWNGRADE_EXIT_CODE = 65

/**
 * The update-authority core of `devrig install devrig` (docs/updates-check/devrig-auto-update.md):
 * this command runs AS the version being installed, so it is the one place that can both rewrite the
 * launcher and attest the result. It (1) VERIFIES the launcher content actually references the
 * install-script it registered — a swallowed Windows sharing violation must not report success for a
 * launcher that never changed; (2) writes the `updated-<v>` completion marker on verified success
 * (never for a SNAPSHOT build — its marker would compare above every release and block self-heal);
 * (3) honors intent: an auto-update run ([ENV_DEVRIG_AUTO_UPDATE]) keeps the no-downgrade guard and
 * refuses when a newer completed update exists, while a manual run (incl. rollback) bypasses the
 * guard and first sweeps every newer-version marker so they cannot re-block the self-heal.
 *
 * Every input is explicit so the decision tree is unit-testable without a [DevrigServices].
 */
fun runInstallDevrigCore(
    homePaths: HomePaths,
    installScript: Path,
    ownVersion: DevrigVersion,
    autoUpdateRun: Boolean,
    isWin: Boolean,
    writeLauncher: (bypassNoDowngradeGuard: Boolean) -> LauncherWriteOutcome?,
    coordination: UpdateCoordination = UpdateCoordination(homePaths.updateDir),
): Int {
    if (autoUpdateRun) {
        val newest = newestUpdatedMarkerVersion(homePaths.updateDir)
        if (newest != null && newest > baseVersion(ownVersion.value)) {
            System.err.println(
                "[mcp-steroid] refusing to install devrig $ownVersion: a completed update to $newest is " +
                    "pending restart, and this is an auto-update run (a delayed installer must never downgrade).",
            )
            return INSTALL_DEVRIG_NO_DOWNGRADE_EXIT_CODE
        }
    } else {
        // Explicit install intent (manual curl|sh, incl. rollback): newer-version markers are swept so
        // they cannot re-block the launcher self-heal after this install.
        coordination.sweepMarkersNewerThan(ownVersion.value)
    }

    val outcome = try {
        writeLauncher(!autoUpdateRun)
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] ERROR: 'devrig install devrig' could not write the launcher: $e")
        e.printStackTrace(System.err)
        return 64
    }

    val launcher = DevrigUserLauncher.path(homePaths, windows = isWin)
    if (outcome == null) {
        // DEVRIG_BIN_NO_AUTO_REGISTER opt-out: the user manages the launcher themselves. Nothing was
        // written and no completion marker is attested (the active updater is disabled under this
        // opt-out for the same reason — see the design doc's gating section).
        if (!launcher.isRegularFile()) {
            System.err.println(
                "[mcp-steroid] ERROR: 'devrig install devrig' did not create the launcher $launcher " +
                    "(DEVRIG_BIN_NO_AUTO_REGISTER opts out of writing it).",
            )
            return 64
        }
        System.err.println("[mcp-steroid] launcher $launcher is managed manually ($ENV_BIN_NO_AUTO_REGISTER); leaving it unchanged")
        return 0
    }
    if (outcome == LauncherWriteOutcome.SKIPPED_NO_DOWNGRADE) {
        System.err.println("[mcp-steroid] launcher rewrite was skipped by the no-downgrade guard")
        return INSTALL_DEVRIG_NO_DOWNGRADE_EXIT_CODE
    }

    // Verify, don't assume: the launcher on disk must reference the exact install-script we registered.
    // isRegularFile() alone would pass on the OLD launcher after a silently failed rewrite.
    val expectedRef = installScript.toAbsolutePath().normalize().toString()
        .let { if (isWin) it else it.replace('\\', '/') }
    val content = try {
        Files.readString(launcher)
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] ERROR: could not read back the launcher $launcher: $e")
        return 64
    }
    if (!content.contains(expectedRef)) {
        System.err.println(
            "[mcp-steroid] ERROR: the launcher $launcher does not reference $expectedRef after the " +
                "rewrite — the write did not land (concurrent rewrite or a swallowed IO failure).",
        )
        return 64
    }

    // The completion marker: attested by the same process that rewrote + verified the launcher, so
    // there is no window in which the launcher is new but the no-downgrade guard has no marker to see.
    if (!ownVersion.isSnapshotBuild) {
        val now = System.currentTimeMillis()
        coordination.writeUpdatedMarker(
            ownVersion.value,
            UpdateStateInfo(
                pid = coordination.ownPid,
                hostname = coordination.hostname,
                currentVersion = ownVersion.value,
                targetVersion = baseVersionString(ownVersion.value),
                startedAt = now,
                completedAt = now,
            ),
        )
    }

    System.err.println("[mcp-steroid] devrig is on PATH via $launcher")

    // Also offer the MCP Steroid plugin to any IDE already running: fire each IDE's native install
    // dialog over REST. Best-effort and fully non-interactive (the IDE — not devrig — shows the dialog),
    // so it is safe inside the `curl | sh` bootstrap: it never reads stdin and never fails the install.
    tryInstallPluginIntoRunningIdesQuietly()
    return 0
}

fun DevrigServices.runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    runner: AiAgentCliRunner = ProcessAiAgentCliRunner(),
): Int {
    if (command.check) {
        // `--check` is the read-only dry-run of install: it writes NOTHING — no launcher, no agent
        // config. The canonical command it compares against is the same stable wrapper install would
        // register, computed as a pure path (DevrigUserLauncher.invocation never touches disk), so the
        // diagnosis is correct even before the launcher file exists (install would create it).
        // Report the launcher itself being absent (install hasn't run yet) as a diagnostic — a read-only
        // stat, no write. Without it the registered command would point at a path that does not exist.
        val launcherFile = DevrigUserLauncher.path(homePaths)
        return runInstallCheckCommand(
            command = command,
            mcpCommand = DevrigUserLauncher.invocation(homePaths, listOf("mcp")),
            missingLauncherPath = launcherFile.takeUnless { it.isRegularFile() },
            out = mcpStdout,
            err = System.err,
            runner = runner,
            ideReachability = { collectIdeReachability() },
        )
    }
    // Create the stable launcher first, then guarantee it exists before we register it.
    ensureBinLauncher(homePaths, force = true, registerWindowsPath = true)
    val launcher = DevrigUserLauncher.path(homePaths)
    if (!launcher.isRegularFile()) {
        System.err.println(
            "[mcp-steroid] ERROR: cannot register the MCP server — the launcher $launcher was not created.",
        )
        System.err.println(
            "  This usually means DEVRIG_BIN_NO_AUTO_REGISTER opts out of writing it. Unset that " +
                "(or create the launcher yourself), then re-run 'devrig install'.",
        )
        return 64
    }
    return runInstallCommand(
        command = command,
        mcpCommand = DevrigUserLauncher.invocation(homePaths, listOf("mcp")),
        out = mcpStdout,
        err = System.err,
        runner = runner,
    )
}

/**
 * Registers devrig as the `mcp-steroid` stdio MCP server in [command]'s agent, narrating each step so
 * the user understands exactly what is being changed.
 *
 * The registration is an **idempotent, consolidating upsert**:
 *  1. it reviews the agent's currently registered MCP servers;
 *  2. it removes every devrig-owned entry it finds — any named `mcp-steroid` or `devrig`, or whose
 *     launch command runs the devrig binary — collapsing duplicates/stale variants into one;
 *  3. it adds a single canonical `mcp-steroid` entry.
 *
 * That is what makes re-running `devrig install` safe and self-healing: it repairs a stale entry (old
 * launcher path or subcommand) and merges stray duplicates instead of failing with "already exists",
 * which is the trap users hit when an earlier version or a different bootstrapper had registered the
 * server under a different name (see issues #84/#86).
 */
fun runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    mcpCommand: StdioMcpCommand,
    out: PrintStream,
    err: PrintStream,
    runner: AiAgentCliRunner,
): Int {
    val agent = command.agent
    val renderedCommand = "${mcpCommand.command} ${mcpCommand.args.joinToString(" ")}"

    out.println("Installing devrig as the '$DEVRIG_MCP_SERVER_NAME' MCP server for ${agent.displayName}.")
    out.println()
    out.println("What this does:")
    out.println("  - Reviews ${agent.displayName}'s MCP servers — matching both by name " +
        "(${DEVRIG_SERVER_NAMES.joinToString(" / ") { "'$it'" }}) and by configuration (any entry whose " +
        "command runs the devrig binary) — and consolidates every match into a single " +
        "'$DEVRIG_MCP_SERVER_NAME' registration (user scope).")
    out.println("  - ${agent.displayName} will launch this command to start it:")
    out.println("      $renderedCommand")
    out.println("  - It points at the stable ~/.mcp-steroid/bin launcher, so it survives devrig upgrades")
    out.println("    without re-registering.")
    out.println()
    out.println("Re-running install is safe — existing devrig entries are replaced.")
    out.println()

    // Step 1 — review the agent's registered MCP servers so we can act on every devrig-owned entry,
    // not just the canonical name. Listing is best-effort: if it fails or we can't parse it, we fall
    // back to reconciling the known devrig names.
    out.println("Step 1/3: reviewing ${agent.displayName}'s registered MCP servers…")
    val listResult = runner.run(mcpListInvocation(agent))
    val listed = if (listResult.exitCode == 0) parseMcpServerList(agent, listResult.output) else emptyList()
    val detected = listed.filter { it.isDevrigOwned() }
    when {
        listResult.exitCode != 0 ->
            out.println("  could not read the server list; reconciling the known devrig names.")
        detected.isEmpty() ->
            out.println("  no existing devrig / '$DEVRIG_MCP_SERVER_NAME' registration found.")
        else -> {
            out.println("  found ${detected.size} devrig registration(s):")
            detected.forEach { out.println("    - '${it.name}' (matched by ${it.devrigMatchReason()})") }
        }
    }

    // The shared install plan — `--check` prints exactly this same set (see installRemovalNames), so the
    // dry-run can never drift from what install actually removes.
    val namesToRemove = installRemovalNames(detected, listReadable = listResult.exitCode == 0)

    // Step 2 — clear them all. Each removal is best-effort: a non-zero exit means "not present", which is
    // expected and not fatal. Underlying agent messages go to stderr; stdout reports confirmed removals.
    out.println("Step 2/3: consolidating into a single '$DEVRIG_MCP_SERVER_NAME' entry…")
    val removed = mutableListOf<String>()
    for (name in namesToRemove) {
        val removeResult = runner.run(mcpRemoveInvocation(agent, name))
        if (removeResult.exitCode == 0) removed += name
        emitAgentOutput(removeResult, err)
    }
    if (removed.isEmpty()) {
        out.println("  nothing to clean up.")
    } else {
        removed.forEach { out.println("  - removed '$it'") }
    }

    // Step 3 — add the single canonical registration.
    out.println("Step 3/3: registering '$DEVRIG_MCP_SERVER_NAME' with ${agent.displayName}…")
    val addInvocation = mcpAddStdioInvocation(agent, mcpCommand, DEVRIG_MCP_SERVER_NAME)
    val addResult = runner.run(addInvocation)
    emitAgentOutput(addResult, err)
    if (addResult.exitCode != 0) {
        err.println()
        err.println(
            "Registration FAILED: '${agent.binary} ${addInvocation.args.joinToString(" ")}' " +
                "exited with code ${addResult.exitCode}.",
        )
        err.println("Fix the error reported above, then re-run 'devrig install ${agent.binary}'.")
        return addResult.exitCode
    }

    out.println()
    out.println("Done — '$DEVRIG_MCP_SERVER_NAME' is registered for ${agent.displayName}.")
    out.println("  Command ${agent.displayName} will run: $renderedCommand")
    out.println("  Verify with: ${agent.binary} mcp list")
    return 0
}

private fun emitAgentOutput(result: AiAgentCliResult, err: PrintStream) {
    if (result.output.isNotBlank()) {
        err.print(result.output)
        if (!result.output.endsWith("\n")) err.println()
    }
}

/**
 * The single source of truth for the names `devrig install` clears before re-adding the canonical entry —
 * shared by install (which EXECUTES the removals) and `--check` (which PRINTS them), so the dry-run can
 * never drift from what install actually does: every detected devrig-owned entry, the canonical name
 * (always, so the re-add is clean even if the listing missed it), plus the legacy name when the agent's
 * list could not be read.
 */
internal fun installRemovalNames(detected: List<McpServerRef>, listReadable: Boolean): Set<String> {
    val names = LinkedHashSet<String>()
    detected.forEach { names += it.name }
    names += DEVRIG_MCP_SERVER_NAME
    if (!listReadable) names += DEVRIG_LEGACY_SERVER_NAME
    return names
}

/**
 * How many marker-discovered IDE backends (IDEs running the MCP Steroid plugin) answered the read-only
 * snapshot probe, out of how many were discovered. Computed on demand from the same markers + snapshot
 * scan `devrig backend` performs — nothing is cached or persisted (Tenet 3).
 */
data class IdeReachabilityReport(val reachable: Int, val discovered: Int)

/**
 * One-shot, read-only IDE reachability snapshot for `install --check`: uses the routing service to
 * count discovered IDEs (via [DevrigProjectRoutingService.discoveredBackends]) and those that have
 * at least one open project route (i.e. answered the snapshot fetch).
 *
 * NOTE: this uses the same live data the routing service holds — IDEs that wrote a
 * `~/.mcp-steroid/markers` marker, i.e. have the MCP Steroid plugin.
 */
fun DevrigServices.collectIdeReachability(): IdeReachabilityReport {
    val discovered = projectRouting.discoveredBackends()
    val routes = projectRouting.routes()
    val reachableBackendNames = routes.map { it.route.backendName }.toSet()
    return IdeReachabilityReport(
        reachable = discovered.count { it.backendName in reachableBackendNames },
        discovered = discovered.size,
    )
}

/**
 * Read-only mode of [runInstallCommand] (issue #86): performs the SAME discovery — list the agent's MCP
 * servers, classify devrig-owned entries, compare against the canonical [mcpCommand] install would
 * register — but applies NOTHING. `install` is the doctor; `--check` is its dry-run. It prints the
 * current registration state, the diff install WOULD apply (the shared [installRemovalNames] set + the
 * canonical add, or "already canonical"), and an IDE-reachability summary so a "Failed to connect" report
 * gets a one-command diagnosis.
 *
 * Returns 0 when the registration is already canonical (re-running install would change nothing) and
 * [INSTALL_CHECK_DRIFT_EXIT_CODE] when install would change anything — including when the agent's server
 * list cannot be read, since the state cannot be verified then.
 *
 * Stateless by design (Tenet 3): the only agent CLI invocation is the read-only `mcp list`, the IDE probe
 * reads markers/ports on demand, and the check writes nothing — two concurrent `--check` runs are safe.
 */
fun runInstallCheckCommand(
    command: DevrigCommand.DevrigCommandInstall,
    mcpCommand: StdioMcpCommand,
    out: PrintStream,
    err: PrintStream,
    runner: AiAgentCliRunner,
    ideReachability: () -> IdeReachabilityReport,
    /** Non-null = the devrig launcher does not exist yet at this path (reported as a diagnostic). */
    missingLauncherPath: Path? = null,
): Int {
    val agent = command.agent
    val renderedCommand = "${mcpCommand.command} ${mcpCommand.args.joinToString(" ")}"

    out.println(
        "Checking the '$DEVRIG_MCP_SERVER_NAME' MCP registration for ${agent.displayName} " +
            "(read-only — nothing is changed).",
    )
    out.println()

    if (missingLauncherPath != null) {
        out.println(
            "Note: the devrig launcher ($missingLauncherPath) does not exist yet — " +
                "'devrig install ${agent.binary}' will create it before registering.",
        )
        out.println()
    }

    // The SAME review step install performs — and the only agent CLI call --check makes.
    val listInvocation = mcpListInvocation(agent)
    val listResult = runner.run(listInvocation)
    val listReadable = listResult.exitCode == 0
    val listed = if (listReadable) parseMcpServerList(agent, listResult.output) else emptyList()
    val detected = listed.filter { it.isDevrigOwned() }

    out.println("Current registration state:")
    when {
        !listReadable ->
            out.println(
                "  could not read ${agent.displayName}'s MCP server list " +
                    "('${listInvocation.binary} ${listInvocation.args.joinToString(" ")}' " +
                    "exited with code ${listResult.exitCode}).",
            )
        detected.isEmpty() ->
            out.println("  no existing devrig / '$DEVRIG_MCP_SERVER_NAME' registration found.")
        else -> detected.forEach { entry ->
            out.println("  - '${entry.name}' (matched by ${entry.devrigMatchReason()}): ${entry.commandLine}")
        }
    }
    out.println()

    // Canonical = exactly one devrig-owned entry, under the canonical name, launching the exact command
    // install would register. Anything else — stale launcher/subcommand, duplicates, a custom name, no
    // entry, or an unreadable list — is drift.
    val canonical = listReadable &&
        detected.singleOrNull()?.let { it.name == DEVRIG_MCP_SERVER_NAME && it.commandLine == renderedCommand } == true

    out.println("What 'devrig install ${agent.binary}' would change:")
    if (canonical) {
        out.println("  already canonical — no changes.")
    } else {
        // The EXACT removal set install would issue (shared via installRemovalNames) — names install
        // clears defensively even when not detected are annotated "if present".
        val detectedNames = detected.map { it.name }.toSet()
        for (name in installRemovalNames(detected, listReadable)) {
            out.println("  - remove '$name'" + if (name in detectedNames) "" else ", if present")
        }
        out.println("  - add '$DEVRIG_MCP_SERVER_NAME' → $renderedCommand")
    }
    out.println()

    reportIdeReachability(out, err, ideReachability)
    out.println()

    return if (canonical) {
        out.println("No drift — '$DEVRIG_MCP_SERVER_NAME' is registered canonically for ${agent.displayName}.")
        0
    } else {
        out.println("Drift detected — run 'devrig install ${agent.binary}' to repair.")
        INSTALL_CHECK_DRIFT_EXIT_CODE
    }
}

private fun reportIdeReachability(out: PrintStream, err: PrintStream, probe: () -> IdeReachabilityReport) {
    val report = try {
        probe()
    } catch (e: Exception) {
        err.println("devrig install --check: IDE discovery failed: ${e.message ?: e::class.simpleName}")
        null
    }
    out.println("IDE backends with the MCP Steroid plugin (read-only discovery, same scan as 'devrig backend'):")
    when {
        report == null ->
            out.println("  discovery failed — see the error above; run 'devrig backend' for details.")
        report.discovered == 0 ->
            out.println(
                "  none discovered — no running IDE has the MCP Steroid plugin. " +
                    "Start one (or run 'devrig backend' for the full picture).",
            )
        else ->
            out.println("  ${report.reachable} of ${report.discovered} discovered backend(s) reachable.")
    }
}


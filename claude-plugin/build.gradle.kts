import groovy.json.JsonSlurper
import org.gradle.api.tasks.bundling.Zip
import java.util.SortedSet

plugins {
    base
}

val pluginVersion = version.toString()

// The devrig Claude plugin is published from the committed source tree via the repo-root
// marketplace.json (source: "./claude-plugin"), so the marketplace reads plugin.json straight
// from git -- NOT from the built ZIP. Claude Code only surfaces an update when the manifest
// `version` string CHANGES, so it must track the released VERSION. `syncClaudePluginVersion`
// writes it; `validatePluginJson` guards against drift.
val pluginJsonFile = projectDir.resolve(".claude-plugin/plugin.json")
val versionFile = rootProject.projectDir.resolve("VERSION")

// Claude Code wants semver; the root VERSION may be two-part (e.g. "0.100"). Normalize to X.Y.Z.
fun normalizeToSemver(raw: String): String {
    val v = raw.trim()
    return when {
        Regex("""^\d+\.\d+\.\d+$""").matches(v) -> v
        Regex("""^\d+\.\d+$""").matches(v) -> "$v.0"
        else -> throw GradleException("VERSION '$v' is not X.Y or X.Y.Z; cannot derive a plugin.json semver")
    }
}

// The lone place the manifest version is rewritten -- shared by syncClaudePluginVersion and the
// ZIP-staging preparePluginFiles so both use identical replace semantics.
fun patchPluginJsonVersion(json: String, newVersion: String): String =
    json.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$newVersion"""")

// Patches the version placeholder in plugin.json and copies all plugin files
// into build/plugin/ so the Zip task has a clean, versioned staging area.
val preparePluginFiles = tasks.register("preparePluginFiles") {
    group = "claude-plugin"
    description = "Stage plugin files into build/plugin/ with patched version"

    val sourceDir = projectDir
    val outputDir = layout.buildDirectory.dir("plugin")

    inputs.property("pluginVersion", pluginVersion)
    inputs.dir(sourceDir.resolve(".claude-plugin"))
    inputs.dir(sourceDir.resolve("bin"))
    inputs.dir(sourceDir.resolve("commands"))
    inputs.dir(sourceDir.resolve("hooks"))
    inputs.file(sourceDir.resolve(".mcp.json"))
    outputs.dir(outputDir)

    doLast {
        val out = outputDir.get().asFile
        out.mkdirs()

        // .claude-plugin/plugin.json -- inject the full build version for the ZIP channel (the
        // committed source keeps the released semver; this only affects the staged/zipped copy).
        val pluginDir = out.resolve(".claude-plugin").also { it.mkdirs() }
        val pluginJson = sourceDir.resolve(".claude-plugin/plugin.json").readText()
        pluginDir.resolve("plugin.json").writeText(patchPluginJsonVersion(pluginJson, pluginVersion))

        // bin/ -- copy everything from the committed source bin/ as-is. Windows artifacts (.ps1, .exe)
        // carry no POSIX exec bit; everything else Claude must spawn -- the POSIX scripts and the
        // devrig-mcp.cmd polyglot launcher -- is marked executable.
        val binOut = out.resolve("bin").also { it.mkdirs() }
        sourceDir.resolve("bin").listFiles()?.forEach { f ->
            val dest = binOut.resolve(f.name)
            f.copyTo(dest, overwrite = true)
            val windowsOnly = f.name.endsWith(".ps1") || f.name.endsWith(".exe")
            if (!windowsOnly) dest.setExecutable(true)
        }

        // .mcp.json -- MCP server registration for Claude Code
        out.resolve(".mcp.json").writeText(sourceDir.resolve(".mcp.json").readText())

        // commands/ -- slash commands, copied as-is
        val commandsOut = out.resolve("commands").also { it.mkdirs() }
        sourceDir.resolve("commands").listFiles()?.forEach { f ->
            f.copyTo(commandsOut.resolve(f.name), overwrite = true)
        }

        // hooks/ -- hook manifest, copied as-is
        val hooksOut = out.resolve("hooks").also { it.mkdirs() }
        sourceDir.resolve("hooks").listFiles()?.forEach { f ->
            f.copyTo(hooksOut.resolve(f.name), overwrite = true)
        }
    }
}

val claudePluginZip = tasks.register<Zip>("claudePluginZip") {
    group = "claude-plugin"
    description = "Build distributable Claude plugin zip"
    dependsOn(preparePluginFiles)

    archiveBaseName.set("claude-plugin")
    archiveVersion.set(pluginVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(layout.buildDirectory.dir("plugin"))
    from(rootProject.projectDir) { include("LICENSE") }
}

// Locks down the exact file set so accidental additions are caught immediately
val verifyPluginFiles = tasks.register("verifyPluginFiles") {
    group = "verification"
    description = "Verify files bundled in the Claude plugin zip"
    dependsOn(claudePluginZip)

    doLast {
        val zip = claudePluginZip.get().outputs.files.singleFile
        val allFiles: SortedSet<String> = run {
            val collected = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) collected += relativePath.pathString
            }
            collected
        }.toSortedSet()

        val expectedFiles = sortedSetOf(
            "LICENSE",
            ".claude-plugin/plugin.json",
            ".mcp.json",
            "bin/install-devrig",
            "bin/install-devrig.ps1",
            "bin/check-devrig",
            "bin/devrig-progress",
            "bin/devrig-recover",
            "bin/devrig-mcp",
            "bin/devrig-mcp.cmd",
            "bin/offer-ide",
            "commands/help.md",
            "commands/setup.md",
            "commands/status.md",
            "commands/uninstall.md",
            "hooks/hooks.json",
        )

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled files mismatch in :claude-plugin zip!")
                if (missing.isNotEmpty()) { appendLine("Missing:"); missing.forEach { appendLine("  - $it") } }
                if (unexpected.isNotEmpty()) { appendLine("Unexpected:"); unexpected.forEach { appendLine("  - $it") } }
                appendLine("\nActual:"); allFiles.forEach { appendLine("  - $it") }
                appendLine("\nUpdate expectedFiles in claude-plugin/build.gradle.kts if intentional.")
            })
        }
    }
}

// Validates plugin.json is well-formed JSON and contains all required fields
val validatePluginJson = tasks.register("validatePluginJson") {
    group = "verification"
    description = "Validate .claude-plugin/plugin.json structure"

    inputs.file(pluginJsonFile)
    inputs.file(versionFile)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(pluginJsonFile) as Map<String, Any?>
        val requiredFields = listOf("name", "description", "version", "author", "homepage", "repository", "license")
        val missing = requiredFields.filter { json[it].let { v -> v == null || v.toString().isBlank() } }
        if (missing.isNotEmpty()) {
            throw GradleException("plugin.json is missing required fields: $missing")
        }
        val name = json["name"].toString()
        if (name != "devrig") {
            throw GradleException("plugin.json: expected name 'devrig', got '$name'")
        }

        // Version must be a real semver that tracks the released VERSION. Claude Code only offers a
        // marketplace update when this string changes, so a stale placeholder pins every install
        // forever. The committed value must equal the current VERSION normalized to X.Y.Z.
        val manifestVersion = json["version"].toString()
        if (!Regex("""^\d+\.\d+\.\d+$""").matches(manifestVersion)) {
            throw GradleException(
                "plugin.json: version '$manifestVersion' is not semver X.Y.Z. " +
                    "Run: ./gradlew :claude-plugin:syncClaudePluginVersion"
            )
        }
        val expected = normalizeToSemver(versionFile.readText())
        if (manifestVersion != expected) {
            throw GradleException(
                "plugin.json version '$manifestVersion' does not match VERSION-derived '$expected'. " +
                    "The manifest version must track the released VERSION so marketplace users receive updates. " +
                    "Run: ./gradlew :claude-plugin:syncClaudePluginVersion  (then commit claude-plugin/.claude-plugin/plugin.json)"
            )
        }
    }
}

// Validates the POSIX install wrapper delegates to the canonical installer and stays stderr-only
val validateInstallScript = tasks.register("validateInstallScript") {
    group = "verification"
    description = "Validate bin/install-devrig correctness"

    val script = projectDir.resolve("bin/install-devrig")
    inputs.file(script)

    doLast {
        val lines = script.readLines()
        val content = lines.joinToString("\n")

        // Diagnostics must not leak onto stdout (kept consistent with the MCP stdio discipline)
        val bareEcho = lines.filter { line ->
            val trimmed = line.trim()
            trimmed.startsWith("echo") && !trimmed.contains(">&2") && !trimmed.startsWith("#")
        }
        if (bareEcho.isNotEmpty()) {
            throw GradleException(
                "install-devrig: echo without >&2 would write to stdout:\n" +
                    bareEcho.joinToString("\n") { "  $it" }
            )
        }

        // Must delegate to the canonical installer, never reimplement download/checksum/JDK logic
        if (!content.contains("install.sh")) {
            throw GradleException("install-devrig: must delegate to the canonical install.sh")
        }
        if (!content.contains("curl") && !content.contains("wget")) {
            throw GradleException("install-devrig: must fetch via curl or wget")
        }

        // Must fail loudly if the install did not produce the launcher (no silent success)
        if (!content.contains(".mcp-steroid")) {
            throw GradleException("install-devrig: must verify ~/.mcp-steroid devrig launcher after install")
        }
        if (!content.contains("/devrig:setup")) {
            throw GradleException("install-devrig: failure message must point at /devrig:setup")
        }
    }
}

// Validates the Windows install wrapper delegates to the canonical installer and stays stderr-only
val validateInstallPs1 = tasks.register("validateInstallPs1") {
    group = "verification"
    description = "Validate bin/install-devrig.ps1 correctness"

    val script = projectDir.resolve("bin/install-devrig.ps1")
    inputs.file(script)

    doLast {
        val content = script.readText()

        // Must delegate to the canonical Windows installer
        if (!content.contains("install.ps1")) {
            throw GradleException("install-devrig.ps1: must delegate to the canonical install.ps1")
        }
        // Diagnostics go to stderr via [Console]::Error (PowerShell Write-Host would hit stdout)
        if (!content.contains("[Console]::Error")) {
            throw GradleException("install-devrig.ps1: diagnostics must go to stderr via [Console]::Error.WriteLine")
        }
        // Must verify the Windows launcher (devrig.cmd) and fail loudly, pointing at the setup command
        if (!content.contains("devrig.cmd")) {
            throw GradleException("install-devrig.ps1: must verify the devrig.cmd launcher after install")
        }
        if (!content.contains("/devrig:setup")) {
            throw GradleException("install-devrig.ps1: failure message must point at /devrig:setup")
        }
    }
}

// Validates the /devrig:setup slash command runs the bundled wrapper and handles outcomes
val validateSetupCommand = tasks.register("validateSetupCommand") {
    group = "verification"
    description = "Validate commands/setup.md structure"

    val command = projectDir.resolve("commands/setup.md")
    inputs.file(command)

    doLast {
        val content = command.readText()

        // Must have YAML frontmatter with a description (model-discoverable metadata)
        if (!content.startsWith("---")) {
            throw GradleException("setup.md: must start with YAML frontmatter")
        }
        if (!content.contains("description:")) {
            throw GradleException("setup.md: frontmatter must include a description")
        }
        // Must pre-download via the bundled wrapper (the plugin .mcp.json owns registration now)
        if (!content.contains("install-devrig")) {
            throw GradleException("setup.md: must run the bundled install-devrig wrapper")
        }
        // Must NOT re-register: that would duplicate the plugin's own .mcp.json server
        if (content.contains("install claude")) {
            throw GradleException("setup.md: must NOT run 'devrig install claude' (plugin .mcp.json is the registration)")
        }
        // Must clean up any legacy user-scope duplicate
        if (!content.contains("claude mcp remove devrig")) {
            throw GradleException("setup.md: must remove a legacy user-scope 'devrig' entry to avoid duplicate servers")
        }
        // Must tell the user to re-run on failure (the install is resumable)
        if (!content.contains("/devrig:setup")) {
            throw GradleException("setup.md: must tell the user to re-run /devrig:setup on failure")
        }
    }
}

// Validates hooks/hooks.json registers the SessionStart hook that drives the setup nudge
val validateHooksJson = tasks.register("validateHooksJson") {
    group = "verification"
    description = "Validate hooks/hooks.json structure"

    val hooksJsonFile = projectDir.resolve("hooks/hooks.json")
    inputs.file(hooksJsonFile)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(hooksJsonFile) as Map<String, Any?>
        val hooks = json["hooks"] as? Map<*, *>
            ?: throw GradleException("hooks.json: missing 'hooks' object")
        val sessionStart = hooks["SessionStart"] as? List<*>
            ?: throw GradleException("hooks.json: missing 'hooks.SessionStart' array")
        val group = sessionStart.firstOrNull() as? Map<*, *>
            ?: throw GradleException("hooks.json: 'hooks.SessionStart' has no matcher-group entries")
        // Issue #247: the charter must re-fire after compaction (SessionStart source "compact") to
        // restore the dropped IDE framing. That only happens if the matcher stays match-all -- an
        // omitted matcher, or "" / "*". Scoping it to e.g. "startup" would silently regress #247.
        val matcher = group["matcher"]?.toString()
        if (matcher != null && matcher != "" && matcher != "*" && !matcher.contains("compact")) {
            throw GradleException(
                "hooks.json: SessionStart matcher '$matcher' would skip the 'compact' source, dropping the " +
                    "devrig charter after compaction (issue #247). Leave the matcher unset, or use \"\" / \"*\"."
            )
        }
        // Each event entry holds a nested 'hooks' array of {type, command} actions (settings.json shape).
        val actions = group["hooks"] as? List<*>
            ?: throw GradleException("hooks.json: SessionStart entry must contain a nested 'hooks' array")
        val action = actions.firstOrNull() as? Map<*, *>
            ?: throw GradleException("hooks.json: SessionStart 'hooks' array has no entries")
        if (action["type"]?.toString() != "command") {
            throw GradleException("hooks.json: SessionStart hook must be type 'command'")
        }
        val command = action["command"]?.toString().orEmpty()
        if (!command.contains("check-devrig")) {
            throw GradleException("hooks.json: SessionStart command '$command' must run check-devrig")
        }
        if (!command.contains("\${CLAUDE_PLUGIN_ROOT}")) {
            throw GradleException("hooks.json: SessionStart command must locate the script via \${CLAUDE_PLUGIN_ROOT}")
        }

        // Issue #246: the PostToolUse hook injects a recovery hint after a recoverable devrig tool
        // error. It must be scoped to the devrig MCP tools so it never fires on unrelated tools, and it
        // must run bin/devrig-recover via ${CLAUDE_PLUGIN_ROOT}.
        val postToolUse = hooks["PostToolUse"] as? List<*>
            ?: throw GradleException("hooks.json: missing 'hooks.PostToolUse' array (issue #246 recovery hint)")
        val ptuGroup = postToolUse.firstOrNull() as? Map<*, *>
            ?: throw GradleException("hooks.json: 'hooks.PostToolUse' has no matcher-group entries")
        val ptuMatcher = ptuGroup["matcher"]?.toString().orEmpty()
        // The matcher is asserted BY BEHAVIOUR, not by substring: a literal `mcp__devrig__` prefix looks
        // right but never fires in the distribution that ships this hook. When devrig's MCP server comes
        // from THIS plugin, Claude namespaces its tools `mcp__plugin_<plugin>_<server>__<tool>` — e.g.
        // `mcp__plugin_devrig_devrig__steroid_execute_code` — which no `mcp__devrig__…` pattern matches.
        // So the matcher must match both spellings while still ignoring every non-devrig tool.
        val ptuRegex = try {
            Regex(ptuMatcher)
        } catch (e: Exception) {
            throw GradleException("hooks.json: PostToolUse matcher '$ptuMatcher' is not a valid regex: ${e.message}")
        }
        fun matches(tool: String) = ptuRegex.containsMatchIn(tool)
        val mustMatch = listOf(
            "mcp__devrig__steroid_execute_code",                 // standalone `claude mcp add devrig`
            "mcp__plugin_devrig_devrig__steroid_execute_code",   // this plugin's own .mcp.json
            "mcp__plugin_devrig_devrig__steroid_fetch_resource",
        )
        val mustNotMatch = listOf("Bash", "Read", "Edit", "mcp__github__create_issue", "mcp__playwright__click")
        mustMatch.filterNot { matches(it) }.takeIf { it.isNotEmpty() }?.let { missed ->
            throw GradleException(
                "hooks.json: PostToolUse matcher '$ptuMatcher' does not match devrig tool name(s) " +
                    "$missed, so bin/devrig-recover would never run for them (issue #246). Note plugin-" +
                    "provided MCP servers are namespaced 'mcp__plugin_<plugin>_<server>__<tool>'."
            )
        }
        mustNotMatch.filter { matches(it) }.takeIf { it.isNotEmpty() }?.let { overreach ->
            throw GradleException(
                "hooks.json: PostToolUse matcher '$ptuMatcher' also matches unrelated tool(s) " +
                    "$overreach; the recovery hint must fire only on devrig tools."
            )
        }
        val ptuActions = ptuGroup["hooks"] as? List<*>
            ?: throw GradleException("hooks.json: PostToolUse entry must contain a nested 'hooks' array")
        val ptuAction = ptuActions.firstOrNull() as? Map<*, *>
            ?: throw GradleException("hooks.json: PostToolUse 'hooks' array has no entries")
        if (ptuAction["type"]?.toString() != "command") {
            throw GradleException("hooks.json: PostToolUse hook must be type 'command'")
        }
        val ptuCommand = ptuAction["command"]?.toString().orEmpty()
        if (!ptuCommand.contains("devrig-recover")) {
            throw GradleException("hooks.json: PostToolUse command '$ptuCommand' must run devrig-recover")
        }
        if (!ptuCommand.contains("\${CLAUDE_PLUGIN_ROOT}")) {
            throw GradleException("hooks.json: PostToolUse command must locate the script via \${CLAUDE_PLUGIN_ROOT}")
        }
    }
}

// Validates the SessionStart hook script. NB: a hook's STDOUT is its data channel (Claude Code
// parses the JSON), so -- unlike the MCP launcher scripts -- this script deliberately writes JSON
// to stdout and the stderr-only rule does NOT apply here.
val validateCheckDevrig = tasks.register("validateCheckDevrig") {
    group = "verification"
    description = "Validate bin/check-devrig correctness"

    val script = projectDir.resolve("bin/check-devrig")
    inputs.file(script)

    doLast {
        val content = script.readText()

        if (!content.contains(".mcp-steroid"))
            throw GradleException("check-devrig: must test the ~/.mcp-steroid devrig launcher")
        if (!content.contains("exit 0"))
            throw GradleException("check-devrig: must exit 0 (a non-blocking SessionStart hook)")
        if (!content.contains("systemMessage"))
            throw GradleException("check-devrig: must emit a top-level systemMessage when devrig is absent")
        if (!content.contains("is not installed yet"))
            throw GradleException("check-devrig: not-installed branch must surface a clear not-installed message")
        // Live-session charter (issue #245): once devrig is installed, EVERY session must carry the
        // model-only "drive the whole IDE via devrig" charter as additionalContext. Guard its key pieces.
        if (!content.contains("EXTREMELY_IMPORTANT"))
            throw GradleException("check-devrig: the live-session charter must be wrapped in an <EXTREMELY_IMPORTANT> block")
        if (!content.contains("steroid_execute_code") || !content.contains("steroid_fetch_resource"))
            throw GradleException("check-devrig: the charter must name devrig's instruments (steroid_execute_code, steroid_fetch_resource, ...)")
        if (!content.contains("mcp-steroid://prompt/skill"))
            throw GradleException("check-devrig: the charter must point the agent at the mcp-steroid://prompt/skill recipe index")
        if (!content.contains("additionalContext"))
            throw GradleException("check-devrig: the charter must be injected as model-only additionalContext")
        // The failure branch detects the marker and is the ONLY place /devrig:setup is surfaced.
        if (!content.contains("bootstrap-install.failed"))
            throw GradleException("check-devrig: must detect the failure marker (bootstrap-install.failed)")
        if (!content.contains("/devrig:setup"))
            throw GradleException("check-devrig: failure branch must point at /devrig:setup")
    }
}

// Behaviorally runs bin/check-devrig against synthetic HOMEs to prove it recognizes BOTH the POSIX
// (`devrig`) and Windows (`devrig.cmd`) launchers. Content-only checks missed the Windows case: the
// launcher devrig writes on Windows is `~/.mcp-steroid/bin/devrig.cmd` (DevrigUserLauncher), so a hook
// that only tested `bin/devrig` nagged "not installed" on every working Windows session.
// The script is POSIX sh; on a Windows build agent `sh` is absent, so the task disables itself there
// (Gradle-task-level skip for a structurally incompatible platform -- the only sanctioned skip).
val validateCheckDevrigRuns = tasks.register("validateCheckDevrigRuns") {
    group = "verification"
    description = "Run bin/check-devrig against synthetic installs (POSIX + Windows launchers)"

    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val script = projectDir.resolve("bin/check-devrig")
    inputs.file(script)
    val work = layout.buildDirectory.dir("check-devrig-test")
    outputs.dir(work)

    doLast {
        fun runHook(home: java.io.File): String {
            val proc = ProcessBuilder("sh", script.absolutePath)
                .directory(home)
                .also { it.environment()["HOME"] = home.absolutePath }
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0) {
                throw GradleException("check-devrig must exit 0; got ${proc.exitValue()} for HOME=$home")
            }
            return out
        }

        fun makeHome(name: String, launcher: String?, failed: Boolean = false, welcomed: Boolean = false): java.io.File {
            val home = work.get().asFile.resolve(name)
            home.deleteRecursively()
            val bin = home.resolve(".mcp-steroid/bin").apply { mkdirs() }
            if (launcher != null) {
                bin.resolve(launcher).apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            }
            val markers = home.resolve(".mcp-steroid/markers")
            if (failed) {
                markers.apply { mkdirs() }.resolve("bootstrap-install.failed").writeText("network down")
            }
            // The one-time welcome fires only when this marker is absent; pre-create it to model a
            // returning user who was already welcomed on a previous session.
            if (welcomed) {
                markers.apply { mkdirs() }.resolve("welcomed").writeText("")
            }
            return home
        }

        // Every live session (installed + already welcomed) must carry the MODEL-ONLY charter: it holds
        // additionalContext (so the agent keeps using devrig) but NO visible systemMessage (issue #245).
        fun assertLiveCharter(out: String, label: String) {
            if (!out.contains("additionalContext"))
                throw GradleException("check-devrig must inject the devrig charter as additionalContext on a live $label session. Output:\n$out")
            if (!out.contains("EXTREMELY_IMPORTANT") || !out.contains("steroid_execute_code"))
                throw GradleException("check-devrig charter on a live $label session must be the <EXTREMELY_IMPORTANT> block naming devrig's tools. Output:\n$out")
            if (out.contains("systemMessage"))
                throw GradleException("check-devrig charter on a returning $label session must be model-only (no visible systemMessage). Output:\n$out")
        }

        // 1. Windows-style working install, already welcomed -> model-only charter (recognizes .cmd launcher).
        assertLiveCharter(runHook(makeHome("windows", "devrig.cmd", welcomed = true)), "Windows")

        // 2. POSIX-style working install, already welcomed -> model-only charter.
        assertLiveCharter(runHook(makeHome("posix", "devrig", welcomed = true)), "POSIX")

        // 2b. First session after install (no `welcomed` marker) -> emit a ONE-TIME welcome that points
        //     at /devrig:help, never at /devrig:setup, and never claims a background download. Then it
        //     must write the marker and stay SILENT on every later session (idempotent).
        val firstRunHome = makeHome("first-run", "devrig")
        val welcomeOut = runHook(firstRunHome)
        if (!welcomeOut.contains("systemMessage") || !welcomeOut.contains("/devrig:help")) {
            throw GradleException("check-devrig must welcome the user and point at /devrig:help on the first session after install. Output:\n$welcomeOut")
        }
        if (welcomeOut.contains("/devrig:setup") || welcomeOut.contains("background")) {
            throw GradleException("the first-run welcome must not mention /devrig:setup or a background download. Output:\n$welcomeOut")
        }
        if (!firstRunHome.resolve(".mcp-steroid/markers/welcomed").exists()) {
            throw GradleException("check-devrig must write the ~/.mcp-steroid/markers/welcomed marker after welcoming.")
        }
        // After the one-time welcome, later sessions drop the visible systemMessage but STILL carry the
        // model-only charter every time (issue #245) -- the whole point is to keep re-asserting devrig.
        val secondRunOut = runHook(firstRunHome)
        assertLiveCharter(secondRunOut, "returning")

        // 3. Nothing installed, no failure -> the only way in is /devrig:setup (script-only, no download).
        val emptyOut = runHook(makeHome("empty", null))
        if (!emptyOut.contains("systemMessage") || !emptyOut.contains("/devrig:setup")) {
            throw GradleException("check-devrig must point the user at /devrig:setup when devrig is absent. Output:\n$emptyOut")
        }
        if (emptyOut.contains("retry")) {
            throw GradleException("check-devrig must not talk about retrying an install that never ran. Output:\n$emptyOut")
        }

        // 4. Failed install (marker present, no launcher) -> MUST point at /devrig:setup, phrased as a retry.
        val failedOut = runHook(makeHome("failed", null, failed = true))
        if (!failedOut.contains("systemMessage") || !failedOut.contains("/devrig:setup")) {
            throw GradleException("check-devrig must point at /devrig:setup when the install failed. Output:\n$failedOut")
        }
        if (!failedOut.contains("retry")) {
            throw GradleException("check-devrig failure branch must phrase the nudge as a retry. Output:\n$failedOut")
        }
    }
}

// Validates the /devrig:status slash command runs the read-only doctor and never mutates
val validateStatusCommand = tasks.register("validateStatusCommand") {
    group = "verification"
    description = "Validate commands/status.md structure"

    val command = projectDir.resolve("commands/status.md")
    inputs.file(command)

    doLast {
        val content = command.readText()
        if (!content.startsWith("---")) {
            throw GradleException("status.md: must start with YAML frontmatter")
        }
        if (!content.contains("description:")) {
            throw GradleException("status.md: frontmatter must include a description")
        }
        // Must run the read-only doctor — never a mutating install/registration.
        if (!content.contains("install claude --check")) {
            throw GradleException("status.md: must run 'devrig install claude --check' (read-only)")
        }
        if (!content.contains(".mcp-steroid")) {
            throw GradleException("status.md: must reference the ~/.mcp-steroid devrig launcher")
        }
    }
}

// Validates the /devrig:uninstall slash command confirms first and only removes devrig's own state
val validateUninstallCommand = tasks.register("validateUninstallCommand") {
    group = "verification"
    description = "Validate commands/uninstall.md structure"

    val command = projectDir.resolve("commands/uninstall.md")
    inputs.file(command)

    doLast {
        val content = command.readText()
        if (!content.startsWith("---")) {
            throw GradleException("uninstall.md: must start with YAML frontmatter")
        }
        if (!content.contains("description:")) {
            throw GradleException("uninstall.md: frontmatter must include a description")
        }
        // Destructive: must require explicit confirmation before removing anything.
        if (!content.contains("Confirm", ignoreCase = true)) {
            throw GradleException("uninstall.md: must require explicit user confirmation before removing anything")
        }
        // Must unregister from Claude and remove only the devrig install dir.
        if (!content.contains("claude mcp remove")) {
            throw GradleException("uninstall.md: must unregister via 'claude mcp remove'")
        }
        if (!content.contains(".mcp-steroid")) {
            throw GradleException("uninstall.md: must remove the ~/.mcp-steroid install directory")
        }
    }
}

// Validates the /devrig:help discoverability command lists copy-paste example prompts and stays read-only
val validateHelpCommand = tasks.register("validateHelpCommand") {
    group = "verification"
    description = "Validate commands/help.md structure"

    val command = projectDir.resolve("commands/help.md")
    inputs.file(command)

    doLast {
        val content = command.readText()
        if (!content.startsWith("---")) {
            throw GradleException("help.md: must start with YAML frontmatter")
        }
        if (!content.contains("description:")) {
            throw GradleException("help.md: frontmatter must include a description")
        }
        // The point of #227: give the user copy-paste example prompts for the whole-IDE bridge.
        if (!content.contains("run the tests in the open IDE") || !content.contains("find duplicates in this file")) {
            throw GradleException("help.md: must include the copy-paste example prompts that show what the bridge can do")
        }
        // Discoverability is read-only — it must not run or register anything.
        if (!content.contains("read-only", ignoreCase = true)) {
            throw GradleException("help.md: must state it is read-only (it explains capabilities; it does not run anything unprompted)")
        }
    }
}

// Validates the repo-root marketplace.json that lists this plugin under the 'devrig' name
val validateMarketplaceJson = tasks.register("validateMarketplaceJson") {
    group = "verification"
    description = "Validate .claude-plugin/marketplace.json structure"

    val marketplaceFile = rootProject.projectDir.resolve(".claude-plugin/marketplace.json")
    // Claude keys a marketplace by the `name` DECLARED here (see ~/.claude/plugins/known_marketplaces.json),
    // so `devrig connect claude` and the IDE's onboarding check must use the same name — otherwise they
    // write / look for `devrig@<wrong-marketplace>`, which names no real plugin. Both constants are
    // cross-checked below.
    val connectSource = rootProject.projectDir
        .resolve("npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ClaudePluginConnect.kt")
    val onboardingSource = rootProject.projectDir
        .resolve("ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/onboarding/OnboardingDecision.kt")
    inputs.file(marketplaceFile)
    inputs.file(connectSource)
    inputs.file(onboardingSource)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(marketplaceFile) as Map<String, Any?>
        listOf("name", "owner", "plugins").forEach { field ->
            if (json[field] == null) throw GradleException("marketplace.json: missing required field '$field'")
        }
        val plugins = json["plugins"] as? List<*>
            ?: throw GradleException("marketplace.json: 'plugins' must be an array")
        @Suppress("UNCHECKED_CAST")
        val devrig = plugins.filterIsInstance<Map<String, Any?>>().singleOrNull { it["name"] == "devrig" }
            ?: throw GradleException("marketplace.json: must list exactly one plugin named 'devrig'")
        if (devrig["source"] != "./claude-plugin") {
            throw GradleException("marketplace.json: the 'devrig' plugin source must be './claude-plugin', got '${devrig["source"]}'")
        }

        val marketplaceName = json["name"].toString()
        val pluginKey = "devrig@$marketplaceName"

        fun assertConstant(file: java.io.File, constant: String, expected: String) {
            val text = file.readText()
            val actual = Regex("""const\s+val\s+$constant\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
                ?: throw GradleException("${file.name}: could not find `const val $constant = \"…\"`")
            if (actual != expected) {
                throw GradleException(
                    "${file.name}: $constant is '$actual' but .claude-plugin/marketplace.json declares " +
                        "name '$marketplaceName', so it must be '$expected'. Claude keys marketplaces by " +
                        "that declared name, so a mismatch makes the enabled-plugin key name no real plugin."
                )
            }
        }
        assertConstant(connectSource, "CLAUDE_MARKETPLACE_NAME", marketplaceName)
        assertConstant(onboardingSource, "CLAUDE_DEVRIG_PLUGIN_KEY", pluginKey)
    }
}

val validateMcpJson = tasks.register("validateMcpJson") {
    group = "verification"
    description = "Validate .mcp.json registers one devrig stdio server via the POSIX launcher"
    val f = projectDir.resolve(".mcp.json")
    inputs.file(f)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        val servers = json["mcpServers"] as? Map<*, *> ?: throw GradleException(".mcp.json: missing mcpServers")
        val devrig = servers["devrig"] as? Map<*, *> ?: throw GradleException(".mcp.json: missing 'devrig' server")
        if (devrig["type"] != "stdio") throw GradleException(".mcp.json: devrig must be stdio")
        val cmd = devrig["command"]?.toString().orEmpty()
        if (!cmd.contains("\${CLAUDE_PLUGIN_ROOT}") || !cmd.contains("/bin/devrig-mcp") || cmd.endsWith(".cmd"))
            throw GradleException(".mcp.json: command must be \${CLAUDE_PLUGIN_ROOT}/bin/devrig-mcp (extensionless), got '$cmd'")
    }
}

// Static checks on both MCP launchers (bin/devrig-mcp -- POSIX, referenced by .mcp.json -- and the legacy
// bin/devrig-mcp.cmd polyglot). Script-only, #253: neither bundles a bootstrap fallback anymore; both must
// exec the INSTALLED devrig directly and emit nothing on stdout before exec (stdout is the JSON-RPC channel).
val validateDevrigMcpLauncher = tasks.register("validateDevrigMcpLauncher") {
    group = "verification"
    description = "Validate bin/devrig-mcp(.cmd) exec the installed devrig and write nothing to stdout pre-exec"
    val posix = projectDir.resolve("bin/devrig-mcp")
    val cmd = projectDir.resolve("bin/devrig-mcp.cmd")
    inputs.file(posix)
    inputs.file(cmd)
    doLast {
        // bin/devrig-mcp -- the new POSIX-only launcher .mcp.json actually invokes.
        val p = posix.readText()
        if (!p.startsWith("#!/bin/sh"))
            throw GradleException("devrig-mcp: must start with #!/bin/sh")
        if (!p.contains("\$HOME/.mcp-steroid/bin/devrig") || !p.contains("mcp"))
            throw GradleException("devrig-mcp: must exec \$HOME/.mcp-steroid/bin/devrig mcp")
        if (p.contains("bootstrap"))
            throw GradleException("devrig-mcp: must NOT reference a bundled bootstrap (script-only, #253)")
        posix.readLines().forEach { ln ->
            val t = ln.trim()
            if (t.startsWith("echo ") && !t.contains(">&2"))
                throw GradleException("devrig-mcp: stdout echo would corrupt JSON-RPC: $ln")
        }

        // bin/devrig-mcp.cmd -- legacy polyglot; same contract, both halves.
        val c = cmd.readText()
        if (!c.contains(".mcp-steroid")) throw GradleException("devrig-mcp.cmd: must check the installed launcher path")
        if (!c.contains("devrig")) throw GradleException("devrig-mcp.cmd: must exec the installed devrig")
        if (c.contains("bootstrap"))
            throw GradleException("devrig-mcp.cmd: must NOT reference a bundled bootstrap (script-only, #253)")
        // No bare `echo`/`Write-Host` to stdout: POSIX echoes must be >&2; cmd echoes must be 1>&2.
        cmd.readLines().forEach { ln ->
            val t = ln.trim().removePrefix(":;").trim()
            if (t.startsWith("echo ") && !t.contains(">&2"))
                throw GradleException("devrig-mcp.cmd: stdout echo would corrupt JSON-RPC: $ln")
        }
    }
}

// Script-only, #253: there is no bundled bootstrap fallback anymore, so this only proves the launcher execs
// whatever is installed at $HOME/.mcp-steroid/bin/devrig, with nothing extra on stdout pre-exec. The
// devrig-absent case is exercised by check-devrig/devrig-progress (they nudge /devrig:setup); the launcher
// itself has no absent-devrig behavior to assert beyond "exec fails, no stray stdout", which we still check.
val validateDevrigMcpLauncherRuns = tasks.register("validateDevrigMcpLauncherRuns") {
    group = "verification"
    description = "Run bin/devrig-mcp(.cmd) against synthetic HOMEs (execs installed devrig + stdout silence)"
    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val posixScript = projectDir.resolve("bin/devrig-mcp")
    val cmdScript = projectDir.resolve("bin/devrig-mcp.cmd")
    inputs.file(posixScript)
    inputs.file(cmdScript)
    val work = layout.buildDirectory.dir("devrig-mcp-test")
    outputs.dir(work)

    doLast {
        fun run(launcher: java.io.File, home: java.io.File): Pair<String, String> {
            val p = ProcessBuilder(launcher.absolutePath)
                .also { it.environment()["HOME"] = home.absolutePath }
                .redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            return out to err
        }

        fun checkLauncher(script: java.io.File, label: String) {
            val staged = work.get().asFile.resolve("$label/launcher").apply { parentFile.mkdirs() }
            script.copyTo(staged, overwrite = true).setExecutable(true)

            // 1. devrig absent -> exec fails (no installed target); the launcher itself must still emit
            //    nothing on stdout -- any complaint goes to stderr, never corrupting the JSON-RPC channel.
            val absent = work.get().asFile.resolve("$label/absent").apply { mkdirs() }
            val (aout, _) = run(staged, absent)
            if (aout.isNotEmpty())
                throw GradleException("$label absent HOME: stdout must stay empty when devrig isn't installed; got: $aout")

            // 2. devrig present -> execs the installed launcher (our fake marks its own stdout).
            val present = work.get().asFile.resolve("$label/present").apply { mkdirs() }
            present.resolve(".mcp-steroid/bin").mkdirs()
            present.resolve(".mcp-steroid/bin/devrig").apply { writeText("#!/bin/sh\necho INSTALLED_RAN\n"); setExecutable(true) }
            val (pout, _) = run(staged, present)
            if (pout.trimEnd('\n') != "INSTALLED_RAN")
                throw GradleException("$label present HOME: stdout must be exactly INSTALLED_RAN (launcher emitted extra stdout?); got: $pout")
        }

        checkLauncher(posixScript, "posix")
        checkLauncher(cmdScript, "cmd")
    }
}

// Validates bin/devrig-progress (the UserPromptSubmit hook). Content-only guards: once devrig is LIVE
// (issue #245) an IDE-shaped prompt must re-remind the model that the IDE bridge exists, via MODEL-ONLY
// additionalContext (no visible systemMessage). NB: this hook writes JSON to stdout on purpose (stdout is
// the hook data channel), so the stderr-only MCP-launcher rule does NOT apply here.
val validateDevrigProgress = tasks.register("validateDevrigProgress") {
    group = "verification"
    description = "Validate bin/devrig-progress live-phase nudge correctness"

    val script = projectDir.resolve("bin/devrig-progress")
    inputs.file(script)

    doLast {
        val content = script.readText()
        if (!content.contains(".mcp-steroid/bin/devrig"))
            throw GradleException("devrig-progress: must detect the LIVE state via the installed ~/.mcp-steroid/bin/devrig launcher")
        if (!content.contains("additionalContext"))
            throw GradleException("devrig-progress: live-phase nudge must inject model-only additionalContext")
        // Model-only: the live-phase nudge must NOT surface a top-level systemMessage. The word may still
        // appear in the download-progress branch below, so require the nudge JSON to be additionalContext-only.
        if (!content.contains("prefer") || !content.contains("devrig"))
            throw GradleException("devrig-progress: nudge must tell the model a JetBrains IDE is connected via devrig and to prefer its tools")
        if (!content.contains("steroid_execute_code") || !content.contains("steroid_fetch_resource"))
            throw GradleException("devrig-progress: nudge must point at steroid_execute_code and steroid_fetch_resource")
        if (!content.contains("mcp-steroid://prompt/skill"))
            throw GradleException("devrig-progress: nudge must point at the mcp-steroid://prompt/skill recipe index")
        if (!content.contains("exit 0"))
            throw GradleException("devrig-progress: must exit 0 (a non-blocking UserPromptSubmit hook)")
    }
}

// Behaviorally runs bin/devrig-progress against synthetic HOMEs with the hook JSON piped on stdin, proving:
// live + IDE-shaped prompt -> additionalContext (no systemMessage); live + off-topic prompt -> silent {};
// not-live -> the new branch never fires. POSIX sh; disables itself on a Windows agent (no `sh`).
val validateDevrigProgressRuns = tasks.register("validateDevrigProgressRuns") {
    group = "verification"
    description = "Run bin/devrig-progress against synthetic live/not-live HOMEs (stdin-piped prompts)"

    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val script = projectDir.resolve("bin/devrig-progress")
    inputs.file(script)
    val work = layout.buildDirectory.dir("devrig-progress-test")
    outputs.dir(work)

    doLast {
        fun runHook(home: java.io.File, stdin: String): String {
            val proc = ProcessBuilder("sh", script.absolutePath)
                .directory(home)
                .also { it.environment()["HOME"] = home.absolutePath }
                .redirectErrorStream(false)
                .start()
            proc.outputStream.bufferedWriter().use { it.write(stdin) }
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0)
                throw GradleException("devrig-progress must exit 0; got ${proc.exitValue()} for HOME=$home")
            return out
        }

        fun makeHome(name: String, live: Boolean): java.io.File {
            val home = work.get().asFile.resolve(name)
            home.deleteRecursively()
            val bin = home.resolve(".mcp-steroid/bin").apply { mkdirs() }
            if (live) bin.resolve("devrig").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            return home
        }

        // 1. LIVE + IDE-shaped prompt -> model-only additionalContext, and NO visible systemMessage.
        val liveHome = makeHome("live", live = true)
        val ideOut = runHook(liveHome, """{"prompt":"run the tests in the open IDE"}""")
        if (!ideOut.contains("additionalContext"))
            throw GradleException("devrig-progress must inject additionalContext for an IDE-shaped prompt when live. Output:\n$ideOut")
        if (ideOut.contains("systemMessage"))
            throw GradleException("devrig-progress live nudge must be model-only (no systemMessage). Output:\n$ideOut")

        // 2. LIVE + off-topic prompt -> stay SILENT ({} only).
        val offOut = runHook(liveHome, """{"prompt":"what is the capital of France"}""")
        if (offOut.contains("additionalContext"))
            throw GradleException("devrig-progress must NOT nudge on a non-IDE prompt. Output:\n$offOut")

        // 2b. LIVE + a search/navigation prompt -> the expanded keyword set (issue #245 follow-up) must fire.
        val searchOut = runHook(liveHome, """{"prompt":"where is this class defined and who calls it"}""")
        if (!searchOut.contains("additionalContext"))
            throw GradleException("devrig-progress must nudge on code-navigation prompts (expanded keywords). Output:\n$searchOut")

        // 3. NOT live (no launcher) -> live branch must never fire, even on an IDE-shaped prompt. With no
        //    statusline.owner marker and no reachable bootstrap, the hook stays silent.
        val notLiveOut = runHook(makeHome("notlive", live = false), """{"prompt":"run the tests in the open IDE"}""")
        if (notLiveOut.contains("additionalContext"))
            throw GradleException("devrig-progress must not inject the live nudge before devrig is installed. Output:\n$notLiveOut")
    }
}

// Validates bin/devrig-recover (the PostToolUse hook, issue #246). Content-only guards: it must scan the
// tool result for each recoverable signature and inject a MODEL-ONLY recovery hint (additionalContext, no
// systemMessage). NB: this hook writes JSON to stdout on purpose (stdout is the hook data channel), so the
// stderr-only MCP-launcher rule does NOT apply here.
val validateDevrigRecover = tasks.register("validateDevrigRecover") {
    group = "verification"
    description = "Validate bin/devrig-recover PostToolUse recovery-hint correctness"

    val script = projectDir.resolve("bin/devrig-recover")
    inputs.file(script)

    doLast {
        val content = script.readText()
        // Recovery hints steer the model, not the user: additionalContext only, never a visible banner.
        if (!content.contains("additionalContext"))
            throw GradleException("devrig-recover: must inject the recovery hint as additionalContext")
        if (content.contains("systemMessage"))
            throw GradleException("devrig-recover: recovery hints must be model-only (no visible systemMessage)")
        if (!content.contains("PostToolUse"))
            throw GradleException("devrig-recover: additionalContext must set hookEventName to PostToolUse")
        // All four recoverable conditions from issue #246 must be recognized.
        if (!content.contains("No IntelliJ IDE with the MCP Steroid plugin is running"))
            throw GradleException("devrig-recover: must recognize the no-IDE-reachable signature")
        if (!content.contains("No open project matches your working directory") ||
            !content.contains("Project not found:"))
            throw GradleException("devrig-recover: must recognize the project-routing signatures")
        if (!content.contains("Dumb mode") || !content.contains("did not reach smart mode"))
            throw GradleException("devrig-recover: must recognize the indexing/dumb-mode signatures")
        if (!content.contains("requires a non-modal IDE"))
            throw GradleException("devrig-recover: must recognize the modal-dialog signature")
        // The hints must name the concrete next action for each condition.
        if (!content.contains("steroid_list_projects"))
            throw GradleException("devrig-recover: project hint must point at steroid_list_projects")
        if (!content.contains("smartReadAction"))
            throw GradleException("devrig-recover: indexing hint must point at smartReadAction { }")
        if (!content.contains("modal=smart_non_modal"))
            throw GradleException("devrig-recover: modal hint must point at modal=smart_non_modal")
        if (!content.contains("exit 0"))
            throw GradleException("devrig-recover: must exit 0 (a non-blocking PostToolUse hook)")
    }
}

// Behaviorally runs bin/devrig-recover with a synthetic PostToolUse payload piped on stdin, proving each
// recoverable signature yields the right MODEL-ONLY hint and a benign result stays silent ({}). POSIX sh;
// disables itself on a Windows agent (no `sh`) — the same sanctioned Gradle-task-level skip as the sibling
// hook runners.
val validateDevrigRecoverRuns = tasks.register("validateDevrigRecoverRuns") {
    group = "verification"
    description = "Run bin/devrig-recover against synthetic PostToolUse payloads (stdin-piped)"

    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val script = projectDir.resolve("bin/devrig-recover")
    inputs.file(script)
    val work = layout.buildDirectory.dir("devrig-recover-test")
    outputs.dir(work)

    doLast {
        fun runHook(stdin: String): String {
            val proc = ProcessBuilder("sh", script.absolutePath)
                .redirectErrorStream(false)
                .start()
            proc.outputStream.bufferedWriter().use { it.write(stdin) }
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0)
                throw GradleException("devrig-recover must exit 0; got ${proc.exitValue()}")
            return out
        }

        // Wraps an error phrase in a realistic PostToolUse tool_response envelope.
        fun payload(errorText: String): String =
            """{"tool_name":"mcp__devrig__steroid_execute_code","tool_response":{"content":[{"type":"text","text":"$errorText"}],"isError":true}}"""

        fun assertHint(out: String, mustContain: String, label: String) {
            if (!out.contains("additionalContext"))
                throw GradleException("devrig-recover must inject additionalContext for the $label case. Output:\n$out")
            if (out.contains("systemMessage"))
                throw GradleException("devrig-recover $label hint must be model-only (no systemMessage). Output:\n$out")
            if (!out.contains(mustContain))
                throw GradleException("devrig-recover $label hint must mention '$mustContain'. Output:\n$out")
        }

        // 1. No IDE reachable -> steer to opening an IDE / starting a backend, never to grep/sed.
        assertHint(
            runHook(payload("No IntelliJ IDE with the MCP Steroid plugin is running. Open your project in IntelliJ, then retry.")),
            "backend", "no-IDE",
        )
        // 2. Project routing (not-found / ambiguous / stale) -> steroid_list_projects + exact project_name.
        assertHint(
            runHook(payload("Project not found: \\\"foo\\\". Available projects: [bar, baz]")),
            "steroid_list_projects", "project-not-found",
        )
        assertHint(
            runHook(payload("Multiple open projects match your working directory (/tmp/x): a, b. Pass project_name.")),
            "steroid_list_projects", "project-ambiguous",
        )
        // 3. Indexing / dumb mode -> smartReadAction { } / awaitConfiguration.
        assertHint(
            runHook(payload("waitForSmartMode did not reach smart mode within 60s — indexing may be stuck.")),
            "smartReadAction", "indexing",
        )
        // 4. Modal dialog -> modal=smart_non_modal / closeModalDialogs().
        assertHint(
            runHook(payload("A modal dialog appeared while the script was running — closed 1 dialog(s) and failed the run.")),
            "modal=smart_non_modal", "modal",
        )
        // 5. Benign success -> stay silent ({} only, no hint).
        val benign = runHook("""{"tool_name":"mcp__devrig__steroid_execute_code","tool_response":{"content":[{"type":"text","text":"BUILD SUCCESSFUL in 3s"}],"isError":false}}""")
        if (benign.contains("additionalContext"))
            throw GradleException("devrig-recover must stay silent on a successful result. Output:\n$benign")
        // 6. Unrecognized error -> also silent (ExecutionSuggestionService owns compile/threading tips).
        val unknown = runHook(payload("Unresolved reference: fooBar"))
        if (unknown.contains("additionalContext"))
            throw GradleException("devrig-recover must NOT fire on errors it does not own (e.g. compile errors). Output:\n$unknown")
    }
}

// Runs bin/offer-ide against a fake $HOME whose `devrig` exits with a chosen code, and asserts the
// once-per-machine `ide-offered` marker is burned ONLY for outcomes devrig actually reported (0 = offered
// or already connected, 1 = manual instructions). Any other code means no offer happened — 2 = NO_IDE,
// 64 = an older devrig without `connect ide` (a plugin update can land before the devrig update), 126/127
// = launch failure — and burning the marker there would silently lose the offer forever.
val validateOfferIdeRuns = tasks.register("validateOfferIdeRuns") {
    group = "verification"
    description = "Run bin/offer-ide against fake HOMEs and assert the once-per-machine marker gating"

    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val script = projectDir.resolve("bin/offer-ide")
    inputs.file(script)
    val work = layout.buildDirectory.dir("offer-ide-test")
    outputs.dir(work)

    doLast {
        val root = work.get().asFile
        root.deleteRecursively()

        // A fake $HOME whose ~/.mcp-steroid/bin/devrig exits with [exitCode] (null = devrig not installed).
        fun makeHome(name: String, exitCode: Int?): java.io.File {
            val home = java.io.File(root, name).apply { mkdirs() }
            java.io.File(home, ".mcp-steroid/markers").mkdirs()
            if (exitCode != null) {
                val bin = java.io.File(home, ".mcp-steroid/bin").apply { mkdirs() }
                java.io.File(bin, "devrig").apply {
                    writeText("#!/bin/sh\necho \"fake devrig $*\" >&2\nexit $exitCode\n")
                    setExecutable(true)
                }
            }
            return home
        }

        fun runHook(home: java.io.File): String {
            val proc = ProcessBuilder("sh", script.absolutePath)
                .also { it.environment()["HOME"] = home.absolutePath }
                .redirectErrorStream(false)
                .start()
            proc.outputStream.close()
            val out = proc.inputStream.bufferedReader().readText()
            proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0)
                throw GradleException("offer-ide must exit 0; got ${proc.exitValue()} for ${home.name}")
            return out
        }

        fun marker(home: java.io.File) = java.io.File(home, ".mcp-steroid/markers/ide-offered")

        // Outcomes devrig reported -> burn the marker (the offer happened; never repeat it).
        for (code in listOf(0, 1)) {
            val home = makeHome("burn-$code", code)
            val out = runHook(home)
            if (!marker(home).isFile)
                throw GradleException("offer-ide must burn the marker for exit $code (the offer was made)")
            if (!out.contains("additionalContext"))
                throw GradleException("offer-ide must emit the SessionStart nudge for exit $code. Output:\n$out")
        }

        // No offer happened -> keep the marker unwritten so a later session retries once.
        for (code in listOf(2, 64, 127)) {
            val home = makeHome("keep-$code", code)
            runHook(home)
            if (marker(home).isFile)
                throw GradleException(
                    "offer-ide must NOT burn the marker for exit $code — no offer was made, so a later " +
                        "session must be able to offer again (2 = no IDE yet, 64 = devrig without " +
                        "'connect ide', 127 = launch failure)."
                )
        }

        // devrig not installed -> silent no-op (check-devrig owns the install nudge), marker untouched.
        val notInstalled = makeHome("not-installed", null)
        val silent = runHook(notInstalled)
        if (silent.isNotBlank())
            throw GradleException("offer-ide must stay silent when devrig is absent. Output:\n$silent")
        if (marker(notInstalled).isFile)
            throw GradleException("offer-ide must not burn the marker when devrig is absent")

        // Already offered -> exits immediately without invoking devrig again.
        val offered = makeHome("already-offered", 0)
        marker(offered).writeText("")
        val second = runHook(offered)
        if (second.isNotBlank())
            throw GradleException("offer-ide must stay silent once the marker exists. Output:\n$second")
    }
}

claudePluginZip.configure { finalizedBy(verifyPluginFiles) }

// Rewrites the committed .claude-plugin/plugin.json `version` to the current VERSION (normalized
// to semver). Run this during a release, in the same commit as the VERSION bump, so the change
// lands on main and marketplace `/plugin update` picks up the released version. Writes into the
// tracked source tree on purpose; guarded by validatePluginJson.
val syncClaudePluginVersion = tasks.register("syncClaudePluginVersion") {
    group = "claude-plugin"
    description = "Sync .claude-plugin/plugin.json version from the root VERSION file"
    // No outputs declared: this writes into the tracked source tree on purpose, so it always runs
    // and never becomes an implicit dependency of the validators.
    inputs.file(versionFile)
    doLast {
        val target = normalizeToSemver(versionFile.readText())
        val current = pluginJsonFile.readText()
        val patched = patchPluginJsonVersion(current, target)
        if (patched != current) {
            pluginJsonFile.writeText(patched)
            logger.lifecycle("plugin.json version -> $target")
        } else {
            logger.lifecycle("plugin.json version already $target")
        }
    }
}

tasks.named("assemble") { dependsOn(claudePluginZip) }
tasks.named("check") {
    dependsOn(verifyPluginFiles)
    dependsOn(validatePluginJson)
    dependsOn(validateInstallScript)
    dependsOn(validateInstallPs1)
    dependsOn(validateSetupCommand)
    dependsOn(validateStatusCommand)
    dependsOn(validateUninstallCommand)
    dependsOn(validateHelpCommand)
    dependsOn(validateHooksJson)
    dependsOn(validateCheckDevrig)
    dependsOn(validateCheckDevrigRuns)
    dependsOn(validateMarketplaceJson)
    dependsOn(validateMcpJson)
    dependsOn(validateDevrigMcpLauncher)
    dependsOn(validateDevrigMcpLauncherRuns)
    dependsOn(validateDevrigProgress)
    dependsOn(validateDevrigProgressRuns)
    dependsOn(validateDevrigRecover)
    dependsOn(validateDevrigRecoverRuns)
    dependsOn(validateOfferIdeRuns)
}

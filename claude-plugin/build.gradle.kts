import groovy.json.JsonSlurper
import org.gradle.api.tasks.bundling.Zip
import java.util.SortedSet

plugins {
    base
}

val pluginVersion = version.toString()

val bootstrapBins by configurations.creating { isCanBeResolved = true; isCanBeConsumed = false }
dependencies { bootstrapBins(project(mapOf("path" to ":devrig-bootstrap", "configuration" to "bootstrapBinaries"))) }

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

        // .claude-plugin/plugin.json -- inject version
        val pluginDir = out.resolve(".claude-plugin").also { it.mkdirs() }
        val pluginJson = sourceDir.resolve(".claude-plugin/plugin.json").readText()
        pluginDir.resolve("plugin.json").writeText(
            pluginJson.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$pluginVersion"""")
        )

        // bin/ -- copy everything from the committed source bin/ (the bootstrap-* binaries are
        // committed there too; see updateBundledBinaries/verifyBundledBinariesUpToDate). Windows
        // artifacts (.ps1, .exe) carry no POSIX exec bit; everything else Claude must spawn -- the
        // POSIX scripts, the devrig-mcp.cmd polyglot launcher, and the suffix-less Go bootstrap
        // binaries -- is marked executable.
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
            "bin/devrig-mcp.cmd",
            "bin/bootstrap-darwin-arm64",
            "bin/bootstrap-darwin-amd64",
            "bin/bootstrap-linux-amd64",
            "bin/bootstrap-linux-arm64",
            "bin/bootstrap-windows-amd64.exe",
            "bin/bootstrap-windows-arm64.exe",
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

    val pluginJsonFile = projectDir.resolve(".claude-plugin/plugin.json")
    inputs.file(pluginJsonFile)

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
        if (!content.contains("background"))
            throw GradleException("check-devrig: downloading message must describe the background download, not a registration nag")
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

        // 1. Windows-style working install, already welcomed -> hook must stay SILENT.
        val winOut = runHook(makeHome("windows", "devrig.cmd", welcomed = true))
        if (winOut.isNotBlank()) {
            throw GradleException(
                "check-devrig falsely nagged on a working WINDOWS install (launcher devrig.cmd present). " +
                    "It must recognize the .cmd launcher. Output:\n$winOut"
            )
        }

        // 2. POSIX-style working install, already welcomed -> hook must stay SILENT.
        val posixOut = runHook(makeHome("posix", "devrig", welcomed = true))
        if (posixOut.isNotBlank()) {
            throw GradleException("check-devrig falsely nagged on a working POSIX install. Output:\n$posixOut")
        }

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
        val secondRunOut = runHook(firstRunHome)
        if (secondRunOut.isNotBlank()) {
            throw GradleException("check-devrig must stay silent on sessions after the one-time welcome. Output:\n$secondRunOut")
        }

        // 3. Nothing installed, no failure -> report background download, and must NOT surface /devrig:setup.
        val emptyOut = runHook(makeHome("empty", null))
        if (!emptyOut.contains("systemMessage") || !emptyOut.contains("background")) {
            throw GradleException("check-devrig must report the background download when devrig is absent. Output:\n$emptyOut")
        }
        if (emptyOut.contains("/devrig:setup")) {
            throw GradleException("check-devrig must NOT mention /devrig:setup while merely downloading. Output:\n$emptyOut")
        }

        // 4. Failed install (marker present, no launcher) -> MUST point at /devrig:setup.
        val failedOut = runHook(makeHome("failed", null, failed = true))
        if (!failedOut.contains("systemMessage") || !failedOut.contains("/devrig:setup")) {
            throw GradleException("check-devrig must point at /devrig:setup when the install failed. Output:\n$failedOut")
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
    inputs.file(marketplaceFile)

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
    }
}

val validateMcpJson = tasks.register("validateMcpJson") {
    group = "verification"
    description = "Validate .mcp.json registers one devrig stdio server via the polyglot launcher"
    val f = projectDir.resolve(".mcp.json")
    inputs.file(f)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        val servers = json["mcpServers"] as? Map<*, *> ?: throw GradleException(".mcp.json: missing mcpServers")
        val devrig = servers["devrig"] as? Map<*, *> ?: throw GradleException(".mcp.json: missing 'devrig' server")
        if (devrig["type"] != "stdio") throw GradleException(".mcp.json: devrig must be stdio")
        val cmd = devrig["command"]?.toString().orEmpty()
        if (!cmd.contains("\${CLAUDE_PLUGIN_ROOT}") || !cmd.contains("/bin/devrig-mcp.cmd"))
            throw GradleException(".mcp.json: command must be \${CLAUDE_PLUGIN_ROOT}/bin/devrig-mcp.cmd, got '$cmd'")
    }
}

val validateDevrigMcpLauncher = tasks.register("validateDevrigMcpLauncher") {
    group = "verification"
    description = "Validate bin/devrig-mcp.cmd routes correctly and writes nothing to stdout pre-exec"
    val s = projectDir.resolve("bin/devrig-mcp.cmd")
    inputs.file(s)
    doLast {
        val c = s.readText()
        if (!c.contains(".mcp-steroid")) throw GradleException("devrig-mcp.cmd: must check the installed launcher path")
        if (!c.contains("bootstrap-")) throw GradleException("devrig-mcp.cmd: must reference the bundled bootstrap")
        // No bare `echo`/`Write-Host` to stdout: POSIX echoes must be >&2; cmd echoes must be 1>&2.
        s.readLines().forEach { ln ->
            val t = ln.trim().removePrefix(":;").trim()
            if (t.startsWith("echo ") && !t.contains(">&2"))
                throw GradleException("devrig-mcp.cmd: stdout echo would corrupt JSON-RPC: $ln")
        }
    }
}

val validateDevrigMcpLauncherRuns = tasks.register("validateDevrigMcpLauncherRuns") {
    group = "verification"
    description = "Run bin/devrig-mcp.cmd against synthetic HOMEs (POSIX routing + stdout silence)"
    enabled = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val script = projectDir.resolve("bin/devrig-mcp.cmd")
    inputs.file(script)
    val work = layout.buildDirectory.dir("devrig-mcp-test")
    outputs.dir(work)

    doLast {
        // Routing-to-bootstrap requires a present, executable bootstrap for THIS os/arch.
        // Provide a fake one so the launcher's exec target exists.
        val os = System.getProperty("os.name").lowercase().let { if (it.contains("mac")) "darwin" else "linux" }
        val arch = System.getProperty("os.arch").lowercase().let { if (it.contains("aarch64") || it.contains("arm")) "arm64" else "amd64" }
        val fakeBoot = work.get().asFile.resolve("plugin/bin/bootstrap-$os-$arch")
        fakeBoot.parentFile.mkdirs()
        fakeBoot.writeText("#!/bin/sh\necho BOOTSTRAP_RAN\n"); fakeBoot.setExecutable(true)
        script.copyTo(work.get().asFile.resolve("plugin/bin/devrig-mcp.cmd"), overwrite = true).setExecutable(true)

        fun run(home: java.io.File): Pair<String, String> {
            val launcher = work.get().asFile.resolve("plugin/bin/devrig-mcp.cmd")
            val p = ProcessBuilder(launcher.absolutePath)
                .also { it.environment()["HOME"] = home.absolutePath }
                .also { it.environment()["CLAUDE_PLUGIN_ROOT"] = work.get().asFile.resolve("plugin").absolutePath }
                .redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            return out to err
        }

        // 1. devrig absent -> routes to bootstrap (our fake prints BOOTSTRAP_RAN on stdout).
        val absent = work.get().asFile.resolve("absent").apply { mkdirs() }
        val (aout, _) = run(absent)
        if (aout.trimEnd('\n') != "BOOTSTRAP_RAN")
            throw GradleException("absent HOME: stdout must be exactly BOOTSTRAP_RAN (launcher emitted extra stdout?); got: $aout")

        // 2. devrig present -> routes to installed launcher (prints INSTALLED_RAN).
        val present = work.get().asFile.resolve("present").apply { mkdirs() }
        present.resolve(".mcp-steroid/bin").mkdirs()
        present.resolve(".mcp-steroid/bin/devrig").apply { writeText("#!/bin/sh\necho INSTALLED_RAN\n"); setExecutable(true) }
        val (pout, _) = run(present)
        if (pout.trimEnd('\n') != "INSTALLED_RAN")
            throw GradleException("present HOME: stdout must be exactly INSTALLED_RAN (launcher emitted extra stdout?); got: $pout")
    }
}

claudePluginZip.configure { finalizedBy(verifyPluginFiles) }

// Refreshes the committed bin/bootstrap-* from a fresh :devrig-bootstrap build. Run this after
// changing the Go sources, then commit the updated binaries. Not part of build/check (it writes
// into the tracked source tree on purpose).
val updateBundledBinaries = tasks.register("updateBundledBinaries") {
    group = "claude-plugin"
    description = "Refresh committed claude-plugin/bin/bootstrap-* from a fresh :devrig-bootstrap build"
    inputs.files(bootstrapBins)
    doLast {
        val binDir = projectDir.resolve("bin")
        bootstrapBins.singleFile.listFiles { f -> f.name.startsWith("bootstrap-") }?.forEach { f ->
            val dest = binDir.resolve(f.name)
            f.copyTo(dest, overwrite = true)
            if (!f.name.endsWith(".exe")) dest.setExecutable(true)
        }
    }
}

// Drift guard: the committed bin/bootstrap-* MUST match a fresh, reproducible :devrig-bootstrap
// build (byte-for-byte; reproducibility comes from -buildid= + the pinned Go toolchain). Fails
// loudly if a Go change was not accompanied by `updateBundledBinaries` + a commit.
val verifyBundledBinariesUpToDate = tasks.register("verifyBundledBinariesUpToDate") {
    group = "verification"
    description = "Ensure committed bin/bootstrap-* match a fresh :devrig-bootstrap build"
    inputs.files(bootstrapBins)
    inputs.dir(projectDir.resolve("bin"))
    doLast {
        val fresh = bootstrapBins.singleFile
        val binDir = projectDir.resolve("bin")
        val freshBins = fresh.listFiles { f -> f.name.startsWith("bootstrap-") }?.sortedBy { it.name }.orEmpty()
        if (freshBins.isEmpty()) throw GradleException(":devrig-bootstrap produced no bootstrap binaries")
        val problems = mutableListOf<String>()
        freshBins.forEach { f ->
            val committed = binDir.resolve(f.name)
            when {
                !committed.exists() -> problems += "missing committed binary: bin/${f.name}"
                !committed.readBytes().contentEquals(f.readBytes()) -> problems += "stale committed binary: bin/${f.name}"
            }
        }
        binDir.listFiles { f -> f.name.startsWith("bootstrap-") }?.forEach { c ->
            if (freshBins.none { it.name == c.name }) problems += "orphan committed binary (not produced by build): bin/${c.name}"
        }
        if (problems.isNotEmpty()) throw GradleException(buildString {
            appendLine("Committed bootstrap binaries are out of date / inconsistent:")
            problems.forEach { appendLine("  - $it") }
            appendLine("Run: ./gradlew :claude-plugin:updateBundledBinaries  (then commit claude-plugin/bin/bootstrap-*)")
        })
    }
}

tasks.named("assemble") { dependsOn(claudePluginZip) }
tasks.named("check") {
    dependsOn(verifyBundledBinariesUpToDate)
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
}

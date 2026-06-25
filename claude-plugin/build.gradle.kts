import groovy.json.JsonSlurper
import org.gradle.api.tasks.bundling.Zip
import java.util.SortedSet

plugins {
    base
}

val pluginVersion = version.toString()

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

        // bin/ -- copy scripts; mark POSIX scripts executable (.cmd/.ps1 are Windows-only, no x-bit)
        val binOut = out.resolve("bin").also { it.mkdirs() }
        sourceDir.resolve("bin").listFiles()?.forEach { f ->
            val dest = binOut.resolve(f.name)
            f.copyTo(dest, overwrite = true)
            if (!f.name.endsWith(".cmd") && !f.name.endsWith(".ps1")) dest.setExecutable(true)
        }

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
            "bin/install-devrig",
            "bin/install-devrig.ps1",
            "bin/check-devrig",
            "commands/setup.md",
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
        if (name != "mcp-steroid") {
            throw GradleException("plugin.json: expected name 'mcp-steroid', got '$name'")
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
        if (!content.contains("/mcp-steroid:setup")) {
            throw GradleException("install-devrig: failure message must point at /mcp-steroid:setup")
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
        if (!content.contains("/mcp-steroid:setup")) {
            throw GradleException("install-devrig.ps1: failure message must point at /mcp-steroid:setup")
        }
    }
}

// Validates the /mcp-steroid:setup slash command runs the bundled wrapper and handles outcomes
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
        // Must run the bundled wrapper via the plugin root, not reimplement the install
        if (!content.contains("\${CLAUDE_PLUGIN_ROOT}")) {
            throw GradleException("setup.md: must reference \${CLAUDE_PLUGIN_ROOT} to locate the wrapper")
        }
        if (!content.contains("install-devrig")) {
            throw GradleException("setup.md: must run the bundled install-devrig wrapper")
        }
        // After install, devrig must self-register the MCP server (the plugin no longer ships one).
        if (!content.contains("install claude")) {
            throw GradleException("setup.md: must run 'devrig install claude' to register the MCP server")
        }
        // Must tell the user to re-run on failure (the install is resumable)
        if (!content.contains("/mcp-steroid:setup")) {
            throw GradleException("setup.md: must tell the user to re-run /mcp-steroid:setup on failure")
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

        // Must test the canonical devrig path and exit cleanly so it never blocks a session.
        if (!content.contains(".mcp-steroid")) {
            throw GradleException("check-devrig: must test the ~/.mcp-steroid devrig launcher")
        }
        if (!content.contains("exit 0")) {
            throw GradleException("check-devrig: must exit 0 (a non-blocking SessionStart hook)")
        }
        // The user-visible nudge must be carried by a top-level systemMessage and point at setup.
        if (!content.contains("systemMessage")) {
            throw GradleException("check-devrig: must emit a top-level systemMessage for the user")
        }
        if (!content.contains("/mcp-steroid:setup")) {
            throw GradleException("check-devrig: systemMessage must point at /mcp-steroid:setup")
        }
    }
}

claudePluginZip.configure { finalizedBy(verifyPluginFiles) }

tasks.named("assemble") { dependsOn(claudePluginZip) }
tasks.named("check") {
    dependsOn(verifyPluginFiles)
    dependsOn(validatePluginJson)
    dependsOn(validateInstallScript)
    dependsOn(validateInstallPs1)
    dependsOn(validateSetupCommand)
    dependsOn(validateHooksJson)
    dependsOn(validateCheckDevrig)
}

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
    inputs.file(sourceDir.resolve(".mcp.json"))
    inputs.dir(sourceDir.resolve("bin"))
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

        // .mcp.json -- copy as-is
        sourceDir.resolve(".mcp.json").copyTo(out.resolve(".mcp.json"), overwrite = true)

        // plugin-bin/ -- copy scripts, preserve execute permission on shell script
        val binOut = out.resolve("bin").also { it.mkdirs() }
        sourceDir.resolve("bin").listFiles()?.forEach { f ->
            val dest = binOut.resolve(f.name)
            f.copyTo(dest, overwrite = true)
            if (!f.name.endsWith(".cmd")) dest.setExecutable(true)
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
            "bin/devrig-start",
            "bin/devrig-start.cmd",
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

// Validates .mcp.json is well-formed JSON with the expected MCP server structure
val validateMcpJson = tasks.register("validateMcpJson") {
    group = "verification"
    description = "Validate .mcp.json structure"

    val mcpJsonFile = projectDir.resolve(".mcp.json")
    inputs.file(mcpJsonFile)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(mcpJsonFile) as Map<String, Any?>
        val servers = json["mcpServers"] as? Map<*, *>
            ?: throw GradleException(".mcp.json: missing 'mcpServers' object")
        val server = servers["mcp-steroid"] as? Map<*, *>
            ?: throw GradleException(".mcp.json: missing 'mcpServers.mcp-steroid' entry")
        val type = server["type"]?.toString()
        if (type != "stdio") {
            throw GradleException(".mcp.json: expected type 'stdio', got '$type'")
        }
        val command = server["command"]?.toString()
        if (command.isNullOrBlank()) {
            throw GradleException(".mcp.json: 'command' field is missing or blank")
        }
        if (!command.contains("devrig-start")) {
            throw GradleException(".mcp.json: command '$command' does not reference devrig-start")
        }
    }
}

// Validates the shell script writes nothing to stdout and delegates to devrig
val validateShellScript = tasks.register("validateShellScript") {
    group = "verification"
    description = "Validate plugin-bin/devrig-start correctness"

    val script = projectDir.resolve("bin/devrig-start")
    inputs.file(script)

    doLast {
        val lines = script.readLines()

        // Every echo must redirect to stderr, otherwise bare echo would corrupt the MCP JSON-RPC channel
        val bareEcho = lines.filter { line ->
            val trimmed = line.trim()
            trimmed.startsWith("echo") && !trimmed.contains(">&2") && !trimmed.startsWith("#")
        }
        if (bareEcho.isNotEmpty()) {
            throw GradleException(
                "devrig-start: echo without >&2 would write to stdout (MCP channel):\n" +
                    bareEcho.joinToString("\n") { "  $it" }
            )
        }

        // Script must end with exec so the shell is replaced by devrig (no wrapper process)
        val hasExec = lines.any { it.trim().startsWith("exec ") }
        if (!hasExec) {
            throw GradleException("devrig-start: missing 'exec' — script must end with 'exec \"\$DEVRIG\" mcp'")
        }

        // Must reference the canonical devrig path
        val hasDevrigPath = lines.any { it.contains(".mcp-steroid") }
        if (!hasDevrigPath) {
            throw GradleException("devrig-start: does not reference ~/.mcp-steroid devrig path")
        }
    }
}

// Validates the Windows batch file uses %USERPROFILE% and delegates to devrig
val validateCmdScript = tasks.register("validateCmdScript") {
    group = "verification"
    description = "Validate plugin-bin/devrig-start.cmd correctness"

    val script = projectDir.resolve("bin/devrig-start.cmd")
    inputs.file(script)

    doLast {
        val content = script.readText()

        // ~ does not expand in cmd.exe
        if (content.contains("~")) {
            throw GradleException("devrig-start.cmd: must not use '~' — use %USERPROFILE% instead")
        }
        if (!content.contains("%USERPROFILE%")) {
            throw GradleException("devrig-start.cmd: must use %USERPROFILE% to reference the home directory")
        }

        // Error messages must go to stderr (1>&2 in batch)
        val bareEcho = content.lines().filter { line ->
            val trimmed = line.trim().lowercase()
            trimmed.startsWith("echo") && !trimmed.contains("1>&2") && !trimmed.startsWith("::") && trimmed != "echo off" && trimmed != "@echo off"
        }
        if (bareEcho.isNotEmpty()) {
            throw GradleException(
                "devrig-start.cmd: echo without 1>&2 would write to stdout (MCP channel):\n" +
                    bareEcho.joinToString("\n") { "  $it" }
            )
        }

        // Must call devrig
        if (!content.contains("devrig")) {
            throw GradleException("devrig-start.cmd: does not reference devrig binary")
        }

        // The Windows launcher devrig writes is `devrig.cmd` (see DevrigUserLauncher.path()), never .bat
        if (content.contains("devrig.bat")) {
            throw GradleException("devrig-start.cmd: references 'devrig.bat', the Windows launcher is 'devrig.cmd'")
        }
        if (!content.contains("devrig.cmd")) {
            throw GradleException("devrig-start.cmd: must reference the 'devrig.cmd' launcher under ~/.mcp-steroid/bin")
        }
    }
}

claudePluginZip.configure { finalizedBy(verifyPluginFiles) }

tasks.named("assemble") { dependsOn(claudePluginZip) }
tasks.named("check") {
    dependsOn(verifyPluginFiles)
    dependsOn(validatePluginJson)
    dependsOn(validateMcpJson)
    dependsOn(validateShellScript)
    dependsOn(validateCmdScript)
}

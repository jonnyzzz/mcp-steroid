@file:Suppress("HasPlatformType")

plugins {
    id("de.undercouch.download") version "5.6.0" apply false
    id("org.jetbrains.intellij.platform") version "2.13.1" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}

group = "com.jonnyzzz.intellij"
val baseVersion = file("VERSION").readText().trim()

/**
 * Short git hash (7 chars) for the current HEAD. On CI we read the full SHA from the
 * BUILD_VCS_NUMBER environment variable (TeamCity) or GITHUB_SHA (GitHub Actions)
 * rather than shelling out to `git`: gradle often runs inside a Docker container that
 * mounts the workspace from the host, and git then refuses to operate on a directory
 * owned by a different UID ("detected dubious ownership", exit 128). Locally neither
 * env var is set, so we fall back to `git rev-parse`.
 */
val gitHash: String = run {
    val ciSha = providers.environmentVariable("BUILD_VCS_NUMBER").orNull?.trim()
        ?: providers.environmentVariable("GITHUB_SHA").orNull?.trim()
    if (!ciSha.isNullOrEmpty()) {
        ciSha.take(7)
    } else {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }
}

fun parseBooleanProperty(propertyName: String, raw: String): Boolean {
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> error("Unsupported $propertyName value '$raw' (expected true/false or 1/0)")
    }
}

fun String.escapeForHtmlPreBlock(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

val isReleaseBuild = parseBooleanProperty(
    propertyName = "mcp.release.build",
    raw = providers.gradleProperty("mcp.release.build").orElse("false").get()
)

/**
 * CI-supplied version string (GitHub Actions or TeamCity). When provided, the build uses it
 * verbatim as the plugin version — the build NEVER rewrites it. Gradle only asserts that the
 * format matches "<baseVersionPrefix>.<counter>-(gh|jb)-<gitHash>" so a misconfigured CI
 * fails fast instead of silently producing a wrongly-labelled artifact.
 *
 * Accepts either -Pmcp.build.version=<version> (GitHub Actions path) or the BUILD_NUMBER
 * environment variable (TeamCity sets it automatically from %build.number%, which we wire
 * to the upstream "build number" build config's emitted buildNumber service message).
 */
val providedBuildVersion: String? =
    providers.gradleProperty("mcp.build.version").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.takeIf {
            // Only accept BUILD_NUMBER when it looks like a full version string, not a bare
            // counter. GitHub Actions exports its run_id there; that must go through
            // -Pmcp.build.version instead so a misconfig is caught loudly.
            it.isNotEmpty() && it.contains('-')
        }

if (providedBuildVersion != null) {
    // The CI-computed version keeps the VERSION file content (all components) intact
    // and appends the CI counter plus the -<ci>-<hash> suffix.
    // e.g. VERSION=0.92.0 + counter=441 + hash=abcdef1 → 0.92.0.441-jb-abcdef1
    val expected = Regex(
        "^" + Regex.escape(baseVersion) + "\\.\\d+-(gh|jb)-" + Regex.escape(gitHash) + "$"
    )
    require(expected.matches(providedBuildVersion)) {
        "mcp.build.version='$providedBuildVersion' does not match expected format " +
            "'${baseVersion}.<counter>-(gh|jb)-${gitHash}'. " +
            "The build number must be composed upstream (GitHub Actions run_number or " +
            "TeamCity buildNumber service message) and passed in unchanged — this build " +
            "does not rewrite it."
    }
}

// Local/dev builds — no CI counter available. Use the literal "19999-SNAPSHOT" in place
// of the CI counter so the shape stays <VERSION>.<counter>-...-<hash>, sorts after any
// realistic CI run, and is obviously not an official build.
//
// The version is stable across runs so generateMetadata stays UP-TO-DATE
// compileKotlin is only re-run when sources actually change.
// The git hash is the only freshness signal.
val localBuildCounter = "19999-SNAPSHOT"
version = when {
    isReleaseBuild -> "$baseVersion-$gitHash"
    providedBuildVersion != null -> providedBuildVersion
    else -> "$baseVersion.$localBuildCounter-$gitHash"
}
val releaseNotesVersion = providers.gradleProperty("mcp.release.notes.version").orElse(baseVersion).get()
val releaseNotesFile = layout.projectDirectory.file("release/notes/$releaseNotesVersion.md")
val releaseNotesText: Provider<String>? = if (isReleaseBuild) {
    providers.provider {
        val file = releaseNotesFile.asFile
        require(file.isFile) {
            "Release build requires release notes at ${file.absolutePath}"
        }
        "<pre>${file.readText().trim().escapeForHtmlPreBlock()}</pre>"
    }
} else {
    null
}

extra["isReleaseBuild"] = isReleaseBuild
extra["releaseNotesText"] = releaseNotesText

subprojects {
    group = rootProject.group
    version = rootProject.version
}

allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("mcp.steroid.test.projectHome", rootProject.layout.projectDirectory.asFile.absolutePath)
        // Host-side test JVMs must run headless. Non-headless was leaking
        // "Choose Run Configuration" / IDE suggestion popups from
        // `:ij-plugin:test`'s in-process IntelliJ Platform fixture, which
        // then sat on the user's desktop interrupting work. The only
        // legitimately non-headless environment is the Docker containers
        // used by :test-integration / :test-experiments — those run their
        // JVMs inside the container under Xvfb, so this host property is
        // simply not inherited there.
        systemProperty("java.awt.headless", "true")
        maxHeapSize = "4g"
    }
}

/**
 * Root configuration that resolves the plugin .zip artifact produced by :ij-plugin.
 * Consumed by [buildPluginOnCI] to avoid reaching into `ij-plugin/build/distributions/` directly.
 */
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

dependencies {
    pluginZip(project(":ij-plugin"))
}

/**
 * CI entry point that builds the plugin and publishes the resulting .zip(s) as build artifacts.
 *
 * Depends on :ij-plugin's plugin-zip configuration (so Gradle drives the buildPlugin task for us),
 * then emits one `##teamcity[publishArtifacts '<path>']` service message per resolved zip so
 * TeamCity uploads them as regular build artifacts — no need for TeamCity-side artifactRules.
 * The service messages are harmless no-ops on GitHub Actions, which keeps using its own
 * `actions/upload-artifact` step.
 *
 * Intended to be the single entry point for both CI systems:
 *   ./gradlew buildPluginOnCI -Pmcp.build.version=<base>-SNAPSHOT-(GH|JB)-<counter>-<hash>
 */
val buildPluginOnCI by tasks.registering {
    group = "ci"
    description = "Build the plugin distribution and publish its binaries via TeamCity service messages."
    inputs.files(pluginZip)
    outputs.upToDateWhen { false }

    doLast {
        val zips = pluginZip.files
        require(zips.isNotEmpty()) {
            "No plugin .zip resolved from :ij-plugin's plugin-zip configuration"
        }
        zips.forEach { zip ->
            require(zip.isFile) { "Plugin zip not a file: ${zip.absolutePath}" }
            logger.lifecycle("Plugin binary: ${zip.absolutePath} (${zip.length()} bytes)")
            // TeamCity service message — ignored by GitHub Actions runners.
            // See https://www.jetbrains.com/help/teamcity/service-messages.html#Publishing+Artifacts+while+the+Build+is+Still+in+Progress
            println("##teamcity[publishArtifacts '${zip.absolutePath}']")
        }
    }
}

/**
 * Subprojects that are NOT part of any CI aggregator. Either not a test of the plugin's
 * runtime behaviour, or too heavyweight for the per-OS unit-test agents:
 *
 * * `test-helper` — pure-test plumbing (Docker reaper, etc.); not exercised by the plugin.
 * * `test-integration` — Docker-based smoke matrix; runs on its own dedicated TC config.
 * * `test-experiments` — long-running experimental Docker tests; ditto.
 * * `npx`, `npx-kt` — standalone devrig (the stdio MCP entrypoint). NOT part of the
 *   per-OS plugin matrix, but covered by its own [ciDevrigTests] aggregator
 *   (`:npx-kt:test` + `:npx-kt:integrationTest`) on a dedicated TeamCity build config,
 *   since devrig now ships as a release artifact and is the agent's stdio entrypoint.
 *
 * The website (`website/`) is not a Gradle module, so it is not in this list — it is
 * already invisible to the CI aggregators.
 */
val nonPluginTestSubprojects = setOf(
    "test-helper",
    "test-integration",
    "test-experiments",
    "npx",
    "npx-kt",
)

/**
 * Subprojects that build the prompt-resource pipeline. Their tests are grouped into a
 * separate CI aggregator ([ciBuildPromptsTests]) so TeamCity can run them on a dedicated
 * fast-feedback "prompt-test" build configuration; they are NOT included in
 * [ciBuildPluginTests] to avoid doubling their cost on the per-OS plugin test matrix.
 *
 * * `prompt-generator` — KotlinPoet code-gen for prompt resources.
 * * `prompts` — generated prompt classes + markdown articles.
 * * `prompts-api` — interfaces the generator emits against.
 */
val promptsSubprojects = setOf(
    "prompt-generator",
    "prompts",
    "prompts-api",
)

/**
 * Subprojects that the per-OS `ij-plugin test` matrix MUST cover — the plugin's core
 * runtime infrastructure. Changes here force TeamCity to pick them up on every agent OS.
 *
 * * `ij-plugin` — the IntelliJ plugin itself (execution, vision, review, storage…).
 * * `jdk-downloader` — placeholder for Corretto JDK download/extract infrastructure.
 * * `pgp-verifier` — standalone OpenPGP detached-signature verifier used by JDK downloads.
 * * `mcp-core` — MCP protocol types, session manager, tool/resource/prompt registries.
 * * `mcp-http` — Ktor HTTP transport implementing MCP Streamable HTTP.
 * * `ai-agents` — agent CLI configuration helpers consumed by the plugin.
 *
 * Kept small and explicit so a missing module (e.g. a new `:mcp-*` submodule that never
 * reaches the plugin classpath) triggers a loud require() failure at configuration time
 * instead of silently being dropped from the CI matrix.
 */
val pluginCoreSubprojects = setOf(
    "ij-plugin",
    "jdk-downloader",
    "pgp-verifier",
    "mcp-core",
    "mcp-http",
    "mcp-stdio",
    "mcp-steroid-server",
    "execution-storage",
    "ai-agents",
)

/**
 * Aggregator that runs `:test` for every plugin subproject EXCEPT the prompts modules
 * (which have their own aggregator, [ciBuildPromptsTests]) and the non-plugin modules
 * listed in [nonPluginTestSubprojects]. Used by the per-OS `ij-plugin test
 * (Windows|Linux|macOS)` configurations on TeamCity.
 *
 * Auto-discovers all subprojects not in the two exclusion sets, then asserts that
 * every module in [pluginCoreSubprojects] is in the resulting bucket — this catches
 * accidental moves to the excluded lists.
 *
 * The `ci` prefix marks it as "intended for CI invocation" — the two aggregators are
 * siblings on the CI side; locally, developers run the subproject-specific `:test` tasks
 * directly.
 */
val ciBuildPluginTests by tasks.registering {
    group = "ci"
    description = "Run :test for the plugin modules (excludes ${(nonPluginTestSubprojects + promptsSubprojects).joinToString { ":$it" }})."

    val excluded = nonPluginTestSubprojects + promptsSubprojects
    val includedSubprojects = subprojects.filter { it.name !in excluded }
    val testTaskPaths = includedSubprojects.map { "${it.path}:test" }
    require(testTaskPaths.isNotEmpty()) {
        "ciBuildPluginTests resolved to zero :test tasks — settings.gradle.kts probably " +
            "stopped including modules; refresh nonPluginTestSubprojects / promptsSubprojects."
    }
    val includedNames = includedSubprojects.map { it.name }.toSet()
    val missingCore = pluginCoreSubprojects - includedNames
    require(missingCore.isEmpty()) {
        "ciBuildPluginTests is missing required plugin-core subproject(s): " +
            "${missingCore.joinToString { ":$it" }}. Either add them to settings.gradle.kts " +
            "or drop them from pluginCoreSubprojects."
    }
    dependsOn(testTaskPaths)

    dependsOn("ij-plugin:verifyPlugin")
    dependsOn("ij-plugin:verifyBundledLibraries")
    dependsOn("ij-plugin:verifyBundledKotlinCompatibility")
}

/**
 * Aggregator that runs `:test` for the prompts-related subprojects only (see
 * [promptsSubprojects]). Invoked by TeamCity's dedicated `prompt-test` configuration, a
 * sibling to the `ij-plugin test` matrix.
 *
 * Running this separately from [ciBuildPluginTests] means a prompt-generator regression
 * fails fast in its own TC build config without blocking the per-OS plugin tests.
 */
val ciBuildPromptsTests by tasks.registering {
    group = "ci"
    description = "Run :test for the prompts-related subprojects: ${promptsSubprojects.joinToString { ":$it" }}."

    val testTaskPaths = subprojects
        .filter { it.name in promptsSubprojects }
        .map { "${it.path}:test" }
    require(testTaskPaths.size == promptsSubprojects.size) {
        "ciBuildPromptsTests: expected ${promptsSubprojects.size} :test tasks, resolved " +
            "${testTaskPaths.size}. Did a module in promptsSubprojects disappear from " +
            "settings.gradle.kts?"
    }
    dependsOn(testTaskPaths)
}

/**
 * Ordered list of Gradle task paths that make up the `ciIntegrationTests` aggregator.
 *
 * Kept in an explicit `List` (not a `Set`) so the declaration order is the execution
 * order — cheapest first, heaviest last. Each entry is paired with its predecessor via
 * `mustRunAfter` below, so Gradle serialises them even under `--parallel`.
 *
 * 1. `:test-helper:test`           — pure-test infrastructure (Docker reaper helpers,
 *                                    agent-output-filter plumbing); no container boot.
 * 2. `:ij-plugin:integrationTest`  — Docker CLI tests (Cli{Claude,Codex,Gemini}…).
 *                                    Spins up agent-in-Docker; needs API keys.
 * 3. `:test-integration:test`      — Docker IntelliJ smoke matrix (IntelliJContainer,
 *                                    DialogKiller, WhatYouSee, PyCharm, EapSmoke…).
 *                                    Spins up a full IDE container per test.
 *
 * CLAUDE.md warns: NEVER run two Docker-IDE tests concurrently — two IntelliJ
 * containers exhaust RAM/CPU and both OOM. The mustRunAfter chain below is what
 * enforces that for this aggregator.
 */
val ciIntegrationTestTaskPaths = listOf(
    ":test-helper:test",
    ":ij-plugin:integrationTest",
    ":test-integration:test",
)

/**
 * Aggregator that runs the integration-test suite end-to-end, strictly sequentially.
 * The mirror image of [ciBuildPluginTests] on the integration-test side — one entry
 * point TeamCity can invoke from a single `./gradlew ciIntegrationTests` step so the
 * ordering rule lives in Gradle (where it belongs), not duplicated per CI system.
 *
 * Note: `:test-integration:test` has an `onlyIf` guard that normally skips it unless
 * the task name contains `:test-integration:`. That guard is extended in
 * `test-integration/build.gradle.kts` to also accept `ciIntegrationTests`, otherwise
 * this aggregator would silently skip the heaviest step.
 */
val ciIntegrationTests by tasks.registering {
    group = "ci"
    description = "Run the integration-test suite in order: ${ciIntegrationTestTaskPaths.joinToString()}."
    dependsOn(ciIntegrationTestTaskPaths)
}

/**
 * Ordered task paths for the devrig (`:npx-kt`) CI aggregator — the standalone stdio MCP
 * entrypoint that agents launch directly (`devrig mpc`), as opposed to the IDE's HTTP
 * transport. Historically `npx-kt` was in [nonPluginTestSubprojects] with NO CI coverage;
 * now that devrig ships as a release artifact and is the stdio entrypoint, it gets its own
 * aggregator + TeamCity build config.
 *
 * 1. `:npx-kt:test`            — fast JVM unit tests (CLI parse, project routing, render).
 * 2. `:npx-kt:integrationTest` — stdio MCP integration driven over stdin/stdout:
 *                                Cli{Claude,Codex,Gemini} `devrig install` in Docker,
 *                                fake-IDE bridge routing, stdout-cleanliness. Needs Docker
 *                                + ANTHROPIC/OPENAI keys + `:npx-kt:installDist`.
 *
 * Cheapest first; the `mustRunAfter` chain below serialises the two even under `--parallel`.
 */
val ciDevrigTestTaskPaths = listOf(
    ":npx-kt:test",
    ":npx-kt:integrationTest",
)

/**
 * Aggregator that runs the devrig (`:npx-kt`) suite — unit then stdio integration —
 * strictly in order. The mirror of [ciIntegrationTests] for the devrig side; TeamCity's
 * dedicated `devrig test` configuration invokes this single entry point.
 */
val ciDevrigTests by tasks.registering {
    group = "ci"
    description = "Run the devrig (:npx-kt) suite in order: ${ciDevrigTestTaskPaths.joinToString()}."
    dependsOn(ciDevrigTestTaskPaths)
}

/**
 * Merge-gate **compile check**: compile every source set of every module and stop there.
 *
 * "Every source set" is taken literally — for each project Gradle exposes one lifecycle
 * `<sourceSet>Classes` task per source set (`main` → `classes`, `test` → `testClasses`, and any
 * extra set a module declares, e.g. `:ij-plugin`'s `integrationTest` → `integrationTestClasses`).
 * Depending on all of them compiles production AND test AND auxiliary code without running a single
 * test or packaging an artifact. New modules and new source sets are picked up automatically — there
 * is no hand-maintained list to drift.
 *
 * Wiring lives in [gradle.projectsEvaluated] because a source set's `*Classes` task is only created
 * while its owning subproject is being evaluated; at root-script configuration time most of them do
 * not exist yet. The hard `require(...)` guards turn a silently-empty graph (e.g. the Kotlin/JVM
 * plugin failing to apply, or a module being renamed out of existence) into a fast, loud failure
 * instead of a green build that compiled nothing.
 */
val compileAllClasses by tasks.registering {
    group = "build"
    description = "Compile every source set (classes / testClasses / extra source sets) across all modules — no tests, no packaging."
}

gradle.projectsEvaluated {
    // Use `tasks.names` (lazy — lists registered task names without realizing the tasks) and depend
    // on path strings. Realizing the actual task objects here (e.g. via `tasks.matching { }`) would
    // force-create unrelated tasks such as `:ocr-tesseract:extractWindowsNatives`, which resolve a
    // configuration at creation time and fail with "unsafe configuration resolution". String paths
    // realize only the `*Classes` tasks themselves, and only when the execution graph is built.
    val classesTaskPaths = subprojects.flatMap { p ->
        p.tasks.names
            .filter { it == "classes" || it.endsWith("Classes") }
            .map { "${p.path}:$it" }
    }
    require(classesTaskPaths.isNotEmpty()) {
        "compileAllClasses resolved to zero *Classes tasks — the Kotlin/JVM plugins likely failed to apply to the subprojects."
    }
    // Fail fast if a structurally-important compile target silently drops out of the graph
    // (renamed module, lost source set, plugin regression). These are the load-bearing ones:
    // the IntelliJ plugin (+ its test and integrationTest sets) and the generated prompt corpus.
    val resolvedPaths = classesTaskPaths.toSet()
    val mustInclude = listOf(
        ":ij-plugin:classes",
        ":ij-plugin:testClasses",
        ":ij-plugin:integrationTestClasses",
        ":mcp-steroid-server:classes",
        ":prompts:classes",
        ":prompts:testClasses",
    )
    val missing = mustInclude.filterNot { it in resolvedPaths }
    require(missing.isEmpty()) {
        "compileAllClasses is missing expected compile target(s): $missing\nResolved targets:\n  ${resolvedPaths.sorted().joinToString("\n  ")}"
    }
    compileAllClasses.configure { dependsOn(classesTaskPaths) }
}

/**
 * Enforce strict ordering between the three steps. Configured inside
 * `gradle.projectsEvaluated` because the tasks live in subprojects that are not yet
 * evaluated when this script runs; `tasks.named(...)` on a not-yet-known task would
 * fail eagerly otherwise.
 *
 * `mustRunAfter` is relative — it only takes effect when both tasks are in the graph,
 * so adding it unconditionally here does NOT change behaviour for anyone running e.g.
 * `./gradlew :ij-plugin:integrationTest` on its own.
 */
gradle.projectsEvaluated {
    fun taskForPath(path: String): TaskProvider<Task> {
        val projectPath = path.substringBeforeLast(":")
        val taskName = path.substringAfterLast(":")
        return project(projectPath).tasks.named(taskName)
    }
    ciIntegrationTestTaskPaths.zipWithNext().forEach { (earlier, later) ->
        val earlierTask = taskForPath(earlier)
        taskForPath(later).configure { mustRunAfter(earlierTask) }
    }
    ciDevrigTestTaskPaths.zipWithNext().forEach { (earlier, later) ->
        val earlierTask = taskForPath(earlier)
        taskForPath(later).configure { mustRunAfter(earlierTask) }
    }
}

// Build :npx-kt:installDist and sync it to ~/.mcp-steroid/devrig/, leaving the
// rest of ~/.mcp-steroid/ (runtime state — backends, caches, logs, markers,
// eid_* sessions) untouched. Agent registration (claude/codex/gemini) is a
// one-time setup handled by the devrig launcher's own CLI, not by this task.
val deployNpx by tasks.registering(Sync::class) {
    description = "Build :npx-kt:installDist and sync it into ~/.mcp-steroid/devrig/."
    group = "deployment"
    dependsOn(":npx-kt:installDist")
    from(project(":npx-kt").layout.buildDirectory.dir("install/devrig"))
    into(File(System.getProperty("user.home"), ".mcp-steroid/devrig"))
}

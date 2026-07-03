/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver

/**
 * Manages the execution of a dpaia arena test case inside a Docker container
 * with IntelliJ IDEA + MCP Steroid.
 *
 * Responsibilities:
 * 1. Clone the test case repository inside the container
 * 2. Check out the base commit
 * 3. Apply test patches (so the agent has failing tests to fix)
 * 4. Build a prompt from the problem statement
 * 5. Run the prompt via an AI agent
 * 6. Evaluate the result
 */
class ArenaTestRunner(
    private val container: ContainerDriver,
    private val projectGuestDir: String,
) {

    private val git = GitDriver(container)

    /**
     * Clone a repository and check out a specific commit inside the container.
     *
     * @param testCase The test case with repo URL and base commit
     * @return The guest directory path where the project was cloned
     */
    fun cloneAndCheckout(testCase: DpaiaTestCase): String {
        // Use a unique suffix so parallel runs for different agents don't collide
        val suffix = System.nanoTime().toString(36)
        val projectDir = "$projectGuestDir/${testCase.repoName}-$suffix"

        println("[ARENA] Cloning ${testCase.cloneUrl} into $projectDir ...")

        // Try fast local clone from the bare repo cache mounted at /repo-cache
        val ownerAndRepo = testCase.repo.removeSuffix(".git")
        val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, projectDir)
        if (!clonedFromCache) {
            // Cache miss: fall back to a full remote clone (needed to checkout any commit)
            git.clone(testCase.cloneUrl, projectDir, shallow = false, timeoutSeconds = 120)
        }

        git.checkout(projectDir, testCase.baseCommit)
        return projectDir
    }

    /**
     * Apply the test patch to the cloned repository so the agent has
     * failing tests that define expected behavior.
     *
     * @param testCase The test case containing the test patch
     * @param projectDir The guest directory where the repo was cloned
     */
    fun applyTestPatch(testCase: DpaiaTestCase, projectDir: String) {
        if (testCase.testPatch.isBlank()) {
            println("[ARENA] No test patch to apply for ${testCase.instanceId}")
            return
        }
        println("[ARENA] Applying test patch for ${testCase.instanceId} ...")
        git.applyPatch(projectDir, testCase.testPatch)
    }

    /**
     * Build the prompt that will be sent to the AI agent.
     *
     * Kept minimal on purpose: the MCP tool descriptions and mcp-steroid://skill/
     * resources already contain all API usage guidance (threading rules, output
     * truncation, VFS patterns, etc.). This prompt is purely a task brief.
     *
     * @param withMcp when true, instructs the agent to use [steroid_execute_code] and IntelliJ IDEA;
     *                when false, instructs the agent to use shell commands (mvn/gradle/bash) only —
     *                used for the A/B comparison baseline (no IDE tooling).
     */
    fun buildPrompt(testCase: DpaiaTestCase, projectDir: String, withMcp: Boolean = true): String = buildString {
        val buildWrapper = if (testCase.buildSystem == "maven") "./mvnw" else "./gradlew"
        val buildSystemResourceUri = if (testCase.buildSystem == "gradle") {
            "mcp-steroid://skill/execute-code-gradle"
        } else {
            "mcp-steroid://skill/execute-code-maven"
        }
        val buildSystemName = if (testCase.buildSystem == "gradle") "Gradle" else "Maven"
        val javaHomeAssignment = if (withMcp) "JAVA_HOME=<Recommended JAVA_HOME>" else "JAVA_HOME=<configured JAVA_HOME>"
        val bashBuildWrapper = "$javaHomeAssignment $buildWrapper"
        val projectJdkVersion = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId]?.projectJdkVersion
            ?: DpaiaCuratedCases.CaseConfig().projectJdkVersion
        val configuredJavaHomePrefix = "/usr/lib/jvm/temurin-$projectJdkVersion-jdk-"
        val compileCommand = if (testCase.buildSystem == "maven") {
            "$bashBuildWrapper -DskipTests compile"
        } else {
            "$bashBuildWrapper compileJava compileTestJava --console=plain"
        }
        val runClassCommand = if (testCase.buildSystem == "maven") {
            "$bashBuildWrapper test -Dtest=<TestClass>"
        } else {
            "$bashBuildWrapper test --tests <TestClass> --console=plain"
        }
        val fullSuiteCommand = "$bashBuildWrapper test"

        appendLine("You are working on a Java Spring project located at: `$projectDir`")
        appendLine()
        appendLine("**OUTPUT REQUIREMENT** (read now, apply at the end): When the fix is complete and the full test suite passes, your LAST message MUST contain `ARENA_FIX_APPLIED: yes` on its own line — the harness only detects this exact string, not Maven/Gradle `BUILD SUCCESS` output. Full template in **Success Markers** at the end of this prompt.")
        appendLine()
        appendLine("## Task Context")
        appendLine()
        appendLine(testCase.problemStatement)
        appendLine()

        if (testCase.failToPass.isNotEmpty()) {
            appendLine("### FAIL_TO_PASS")
            for (test in testCase.failToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        // Include the test patch so the agent immediately sees what changed
        // without needing a VCS check or file read on the first turn.
        if (testCase.testPatch.isNotBlank()) {
            appendLine("### Test Patch (already applied — these tests define expected behavior)")
            appendLine("```diff")
            // Truncate very large patches to avoid blowing up the prompt
            val patchLines = testCase.testPatch.lines()
            if (patchLines.size <= 200) {
                appendLine(testCase.testPatch)
            } else {
                appendLine(patchLines.take(200).joinToString("\n"))
                appendLine("... (${patchLines.size - 200} more lines truncated)")
            }
            appendLine("```")
            appendLine()
        }

        if (testCase.passToPass.isNotEmpty()) {
            appendLine("### PASS_TO_PASS")
            for (test in testCase.passToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        appendLine("## Environment Facts")
        appendLine()
        appendLine("- Build system: **${testCase.buildSystem}**")
        appendLine("- Use the project wrapper only: `$buildWrapper`")
        appendLine("- Test-class command template: `$runClassCommand`")
        appendLine("- Configured project JDK version: **$projectJdkVersion**")
        if (withMcp) {
            appendLine("- Bash build/test commands must use the exact `Recommended JAVA_HOME` printed by the first MCP call: `JAVA_HOME=<Recommended JAVA_HOME> $buildWrapper ...`. The value must start with `$configuredJavaHomePrefix`; do not use wildcard JAVA_HOME assignments and do not try a lower JDK first.")
        } else {
            appendLine("- Bash build/test commands must use JDK $projectJdkVersion first. Resolve a concrete path whose name starts with `$configuredJavaHomePrefix`, then run `JAVA_HOME=<that exact path> $buildWrapper ...`; do not use wildcard JAVA_HOME assignments and do not try a lower JDK first.")
        }
        if (testCase.buildSystem == "maven") {
            appendLine("- **NEVER use `$buildWrapper install -am`** (also-make). The `-am` flag builds ALL upstream dependencies (potentially 48+ modules) and causes OOM in the container. Install only what you need: `$buildWrapper install -pl <module> -DskipTests`.")
            appendLine("- **Maven wrapper not found / `./mvnw` permission error**: If `./mvnw` is missing or not executable from the project root, use the bundled Maven directly: `JAVA_HOME=/usr/lib/jvm/temurin-17-<arch> /opt/idea/plugins/maven/lib/maven3/bin/mvn -f $projectDir/pom.xml ...`. Do NOT spend more than 2 Bash calls searching for the wrapper — fall back to the bundled mvn immediately.")
            appendLine("- **Maven + Lombok/Spring Boot 2.x failures**: If Maven fails with Lombok annotation errors (`bad class file`, `class file has wrong version`, or `com.sun.tools.javac.code.Symbol` errors), the default JAVA_HOME (Java 21) may be incompatible. Run `ls /usr/lib/jvm/` to find available JDKs, then try: `JAVA_HOME=/usr/lib/jvm/temurin-17-<arch> $buildWrapper ...`. Do NOT use `steroid_execute_code` or IntelliJ compiler to fix Maven compilation failures — bash + correct JAVA_HOME is always faster.")
            appendLine("- **Maven missing module dependency** (e.g. `Could not resolve .../ts-common...`): install only that module: `JAVA_HOME=... $buildWrapper install -pl <missing-module> -DskipTests -Dspotless.check.skip=true`. Do NOT use IntelliJ APIs to resolve Maven module dependencies.")
        }
        appendLine("- Check Docker once at start (`docker info`) **only if the FAIL_TO_PASS tests use `@Testcontainers`, extend `AbstractIT`/`IntegrationTest`, or mention Docker**. For pure file-creation scenarios (just new Java classes/records needed), skip the Docker check entirely — it adds 10-15s with no benefit.")
        appendLine("- If Docker is unavailable, **still attempt to run FAIL_TO_PASS tests** — many use H2 in-memory DB and work fine without Docker.")
        appendLine("  - Run the target test class: `$runClassCommand`")
        appendLine("  - If it fails with a Docker connection error (`Could not find a valid Docker environment` / `DockerException`):")
        appendLine("    1. Run `./mvnw test-compile -Dspotless.check.skip=true` to verify compilation.")
        appendLine("    2. If `test-compile` **passes** → report `ARENA_FIX_APPLIED: yes` with note:")
        appendLine("       `(tests blocked by Docker unavailability — compilation verified via test-compile)`")
        appendLine("    3. If `test-compile` **fails** → fix the compile errors first, then re-check.")
        appendLine("  - If it fails for other (non-Docker) reasons: fix those reasons and retry.")
        appendLine("  - **NEVER output `ARENA_FIX_APPLIED: yes` based on compile checks alone** unless Docker is the *explicit* blocker confirmed by a DockerException in the test output.")
        appendLine("  - **Mixed pass (some FAIL_TO_PASS tests pass, others fail with `Could not find a valid Docker environment`)**: The implementation is correct. Output `ARENA_FIX_APPLIED: yes` immediately — Testcontainers requires a Docker socket that is absent in this container. **Do NOT** attempt `docker pull`, `docker info`, `service docker start`, or any Docker socket debugging — it is container infrastructure and cannot be fixed from inside. Stop and declare success.")
        appendLine("  - **Docker image pull stalls** (e.g., `testcontainers/ryuk:*` or DB image pull makes no progress for 30+ seconds): this is a network restriction in the arena container — the public Docker registry is unreachable. **Do NOT** wait, retry, or debug Docker networking. Run `$compileCommand` to verify code compiles; if it passes, output `ARENA_FIX_APPLIED: yes` with note: `(tests blocked by Docker image pull failure — registry unreachable in arena container)` and stop immediately.")
        appendLine("  - **Testcontainers/Docker infrastructure errors** (e.g., `HTTP 400 Bad Request`, API version mismatch, `BadRequestException`, ryuk container start failures, `DockerClientException`): these are arena container infrastructure problems — **NOT code bugs**. After **2 failed test attempts** showing Docker infrastructure errors, stop all Docker debugging. Run `$compileCommand`; if it passes, output `ARENA_FIX_APPLIED: yes` with note: `(tests blocked by Docker infrastructure — API incompatibility in arena container)` and stop. Do NOT write proxy scripts, monkey-patch docker-java, or spend more than 2 attempts on any Docker infrastructure error.")
        appendLine("- **Gap analysis before implementing**: After reading VCS-modified test files, scan for method calls that reference production code. Verify each referenced method exists in the service/repository before writing other code — compile errors at test-run time from missing methods add unnecessary round-trips.")
        appendLine("- **Spring Boot i18n/message key validation**: When adding a new field/feature, check immediately whether the project has `MessageKeyValidatorTest` (or similar) that verifies all locale property files have consistent keys. Run: `find $projectDir/src -name '*MessageKey*' -o -name 'messages_*.properties' 2>/dev/null | head -20`. If multiple `messages_*.properties` exist (messages.properties, messages_de.properties, etc.), you MUST add the new message key to ALL of them in the same edit pass — do NOT wait for a failed test run to discover missing keys.")

        if (withMcp) {
            appendLine("- IntelliJ MCP is available; the project is already open and indexed.")
            appendLine("- Use `steroid_execute_code` for: VCS diff (`ChangeListManager`), PSI queries, type hierarchy, cross-file reference search (`ReferencesSearch`), **IntelliJ builds** (`ProjectTaskManager.buildAllModules`), and **running tests via IntelliJ** (`ProjectTaskManager.getInstance(project).run(...)`).")
            appendLine("- **Prefer IntelliJ builds over Maven/Gradle for compilation checks** — the mandatory build recipe below is the default path. Reserve Maven/Gradle Bash tests for final pass/fail or explicit IDE-build aborts.")
            appendLine("- **Do NOT use `steroid_execute_code` to run `./mvnw` or `./gradlew` via ProcessBuilder** — the ProcessBuilder pattern inside steroid is banned (classpath conflicts + token overflow). Use Bash for Maven/Gradle CLI invocations.")
            appendLine("- **Do not rerun Maven/Gradle just to recover hidden output.** If a completed targeted test run only lost `BUILD SUCCESS` behind `tail` or `grep`, inspect the saved output or preserve more output next time instead of rerunning the same target. Rerun when you changed code, saw a real failure, got an incomplete run, or Gradle skipped tests.")
            appendLine("- Keep one stable `task_id` for this task.")
            appendLine("- **Project name in IntelliJ is always `project-home`** — use this exact name in every `steroid_execute_code` call. Never use the GitHub repo name (e.g. \"petclinic\", \"spring-petclinic\") as the project name.")
            appendLine("- **The IDE is already configured** — do NOT attempt JDK/SDK setup, do NOT install plugins. Start immediately with your first real task call.")
            appendLine("- **Check VCS changes on your FIRST call** (via `ChangeListManager.getInstance(project).allChanges`) to detect any prior agent work — do NOT assume a clean slate when there are multiple agent sessions.")
            appendLine("- **Validation/service changes → regression risk**: When you add a validation rule to an existing service method (e.g., `saveUser()`, `createOwner()`), EVERY other test that calls this method with data that now fails validation will break. BEFORE declaring success, scan for all test call sites: `PsiSearchHelper.getInstance(project).processAllFilesWithWord(\"saveUser\", scope, { f -> ...; true }, true)`. Common regression culprits: `Abstract*Tests`, `*JdbcTests`, `*JpaTests`, `*SpringDataJpaTests` — update their test data (e.g. passwords, names) to satisfy the new rule.")
            appendLine("- **Maven generated sources**: When a class is not found by filename (e.g., `UserDto.java` absent from `src/`), it may be OpenAPI/annotation-processor-generated in `target/generated-sources/`. Use `JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))` (NOT filename search) or `PsiSearchHelper.processAllFilesWithWord(\"ClassName\", ...)` to find usage context. Stop after 2 failed filename lookups and switch strategies.")
            appendLine("- **Use the native Write tool for new source files** — it is ~47% faster than `steroid_execute_code` VFS writes. After bulk file creation via Write, trigger a single VFS refresh so IntelliJ indexes the new files:")
            appendLine("  ```kotlin")
            appendLine("  // ONE steroid_execute_code call to refresh after writing files:")
            appendLine("  import com.intellij.openapi.vfs.VfsUtil")
            appendLine("  import com.intellij.openapi.vfs.LocalFileSystem")
            appendLine("  VfsUtil.markDirtyAndRefresh(false, true, true,")
            appendLine("      LocalFileSystem.getInstance().findFileByPath(\"\${project.basePath!!}/src\"))")
            appendLine("  ```")
            appendLine("  **Exception**: use `steroid_execute_code` VFS creation ONLY when you must create a file AND immediately use PSI on it in the same call (e.g., create + run inspections atomically).")
            appendLine("- **Multi-site edits → ONE `steroid_execute_code` script**. When you are about to chain 2+ native `Edit` calls (same file or N files), STOP and batch them into one script: read each file, `content.replace(OLD, NEW)`, pre-check every match, then save all files inside a single `writeAction { }`. VFS + PSI stay consistent. Native `Edit` remains valid ONLY for a single literal substitution in a single file.")
            appendLine("- **Use native Read/Grep/Glob tools for simple file reads after the first steroid call.** Additional `steroid_execute_code` calls are for PSI queries, cross-file reference search, VFS writes, or test execution — not filename lookups. Follow the hard read budget below.")
            appendLine("- **If `steroid_execute_code` returns an error**: read the error message and retry with corrected code. Do NOT fall back to native Write/Bash tools after a single exec_code failure. Common fixes:")
            appendLine("  - `suspension functions can only be called within coroutine body` → mark your helper as `suspend fun readFile(...)` instead of `fun readFile(...)`")
            appendLine("  - `unresolved reference` → add the missing import explicitly at the top of the script")
            appendLine("  - `Write access is allowed from write thread only` → wrap the operation in `writeAction { }`")
            appendLine("  - `Read access is allowed from inside read-action only` → wrap the call in `readAction { }` (e.g. `readAction { FilenameIndex.getVirtualFilesByName(...) }`)")
            appendLine("  - `is not a directory in VFS` → a file exists where you expected a directory; check `vf.isDirectory`, delete the blocking file with `dir.delete(this)` inside `writeAction`, then `createChildDirectory`")
            appendLine("  - Java string with backtick-dollar or .class literals → switch to triple-quoted strings or string concatenation")
            appendLine("- **Batch file creation by type** — Create all DTO/record files in ONE `steroid_execute_code` call and all controller files in another. This is optimal (minimal round-trips, atomic VFS operations). After each batch, verify all expected files exist before continuing. Do NOT mix unrelated types in a single call.")
            appendLine("- **`findProjectFile()` pitfall for resource files**: `findProjectFile(\"filename\")` (just a filename) returns null — it needs the full relative path (e.g., `\"src/main/resources/application.properties\"`). For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName(\"application.properties\", scope).firstOrNull { it.path.contains(\"src/main/resources\") }` instead of `findProjectFile()`.")
            appendLine("- **FAIL_TO_PASS test files may not exist on disk**: If a FAIL_TO_PASS test file returns empty or NOT FOUND when you try to read it, it is a **new file added by the test patch** that needs the implementation to make sense. Do NOT block on reading it. Use the class name and problem statement to understand what needs to be implemented.")
            appendLine("- **Research budget**: Complete ALL exploration in AT MOST 2 `steroid_execute_code` calls. After 2 research calls, start writing implementation. If a file is empty or missing, pivot immediately to the problem statement and FAIL_TO_PASS class names — they describe what must be implemented even when source files can't be read.")
            appendLine("- **Native file read budget — HARD STOP**: After the first `steroid_execute_code` VCS check, you may make AT MOST **10 Read/Glob/Grep calls** before your first Edit or Write. Read ONLY the files directly named in the VCS diff — NOT build files, entity classes, application configs, or files from modules not mentioned in the diff. Do NOT audit the entire project before writing code.")
            appendLine("  - **If you have already made 10 Read/Glob/Grep calls without yet making an Edit or Write: STOP IMMEDIATELY. Make your first code change now.** The test patch shows exactly what each test expects — further reading adds no value and will cause a timeout. Scope constraint: read only VCS-diff files + their direct imports. No build files, no entity classes, no application.yml, no pom.xml from unrelated modules.")
            appendLine("- **Multi-module project scoping (CRITICAL for microservices)**: When the VCS diff lists test files from 3+ distinct modules (microservices/separate subdirs): implement **sequentially per module**. Pattern: Read tests from MODULE_1 → Write/Edit MODULE_1 implementation → move to MODULE_2. Do NOT read tests from all N modules before writing any code — that creates an N-module exploration loop that always times out. A single service with 2-3 test files is enough signal to start writing its implementation without reading other services first.")
            appendLine("  - **Reactive migration trap**: When converting from Spring MVC to WebFlux (RestTemplate→WebClient, CrudRepository→ReactiveCrudRepository), do NOT explore all services before changing any. Pick the simplest service, convert it fully (implementation + interface), compile-check, then move to the next. Parallel exploration of a 4-service migration always exceeds the 900s budget.")
            appendLine("- **MANDATORY first steroid call**: Your FIRST action must be a `steroid_execute_code` call that checks VCS changes and project readiness. This is required even for simple tasks — it confirms the IDE sees the project and shows exactly what the test patch changed.")
            appendLine("- **MANDATORY compilation check after edits**: After all file edits (before running Maven/Gradle tests), run ONE `steroid_execute_code` call to trigger IntelliJ compilation. **Do NOT use `./mvnw test-compile` or `./gradlew compileJava` for this check** — IntelliJ incremental build catches errors in ~2-5s vs a cold Maven compile (~25-60s). Use this exact code:")
            appendLine("  ```kotlin")
            appendLine("  val result = com.intellij.task.ProjectTaskManager.getInstance(project).buildAllModules().blockingGet(120_000)")
            appendLine("  // NOTE: output may include '=== MODAL DIALOG DETECTED ===' — that is the dialog-killer log, NOT a compile error.")
            appendLine("  // A successful build says: Build errors: false, aborted: false")
            appendLine("  // A compile error says: Build errors: true, aborted: false")
            appendLine("  // An aborted build says: Build errors: false, aborted: true  ← likely missing $buildSystemName sync")
            appendLine("  println(\"Build errors: ${'$'}{result?.hasErrors()}, aborted: ${'$'}{result?.isAborted}\")")
            appendLine("  if (result?.hasErrors() == true) {")
            appendLine("      // Read IntelliJ problem list for the actual error messages")
            appendLine("      val problems = com.intellij.analysis.problemsView.toolWindow.ProblemsView")
            appendLine("          .getToolWindow(project)?.let { /* use WolfTheProblemSolver */ null }")
            appendLine("      println(\"Check the editor — build errors present, read the failing files for syntax issues\")")
            appendLine("  }")
            appendLine("  ```")
            appendLine("  **If `buildAllModules()` returns `isAborted=true`** (IntelliJ build runner couldn't start): call `steroid_fetch_resource` for `$buildSystemResourceUri` and run its sync pattern. Only fall back to Bash `$compileCommand` if sync itself fails or times out.")
            appendLine("  **IMPORTANT**: `=== MODAL DIALOG DETECTED ===` in the output is normal — it means the dialog-killer suppressed a transient dialog. It does NOT mean the build failed. Only `Build errors: true` means a compile error.")
            appendLine("- For multi-file edits (renames, annotation changes, identical changes across N files), use the single-script read-replace-save shape inside `steroid_execute_code` described above — NOT a chain of native `Edit` calls. Still use steroid for the mandatory first call and compilation check.")
            appendLine("- **First call recipe** — combine readiness + Docker + VCS changes + **build environment** in ONE `steroid_execute_code` call (saves ~60s vs 3 separate calls):")
            appendLine("  ```kotlin")
            appendLine("  println(\"Project: ${'$'}{project.name}, base: ${'$'}{project.basePath}\")")
            appendLine("  // Docker socket check — no process spawn needed (ProcessBuilder inside steroid is banned)")
            appendLine("  val dockerOk = java.io.File(\"/var/run/docker.sock\").exists()")
            appendLine("  println(\"Docker: ${'$'}dockerOk\")")
            appendLine("  // Build environment — expose Maven path and available JDKs so you never need Bash to find them")
            appendLine("  val mavenBin = \"/opt/idea/plugins/maven/lib/maven3/bin/mvn\"")
            appendLine("  println(\"Maven: ${'$'}{if (java.io.File(mavenBin).exists()) mavenBin else \"NOT FOUND\"}\")")
            appendLine("  val gradlew = java.io.File(project.basePath + \"/gradlew\")")
            appendLine("  println(\"Gradlew: ${'$'}{if (gradlew.exists()) gradlew.absolutePath else \"NOT FOUND\"}\")")
            appendLine("  val configuredJdkVersion = \"$projectJdkVersion\"")
            appendLine("  val jvmDir = java.io.File(\"/usr/lib/jvm\")")
            appendLine("  val temurinJdks = jvmDir.listFiles()?.filter { it.name.startsWith(\"temurin\") }?.sortedBy { it.name } ?: emptyList()")
            appendLine("  println(\"JDKs: ${'$'}{if (temurinJdks.isEmpty()) \"none\" else temurinJdks.joinToString(\", \") { it.name }}\")")
            appendLine("  val recommendedJavaHome = temurinJdks.firstOrNull { it.name.startsWith(\"temurin-${'$'}configuredJdkVersion-jdk-\") }?.absolutePath")
            appendLine("  println(\"Recommended JAVA_HOME: ${'$'}{recommendedJavaHome ?: \"NOT FOUND for JDK ${'$'}configuredJdkVersion\"}\")")
            appendLine("  println(\"Current JAVA_HOME: ${'$'}{System.getProperty(\"java.home\")}\")")
            appendLine("  val changes = readAction {")
            appendLine("      com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)")
            appendLine("          .allChanges.mapNotNull { it.virtualFile?.path }")
            appendLine("  }")
            appendLine("  println(\"VCS-modified files:\\n\" + changes.joinToString(\"\\n\"))")
            appendLine("  // Then read those VCS-modified files + the FAIL_TO_PASS test files in the SAME or next call")
            appendLine("  // NOTE: use VfsUtil.loadText() with an empty-check — if content is empty, file may not exist yet:")
            appendLine("  //   val vf = findProjectFile(path) ?: run { println(\"NOT FOUND: \$path\"); continue }")
            appendLine("  //   val c = VfsUtil.loadText(vf); if (c.isEmpty()) { println(\"EMPTY: \$path\"); continue }")
            appendLine("  ```")
            appendLine("  **USE THE PRINTED Maven/Gradlew/Recommended JAVA_HOME paths for ALL subsequent Bash build commands** — never run `find /opt -name mvn` or `ls /usr/lib/jvm/` after this. The first call tells you everything you need.")
            appendLine("  **JDK SELECTION**: Use the printed `Recommended JAVA_HOME` for this case's configured JDK $projectJdkVersion on the first Bash build/test command. Do NOT try `JAVA_HOME=/usr/lib/jvm/temurin-21-...` first when the configured version is higher. Only switch JDKs after a real compiler/toolchain error proves the configured JDK is incompatible.")
            appendLine("  If `dockerOk=false`: still **run the FAIL_TO_PASS tests first via Bash** (many use H2, no Docker needed).")
            appendLine("  Only treat Docker as a blocker if the test explicitly fails with a `DockerException` / `Could not find a valid Docker environment` error.")
            appendLine("  **HARD STOP ON DOCKER FAILURES**: If ANY test fails with `Could not find a valid Docker environment`, `BadRequestException Status 400`, `HTTP 400`, or `docker.sock` errors — this is an INFRASTRUCTURE problem, NOT your code. Do NOT retry, do NOT investigate DOCKER_HOST, do NOT probe docker.sock, do NOT try environment variables. Instead: verify your code compiles (ProjectTaskManager or ./mvnw test-compile) and output ARENA_FIX_APPLIED: yes. Maximum 2 Bash calls for Docker — after that, STOP.")
        } else {
            appendLine("- IntelliJ MCP tools are unavailable in this run.")
            appendLine("- Use shell commands only (`bash`, `cat`, `find`, `grep`, `$buildWrapper`).")
            appendLine("- Do not call `steroid_*` tools.")
        }

        // Task-type-specific hints — applied for both MCP and NONE agents
        val isR2dbc = testCase.tags.any { it.contains("r2dbc", ignoreCase = true) }
                || testCase.problemStatement.contains("r2dbc", ignoreCase = true)
                || testCase.problemStatement.contains("reactive", ignoreCase = true) && testCase.problemStatement.contains("repository", ignoreCase = true)
        if (isR2dbc) {
            appendLine()
            appendLine("## R2DBC-Specific Notes (CRITICAL)")
            appendLine()
            appendLine("- **`@OneToMany` / `@OneToOne` / `@ManyToMany` are NOT supported in Spring Data R2DBC.** Each entity must stand alone. Load related entities with separate repository queries (e.g. `petRepository.findByOwner(ownerId)`) — do NOT use JPA-style join annotations.")
            appendLine("- **`@Id` must be `org.springframework.data.annotation.Id`**, NOT `jakarta.persistence.Id`. Mixing them causes silent failures.")
            appendLine("- **`@Column` syntax differs**: `@Column(\"column_name\")` (string arg), NOT `@Column(name=\"...\")` (JPA-style named attribute).")
            appendLine("- **`ReactiveCrudRepository<T, ID>` / `R2dbcRepository<T, ID>`** replaces `JpaRepository`. All methods return `Mono<T>` or `Flux<T>`. Tests must use `.block()` or `StepVerifier`.")
            appendLine("- **`@Transactional` from `org.springframework.transaction.annotation.Transactional`** — same annotation, works with R2DBC if the reactive transaction manager is configured.")
            appendLine("- **No `@GeneratedValue` with `GenerationType.IDENTITY` auto-mapping** for all DBs — verify schema uses the right sequence/auto-increment strategy.")
        }

        appendLine()
        appendLine("## Success Markers")
        appendLine()
        appendLine("- Implement the requested behavior with minimal code changes.")
        appendLine("- FAIL_TO_PASS tests must pass — run them with `$runClassCommand` and confirm `BUILD SUCCESS`.")
        if (testCase.buildSystem == "gradle") {
            appendLine("- **Gradle IDE build first**: before any Bash `./gradlew` compile/test check, run this IDE-native build in `steroid_execute_code`. It is the fastest way to catch compile errors and now uses the configured Gradle JVM:")
            appendLine("  ```kotlin")
            appendLine("  import com.intellij.openapi.module.ModuleManager")
            appendLine("  import com.intellij.task.ProjectTaskManager")
            appendLine("  import org.jetbrains.concurrency.await")
            appendLine("  val modules = ModuleManager.getInstance(project).modules")
            appendLine("  println(\"MODULES=${'$'}{modules.size}\")")
            appendLine("  val result = ProjectTaskManager.getInstance(project).build(*modules).await()")
            appendLine("  println(\"Build errors: ${'$'}{result.hasErrors()}, aborted: ${'$'}{result.isAborted}\")")
            appendLine("  ```")
            appendLine("  If this prints `Build errors: false, aborted: false`, do not run Bash Gradle just to compile. Move to targeted/full Gradle tests only when you need real test output for `ARENA_FIX_APPLIED`.")
            appendLine("- **Gradle sync fallback**: if the IDE build prints `aborted: true`, call `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle`, run its `ProjectDataImportListener.onFinalTasksFinished` sync recipe, then retry the IDE build once. Bash `./gradlew` remains the final verification/fallback path outside `steroid_execute_code`.")
            appendLine("- **Gradle UP-TO-DATE pitfall**: After writing new source files, always add `--rerun-tasks` to the FIRST Gradle test invocation (e.g. `$bashBuildWrapper :module:test --tests <Class> --rerun-tasks --no-daemon`). Without it, Gradle may return `UP-TO-DATE` and skip tests entirely while still printing `BUILD SUCCESSFUL`. If you see `BUILD SUCCESSFUL` with no `Tests run:` line, immediately rerun with `--rerun-tasks`.")
            appendLine("- **Multi-module Gradle test targeting**: `$bashBuildWrapper test --tests ClassName` silently finds NO tests when the class is in a submodule. ALWAYS use the subproject prefix: `$bashBuildWrapper :submodule:test --tests com.example.ClassName --rerun-tasks`. Find the correct subproject prefix by inspecting `settings.gradle` or using `ProjectRootManager.contentSourceRoots` — each root's path reveals its module.")
            appendLine("- **Batch Gradle targeted tests across subprojects**: If FAIL_TO_PASS lists 2+ Gradle test classes in different subprojects, run them in ONE command with repeated `:subproject:test --tests FQCN` pairs, then keep the full suite as the final separate run. Example: `$bashBuildWrapper :a:test --tests a.FooTest :b:test --tests b.BarTest --rerun-tasks --no-daemon --console=plain`.")
        }
        appendLine("- **Run the full test suite ONCE as the LAST step** (`$fullSuiteCommand`, NO `-Dtest=` filter).")
        appendLine("  Do NOT run full suites as intermediate checks during development — run only targeted tests (`$runClassCommand`) while iterating.")
        appendLine("  A full test suite run takes 60-90s; running it twice costs 2× that. A targeted test rerun solely to recover hidden `BUILD SUCCESS` output is also waste; reruns after fixes, failures, incomplete output, or Gradle skipped tests are required.")
        appendLine("  Service/validation changes often break other test classes (e.g., `Abstract*Tests`, `*JdbcTests`, `*JpaTests`, `*SpringDataJpaTests`).")
        appendLine("  Before outputting `ARENA_FIX_APPLIED: yes`, the full suite must exit 0.")
        appendLine("  - Search for `Abstract*` test base classes and any test using the same data as FAIL_TO_PASS tests")
        appendLine("  - Update them if your change (e.g. validation rule) also affects their test data (e.g. passwords, names)")
        if (testCase.passToPass.isNotEmpty()) {
            appendLine("- PASS_TO_PASS tests must stay passing.")
        }
        appendLine("- `ARENA_FIX_APPLIED: yes` requires actual test output showing BUILD SUCCESS — not just compile checks.")
        appendLine("- **MANDATORY final output** — after the full test suite passes, your response MUST end with these exact markers on their own lines. Do NOT substitute Maven/Gradle's `BUILD SUCCESS` console output for these markers — the harness only detects the explicit `ARENA_FIX_APPLIED:` line:")
        appendLine("ARENA_FIX_APPLIED: yes")
        appendLine("ARENA_SUMMARY: <one line summary of what changed and what test output confirmed success>")
    }

    /**
     * Run a complete arena test: clone (unless pre-deployed), patch, prompt agent, collect result.
     *
     * @param testCase The test case to run
     * @param agent The AI agent session to use
     * @param timeoutSeconds Maximum time for the agent to work
     * @param prewarm Optional lambda to run before the agent timer (excluded from agent budget).
     *                Use when pre-warming is needed AFTER container creation (legacy approach).
     *                When [predeployedProjectDir] is set, the project is already indexed by
     *                [waitForProjectReady] so no prewarm is needed.
     * @param predeployedProjectDir Guest path of a project already deployed and indexed in the IDE.
     *                              When set, skips clone + patch (done by IntelliJProject.deploy()).
     *                              The project was deployed via [IntelliJProject.ProjectFromGitCommitAndPatch]
     *                              and indexed by [IntelliJContainer.waitForProjectReady].
     */
    fun runTest(
        testCase: DpaiaTestCase,
        agent: AiAgentSession,
        withMcp: Boolean = true,
        timeoutSeconds: Long = 1800,
        prewarm: ((projectDir: String) -> Unit)? = null,
        predeployedProjectDir: String? = null,
    ): ArenaTestResult {
        println("[ARENA] ========================================")
        println("[ARENA] Running: ${testCase.instanceId}")
        println("[ARENA] Repo: ${testCase.repo}")
        println("[ARENA] Tags: ${testCase.tags}")
        println("[ARENA] Build: ${testCase.buildSystem}")
        println("[ARENA] ========================================")

        // Step 1+2: Clone and patch, unless the project was pre-deployed before IntelliJ started.
        val projectDir: String
        if (predeployedProjectDir != null) {
            println("[ARENA] Using pre-deployed project at $predeployedProjectDir (skipping clone+patch)")
            projectDir = predeployedProjectDir
        } else {
            projectDir = cloneAndCheckout(testCase)
            applyTestPatch(testCase, projectDir)
        }

        // Step 3: Build prompt
        val prompt = buildPrompt(testCase, projectDir, withMcp = withMcp)
        println("[ARENA] Prompt length: ${prompt.length} chars")

        // Pre-warm (NOT measured — IDE setup before the agent's timer starts)
        val prewarmStartMs = System.currentTimeMillis()
        if (prewarm != null) {
            println("[ARENA] Pre-warming IDE for ${testCase.instanceId} ...")
            prewarm(projectDir)
            println("[ARENA] Pre-warm complete")
        }
        val prewarmDurationMs = System.currentTimeMillis() - prewarmStartMs

        // Step 4: Run agent (START MEASURING)
        println("[ARENA] Running agent (timeout: ${timeoutSeconds}s) ...")
        val agentStartMs = System.currentTimeMillis()
        val agentResult = agent.runPrompt(prompt, timeoutSeconds = timeoutSeconds).awaitForProcessFinish()
        val agentDurationMs = System.currentTimeMillis() - agentStartMs

        // Step 5: Evaluate
        val evaluation = evaluate(testCase, agentResult)

        println("[ARENA] ========================================")
        println("[ARENA] Result for ${testCase.instanceId}:")
        println("[ARENA]   Agent exit code: ${agentResult.exitCode}")
        println("[ARENA]   Agent claimed fix: ${evaluation.agentClaimedFix}")
        println("[ARENA]   Used MCP: ${evaluation.usedMcpSteroid}")
        println("[ARENA] ========================================")

        return ArenaTestResult(
            testCase = testCase,
            agentResult = agentResult,
            evaluation = evaluation,
            agentDurationMs = agentDurationMs,
            prewarmDurationMs = prewarmDurationMs,
        )
    }

    /**
     * Evaluate the agent's response against the test case expectations.
     */
    private fun evaluate(testCase: DpaiaTestCase, result: ProcessResult): ArenaEvaluation {
        val combined = result.stdout + "\n" + result.stderr

        return ArenaEvaluation(
            agentExitedSuccessfully = result.exitCode == 0,
            usedMcpSteroid = combined.contains("steroid_execute_code", ignoreCase = true),
            agentClaimedFix = combined.contains("ARENA_FIX_APPLIED: yes", ignoreCase = true),
            agentSummary = extractMarker(combined, "ARENA_SUMMARY:"),
        )
    }

    private fun extractMarker(text: String, marker: String): String? {
        val line = text.lines().find { it.trimStart().startsWith(marker, ignoreCase = true) }
        return line?.substringAfter(marker)?.trim()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Result of running an arena test case.
 */
data class ArenaTestResult(
    val testCase: DpaiaTestCase,
    val agentResult: ProcessResult,
    val evaluation: ArenaEvaluation,
    /** Wall-clock milliseconds spent inside [agent.runPrompt] (excludes git clone and patch apply). */
    val agentDurationMs: Long = 0L,
    /** Wall-clock milliseconds spent in IDE pre-warm (open + index); excluded from agent budget. */
    val prewarmDurationMs: Long = 0L,
)

/**
 * Evaluation metrics for an arena test run.
 */
data class ArenaEvaluation(
    /** Whether the agent process exited with code 0 */
    val agentExitedSuccessfully: Boolean,

    /** Whether the agent used steroid_execute_code */
    val usedMcpSteroid: Boolean,

    /** Whether the agent reported it applied a fix */
    val agentClaimedFix: Boolean,

    /** The agent's one-line summary of changes, if provided */
    val agentSummary: String?,
)

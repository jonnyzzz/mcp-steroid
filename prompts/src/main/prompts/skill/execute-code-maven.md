Execute Code: Maven Patterns

Running Maven builds and tests via IntelliJ Maven APIs instead of ProcessBuilder.

# Execute Code: Maven Patterns

## First open: trigger the Maven import before semantic work

On a newly opened Maven project, routing and IDE initialization can finish before the Maven model exists.
Before the first indexed semantic query, go directly to **Trigger and await Maven import** below and run
that recipe even when no POM was edited. Scheduling the update starts the import;
`Observation.awaitConfiguration(project)` only awaits work, so calling it alone is not a trigger.

A frontendless backend cannot show the yellow project-JDK banner. Check
`ProjectRootManager.getInstance(project).projectSdk`, but a null project SDK is a warning, not automatic failure:
imported project-local source PSI can still be valid. When the task needs JDK library resolution,
compilation, inspections, or refactoring that resolves JDK symbols, inspect module SDKs and unresolved JDK
references. Follow **Fix: "Project JDK is not defined" Banner** and trigger the import again only when the
needed capability is absent or diagnostics identify the SDK. Do not mutate SDK configuration merely because
the project-level value is null.

## Agent: Run One Maven Test Method (two-call pattern)

When an agent task asks for "run one fast test through Maven" — pick a plain JUnit method, then run it through IntelliJ's Maven integration. **Do NOT shell out to `./mvnw` or `mvn` via the `Bash` tool**, and do NOT use `ProcessBuilder("./mvnw")` inside `steroid_execute_code`. Both bypass the IDE entirely and defeat the value of MCP Steroid.

> ⚠️ **Single-call pattern does NOT work for Maven test runs.** The MCP HTTP transport (claude-code's CLI in particular) cancels in-flight tool calls after ~60 seconds. Maven setup + a JUnit test on a fresh checkout often takes 30–120s. A single script that calls `runConfiguration` and then `await`s the SMT listener will be cancelled mid-run by the client, even though the IDE-side script timeout is much larger. **Use the two-call pattern below: launch in call 1, poll in call 2+.**

> ⚠️ **Use polling, not listeners.** Read state directly from the live `RunContentDescriptor`'s `ProcessHandler` (terminated? exit code?) plus the surefire XML report on disk. SMT events do not fire reliably for Maven surefire, and a long-lived `messageBus.connect()` is brittle across retries. Polling is shorter, simpler, and matches what a human reads from the Run tool window.

> ⚠️ **Avoid `MavenRunConfigurationType.runConfiguration(...)` directly.** That convenience overload calls `ApplicationManager.getApplication().invokeAndWait(...)` internally, which can block the script's coroutine dispatcher. Use `createRunnerAndConfigurationSettings` + `ProgramRunnerUtil.executeConfiguration` dispatched on `Dispatchers.EDT`.

### Call 1 — launch the test, return immediately

```kotlin[IU]
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters

val params = MavenRunnerParameters(
    true, project.basePath!!, null,
    listOf(
        "test",
        "-pl", "core",
        "-Dtest=com.example.MyServiceTest#shouldReturnFeature",
        "-DskipITs",
        "-Dspotless.check.skip=true",
    ),
    emptyList(),
)
val settings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
    null, null, params, project, "Maven test (MCP)", false,
)
RunManager.getInstance(project).addConfiguration(settings)
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
println("Maven test launched: ${params.goals}")
println("EXECUTION_VIA: MavenRunConfigurationType")
println("Call the polling script next.")
```

### Call 2 — poll the descriptor's ProcessHandler + read surefire XML (re-issue every 20–30s)

```kotlin[IU]
import com.intellij.build.BuildView
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.vfs.LocalFileSystem

val descriptor = RunContentManager.getInstance(project).allDescriptors
    .firstOrNull { it.displayName?.contains("Maven test (MCP)") == true }
val handler = descriptor?.processHandler
if (handler == null) {
    println("No Maven run in flight; run the launch script first.")
} else if (!handler.isProcessTerminated) {
    println("Maven run still in flight; call this script again.")
} else {
    val exit = handler.exitCode ?: -1
    val module = "core" // adjust to the targeted -pl module
    val reportsDir = LocalFileSystem.getInstance()
        .findFileByPath("${project.basePath}/$module/target/surefire-reports")
    val testsRunLines = reportsDir?.children
        ?.filter { it.name.endsWith(".txt") }
        ?.mapNotNull { vf ->
            String(vf.contentsToByteArray(), vf.charset).lines()
                .firstOrNull { it.startsWith("Tests run:") }
                ?.let { vf.name + ": " + it }
        }
        ?: emptyList()
    println("EXECUTION_VIA: MavenRunConfigurationType")
    println("PROCESS_EXIT_CODE: $exit")
    testsRunLines.forEach { println("TESTS_RUN: $it") }
    println("TEST_RESULT: ${if (exit == 0) "PASSED" else "FAILED"}")

    // On failure with no surefire output, dump the Maven build console tail so the
    // agent sees the *cause* (BUILD FAILURE message, missing artifact, compile error)
    // without a separate inspection round.
    if (exit != 0 && testsRunLines.isEmpty()) {
        val buildView = descriptor.executionConsole as? BuildView
        val inner = buildView?.consoleView as? ConsoleViewImpl
        val text = inner?.editor?.document?.text
        if (text != null) {
            val tail = text.lines().takeLast(60).joinToString("\n")
            println("--- Maven build console tail (last 60 lines) ---")
            println(tail)
        }
    }
}
```

**Why this shape:**
- `RunContentDescriptor.processHandler` exposes `isProcessTerminated` and `exitCode` directly — read whenever you want, no event subscription.
- Maven surefire writes one `<TestClass>.txt` and `<TestClass>.xml` per class into `<module>/target/surefire-reports/`. The `.txt` files start with `Tests run: N, Failures: M, Errors: K, Skipped: J, Time elapsed: …` — same numbers a human reads.
- `processHandler.exitCode == 0` is the authoritative pass/fail signal; the surefire counts are extra detail for the agent's report.
- For Maven runs `descriptor.executionConsole` is a `BuildView`; `BuildView.getConsoleView()` returns the inner `ConsoleViewImpl` whose `editor.document.text` holds the full Maven log — that's the same content the Build tool window shows. Reading the tail on failure surfaces `BUILD FAILURE`, missing-artifact errors, and compile errors without a follow-up call.
- Each script returns in <2s — well under the MCP HTTP transport's ~60s cancel window. `project.userData`, `CompletableDeferred`, and `messageBus.connect()` are all unnecessary.

**`-am` (also-make) is BANNED.** It walks the upstream graph and frequently OOM-kills the container. Pin to the one submodule with `-pl <module>` and accept that one extra `install` round-trip below if a sibling artifact is missing.

### Sibling-install fallback (when the targeted module references an in-reactor sibling not yet in `~/.m2`)

If the polling script reports `TEST_RESULT: FAILED` and the Maven build console tail mentions `The POM for io.example:sibling:jar:X is missing`, `Could not resolve artifact ...:sibling:jar:X`, or `Could not resolve parent POM ...`, install the missing piece through IntelliJ. Same two-call shape: launch via `createRunnerAndConfigurationSettings` with the install goal, give the run config a unique name (e.g. `"Maven install (MCP)"`), then poll its `processHandler.exitCode` exactly like the test polling script. Drop `-Dtest=...` from the goal list.

Two missing-piece patterns, in the order to try them:

1. **Sibling artifact missing** — goals = `listOf("install", "-pl", "<missing-module>", "-DskipTests")`. Example for Keycloak: `-pl common -DskipTests`. The error mentions a specific `<artifactId>` you don't have in `~/.m2`.

2. **Parent POM missing** — goals = `listOf("install", "-pl", ".", "-N", "-DskipTests")` (the `-N` / `--non-recursive` flag is the key — it installs ONLY the root POM, not children). Use this when the error mentions `Could not resolve parent POM` or after a successful sibling install the next test attempt still fails because the root parent (e.g. `keycloak-parent`) isn't in `~/.m2`. Without `-N`, this would install the entire reactor and OOM the container.

After each install round, re-issue the test launch+poll. Stop after at most TWO install rounds (one sibling + one parent is the typical shape for projects like Keycloak); if more are needed, escalate rather than chain installs — the project likely needs a top-level `mvn install -DskipTests` that this recipe deliberately avoids.

**Never use `-am` (also-make).** It walks the upstream graph and OOM-kills the container. Pin to one module + `-N` for parent-only installs.

**`MavenRunner.run` is the lighter alternative** for goal execution, but it has no `RunContentDescriptor` — you can poll its returned future instead, but it's simpler to keep one shape across all Maven invocations.

---

## Trigger and await Maven import

On a first open or after modifying `pom.xml`, trigger a full Maven re-import and wait for completion
before compiling, running tests, or using indexed PSI:

```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // package: buildtool (NOT project) — IU-253+
import com.intellij.platform.backend.observation.Observation

val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(
    MavenSyncSpec.full("post-pom-edit", explicit = true)
)
Observation.awaitConfiguration(project)  // suspends until sync + indexing fully complete
println("Maven sync complete — new deps resolved, safe to compile/inspect")
```

**Key notes:**
- `MavenSyncSpec.full()` forces re-reading all POM files (use after external edits)
- `MavenSyncSpec.incremental()` only syncs changed files (use for minor updates)
- `explicit = true` marks the sync as user-initiated (affects IDE progress indicators)
- `Observation.awaitConfiguration(project)` is required — otherwise `runInspectionsDirectly` shows false "cannot resolve symbol" errors from undownloaded deps
- ⚠️ `MavenSyncSpec` is in package `org.jetbrains.idea.maven.buildtool` — NOT `.project`

**Partial sync — only specific pom files:**
```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec

val pomVf = findProjectFile("pom.xml")!!
MavenProjectsManager.getInstance(project).scheduleUpdateMavenProjects(
    MavenSyncSpec.full("update specific pom"),
    filesToUpdate = listOf(pomVf),
    filesToDelete = emptyList()
)
```

---

## JAVA_HOME / Multi-JDK Selection

### JDK Selection Algorithm (do this BEFORE your first Maven/Gradle command)

When multiple JDKs are available, select the right one immediately — don't trial-and-error:

1. **Read the project's Java version** from `pom.xml` (`<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, or `<release>`) or `build.gradle` (`sourceCompatibility`, `toolchain`).
2. **List available JDKs**: `ls /usr/lib/jvm/ 2>/dev/null` (Linux) or `ls /Library/Java/JavaVirtualMachines/ 2>/dev/null` (macOS).
3. **Pick the LOWEST available JDK version >= the project's requirement.** Example: project needs Java 24 → available are temurin-8, 11, 17, 21, 25 → pick **temurin-25** (only one >= 24). Never start with lower JDK versions and work upward.
4. **Set JAVA_HOME in your FIRST Bash command** — before any Maven/Gradle invocation:
```
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64
export PATH=$JAVA_HOME/bin:$PATH
java -version   # confirm
```

> **If the first `steroid_execute_code` call already printed available JDKs** (e.g., `JDKs: temurin-8-jdk-arm64, temurin-21-jdk-arm64, temurin-17-jdk-arm64, temurin-11-jdk-arm64, temurin-25-jdk-arm64`), use that list directly — do NOT run `ls /usr/lib/jvm/` again.

### JDK Troubleshooting

When Maven fails with `Fatal error compiling`, `cannot find symbol`, `POM not found for parent`,
or `Unsupported class file major version`, the root cause is often a JDK version mismatch.
Fix it BEFORE making any other changes.

**Run Maven with explicit JAVA_HOME:**
```
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 mvn -pl ts-common install -DskipTests
```

**When to do this first:**
- Multi-module project with a `common` or `parent` module that fails to install
- Error message mentions `Unsupported class file major version` (version mismatch)
- Maven cannot resolve `../pom.xml` or parent POM in a fresh container
- Project's `pom.xml` declares `<java.version>17</java.version>` but `java -version` shows 21+

**Do NOT use steroid_execute_code for Maven JAVA_HOME issues.** The IDE cannot fix JDK
mismatches — only setting `JAVA_HOME` in the shell environment fixes it. Use `Bash` tool.

---

## Fix: "Project JDK is not defined" Banner (IntelliJ IDEA)

When IntelliJ shows a yellow "Project JDK is not defined" notification in the editor,
Maven builds and inspections will fail. Fix it immediately before doing any other work.

**For Maven/Gradle projects**: the correct JDK is the one Maven/Gradle uses for import —
typically whatever `JAVA_HOME` is set to. Using a different JDK can cause language-level
mismatches and re-import failures.

**Step 1: Reuse an unambiguous module or Maven-environment SDK**

Prefer a Java SDK already assigned to the project's modules. Otherwise match an existing registration to
`JAVA_HOME`, or create that exact SDK. Never choose the first global registration: it may belong to another
project or language level. When neither source is unambiguous, determine the required JDK from the POM and set
`explicitJdkPath` before running the recipe.

```kotlin[IU]
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.backend.observation.Observation
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec

// 1. Prefer one Java SDK already assigned consistently across modules.
val javaSdkType = JavaSdk.getInstance()
val (registeredSdks, moduleJavaSdks, currentSdk) = readAction {
    val registered = ProjectJdkTable.getInstance().getSdksOfType(javaSdkType).toList()
    val moduleSdks = ModuleManager.getInstance(project).modules
        .mapNotNull { module -> ModuleRootManager.getInstance(module).sdk }
        .filter { it.sdkType == javaSdkType }
        .distinctBy { it.homePath ?: it.name }
    Triple(registered, moduleSdks, ProjectRootManager.getInstance(project).projectSdk)
}
val moduleSdk = moduleJavaSdks.singleOrNull()

// 2. Otherwise use an explicit project requirement, or the exact Maven environment JDK. Set the
// explicit path only after reading the POM/compiler level; never guess from a global registration.
val explicitJdkPath: String? = null // TODO: set when the project requirement is known
val desiredJdkPath = (explicitJdkPath ?: System.getenv("JAVA_HOME"))
    ?.let(FileUtil::toCanonicalPath)
    ?.takeIf { javaSdkType.isValidSdkHome(it) }
val registeredDesiredSdk = readAction {
    desiredJdkPath?.let { desired ->
        registeredSdks.firstOrNull { sdk ->
            sdk.homePath?.let(FileUtil::toCanonicalPath) == desired
        }
    }
}

when {
    currentSdk != null -> println("Project SDK already set: ${currentSdk.name}")
    moduleSdk != null -> {
        edtWriteAction { JavaSdkUtil.applyJdkToProject(project, moduleSdk) }
        println("Applied the modules' Java SDK: ${moduleSdk.name}")
    }
    moduleJavaSdks.size > 1 -> {
        println("Modules already use multiple Java SDKs; preserving their per-module assignments")
    }
    desiredJdkPath != null -> {
        val sdk = registeredDesiredSdk
            ?: edtWriteAction { SdkConfigurationUtil.createAndAddSDK(desiredJdkPath, javaSdkType) }
        if (sdk != null) {
            edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }
            println("Applied SDK from: $desiredJdkPath (${sdk.name})")
        } else error("createAndAddSDK returned null for $desiredJdkPath")
    }
    else -> error(
        "No unambiguous Java SDK: modules have none and JAVA_HOME/explicitJdkPath does not identify one"
    )
}

// 3. Trigger Maven re-sync — initial import may have failed without a JDK
val configuredProjectSdk = readAction { ProjectRootManager.getInstance(project).projectSdk }
if (configuredProjectSdk != null) {
    MavenProjectsManager.getInstance(project)
        .scheduleUpdateAllMavenProjects(MavenSyncSpec.full("after-jdk-fix", explicit = true))
    Observation.awaitConfiguration(project)
    println("Maven re-sync complete")
}
```

**When to run this**: Before any Maven or inspection call if the editor shows the JDK banner. In a
frontendless backend with a null `ProjectRootManager.getInstance(project).projectSdk`, run it only when the
task needs JDK-dependent capabilities or diagnostics show missing SDK/JDK resolution; first inspect module
SDKs instead of blindly choosing the first registered JDK.
**Why same JDK as Maven**: Maven was configured for `JAVA_HOME` — using a different JDK causes
language-level mismatches and re-import failures.

---

## What NOT to Do

- **❌ `ProcessBuilder("./mvnw")` / `ProcessBuilder("mvn")`** — banned inside `steroid_execute_code`. Use `MavenRunConfigurationType.createRunnerAndConfigurationSettings` per the Agent recipe at the top.
- **❌ `Bash` tool to invoke `./mvnw` / `mvn`** — same reason; the whole point of MCP Steroid is the IDE-driven path.
- **❌ Skip Maven sync after pom.xml edit** — without sync, imports show "cannot resolve symbol" false positives.
- **❌ `-am` (also-make)** — walks the full upstream graph and OOM-kills the container. Install one sibling at a time via the *Sibling-install fallback* in the Agent recipe.
- **❌ Print untruncated Maven output** — Spring Boot tests generate 100k+ chars. The Agent recipe reads only the surefire `Tests run:` summary line per class, which is bounded.
- **❌ Run multiple test classes in one `-Dtest=A,B,C,D`** — token overflow on long output. Run one at a time.

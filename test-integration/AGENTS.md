# test-integration — Agent Guide

**Stable** Docker-based integration tests + shared infrastructure for the wider integration test
suite. Experimental / long-running tests live in the sibling `:test-experiments` module, which
depends on this one for the infrastructure.

## Design principles in this module

The three repo-wide tenets (canonical: [`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md);
runtime: `mcp-steroid://skill/design-philosophy`) show up here as concrete
operational rules:

- **Tenet 1 — narrow MCP tool surface.** This module does not add MCP
  tools. It tests the existing `steroid_*` surface end-to-end through
  Docker-isolated agent CLIs and `mcpExecuteCode`-driven fixtures. If a
  test would be easier with a new tool, treat that as a recipe (prompt
  resource or fixture script) gap first — only escalate to a tool
  proposal after the gates in `docs/PHILOSOPHY.md` Tenet 1 pass.
- **Tenet 2 — IntelliJ APIs over wrappers.** Every test fixture configures
  the IDE through *typed IntelliJ APIs* via `steroid_execute_code` (see
  "Configuring the IDE — always via `mcpExecuteCode`, never via XML"
  later in this file). XML config writes are explicitly banned because
  they're untyped and silently fragile.
- **Tenet 3 — `McpScriptContext` is last-resort.** Tests that need a
  helper inside `steroid_execute_code` must write the helper *as* an
  IntelliJ API call, not as a request to extend the context class.

Read [`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md) before proposing
changes that would expand the tool surface or the script-context
surface.

## Researching IntelliJ APIs — Use MCP Steroid + Debugger

**The IntelliJ project is open in the IDE (`~/Work/intellij`).** Use `steroid_execute_code`
with `project_name="intellij"` to research APIs directly via PSI — this is faster and more
accurate than file-based search.

**Avoid full Kotlin reference resolution on the IntelliJ monorepo.** `ReferencesSearch.search(target, scope)`
over `~/Work/intellij` works but emits severe Kotlin FIR logs (`KaFirReferenceResolver`,
`Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl`). For usage/reference counts at
monorepo scale, narrow candidates with `CacheManager.getVirtualFilesWithWord(name, UsageSearchContext.IN_CODE,
scope, true)` + `KtCallExpression`/PSI filtering inside `smartReadAction` (no full resolve, no FIR severe
logs) — see `IntelliJThisLoggerLookupTest`.

**Use the debugger instead of reading code** — when you need to understand runtime behavior
(e.g., what `UnknownSdkTracker` actually does, how `MavenRunConfigurationType` launches a process),
set a breakpoint and step through. This is significantly more effective than reading source:
- Set breakpoint: `steroid_execute_code` with `XDebuggerUtil.toggleLineBreakpoint()`
- Launch debug: fire `DebugClass` context action or create a debug run config
- Evaluate expressions at breakpoint: `XDebugSession.currentStackFrame.evaluateExpression()`
- See `mcp-steroid://prompt/debugger-skill` for the full workflow

**Pattern: Find a class and inspect its methods**
```
// steroid_execute_code on project "intellij"
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.allScope(project)
val files = readAction { FilenameIndex.getVirtualFilesByName("MavenRunConfigurationType.java", scope) }
files.forEach { f ->
    val lines = String(f.contentsToByteArray(), f.charset).lines()
    lines.forEachIndexed { idx, line ->
        if (line.contains("fun runConfiguration") || line.contains("@Deprecated") || line.contains("@ApiStatus")) {
            println("L${idx+1}: ${line.trim()}")
            for (i in 1..5) { lines.getOrNull(idx+i)?.let { println("  L${idx+1+i}: ${it.trim()}") } }
        }
    }
}
```

**Why this is better than file search:**
- O(1) indexed lookup via `FilenameIndex` — no `find` or `grep` needed
- Can use PSI to resolve types, find usages, check deprecation annotations
- Works on ALL open projects (intellij, mcp-steroid, jb-cli)
- Finds internal/non-exported classes that grep might miss

**Both you and sub-agents MUST use MCP Steroid** for IntelliJ API research — not file search tools.

## Debugging a stuck/hung Docker test — collect thread dumps FIRST

When a `test-integration` or `test-experiments` test hangs (IDE window never appears,
project never finishes importing, `waitForIdeWindow` times out, assertions stall), **do NOT
kill the Gradle task first**. The JVM inside the container is still alive and holds all the
evidence you need in a thread dump. `--rm` only runs on container stop, so you have time to
poke at it.

### Recipe

```bash
# 1. Find the running IDE container (most recent, image built from the test's Dockerfile)
docker ps --format '{{.ID}}\t{{.Image}}\t{{.Names}}\t{{.CreatedAt}}'

# 2. Find the IDE JVM PID inside that container (it's always com.intellij.idea.Main)
docker exec <CONTAINER_ID> jps -l
# → e.g.   766 com.intellij.idea.Main

# 3. Take a full thread dump (includes coroutine dump via the plugin's DebugProbes)
docker exec <CONTAINER_ID> jcmd <PID> Thread.print > /tmp/ide-thread-dump.txt

# 4. Inspect — the EDT (AWT-EventQueue-0) is the primary suspect for modal-dialog hangs
grep -n "AWT-EventQueue-0" /tmp/ide-thread-dump.txt   # find line number
sed -n '<LINE>,$p' /tmp/ide-thread-dump.txt | head -80  # read its stack
```

### What the stack tells you

| Symptom on EDT | Likely cause |
|---|---|
| `DialogWrapperPeerImpl.show` → `MessageDialogBuilder$YesNo.ask` → `UnknownSdkFixActionDownloadBase.collectConsent` | A named SDK (e.g. `corretto-21`) is pinned in `.idea/misc.xml` or `.idea/gradle.xml` and the container doesn't have a `ProjectJdkTable` entry with that exact name. IntelliJ fires `SdkLookup` at project open, which proposes a download, which blocks the EDT on a YesNo modal. |
| `DialogWrapperPeerImpl.show` → `MessageDialogBuilder$YesNo.ask` → `ClassicUiToIslandsMigration` or similar | A "Meet the Islands Theme" / onboarding modal. Fix via `early-access-registry.txt` + `options/other.xml` startup stubs (see `writeEarlyAccessRegistry` / `writeStartupProperties` in `intelliJ.kt`). |
| Deep inside `VfsData` init under `fleet.kernel.Transactor` with `urlopen`/`socket` frames | `AIPromoWindowAdvisor` is blocking startup on a `frameworks.jetbrains.com` HTTP fetch. Fix via `-Dllm.show.ai.promotion.window.on.start=false` + the AI-promo startup stubs. |

### Finding the *caller* that triggered the modal

The EDT frame only shows the dialog itself. The real caller is usually another thread
blocked on `invokeAndWait`:

```bash
grep -n "UnknownSdk\|SdkLookup\|SdkType\|Workspace\|ApplicationImpl pooled thread" /tmp/ide-thread-dump.txt
```

Look for the pooled thread whose stack ends in `SwingUtilities.invokeAndWait` + the relevant
IntelliJ method. That thread's Kotlin frames (if any) identify which entry point kicked off
the modal (e.g. `SdkLookupContextEx.runSdkResolutionUnderProgress` → Gradle plugin called
`SdkLookup.newLookupBuilder().executeLookup()` because of `gradleJvm="corretto-25"` in
`.idea/gradle.xml`).

### Only kill the container after you have the dump

`docker stop <CONTAINER_ID>` after saving the dump, then Ctrl-C the Gradle task. Copy the
dump out of `/tmp` into the failing test's `run-*/intellij/` folder if you plan to iterate
on the fix — keeping the dump alongside the run-dir artifacts (video, screenshots, logs)
makes later comparisons trivial.

## Remote-debugging the Dockerized IDE (attach a JVM debugger live)

Thread/coroutine dumps tell you *where* the IDE JVM is parked; for *why* (variable values,
stepping, conditional breakpoints in the plugin) attach a real debugger to the in-container
IDE. **The IDE JVM always starts with the JDWP agent open** — no flag needed:

- `intelliJ.kt`'s `generateVmOptions()` always appends
  `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${IDE_DEBUG_PORT.containerPort}`.
- **`suspend=n` is mandatory for `:test-integration` AND `:test-experiments`** (they share this
  infra). It means the IDE never waits for a debugger, so normal/CI runs are unaffected. **NEVER
  flip it to `suspend=y`** to debug a startup hang — on CI nobody attaches, so the JVM would block
  and the whole test would hang until its timeout. Set breakpoints and attach live instead; the
  agent stays open the whole run.
- `IDE_DEBUG_PORT` (`ContainerPort(5005)`, defined in `intelliJ.kt`) is exposed on the
  container (`StartContainerRequest.ports(...)` in `intelliJ-factory.kt`) and Docker maps it
  to a random host port.
- The mapped **host** port is printed to the test console — in the JVM's own JDWP wording so it is
  instantly recognisable, but with the host-mapped port (the in-container port is invisible from
  the host) — `Listening for transport dt_socket at address: <host-port>`, plus
  `[IDE-DEBUG] attach … to localhost:<host-port>` and `IDE_DEBUG_PORT=<host-port>` in
  `session-info.txt`.

### Attach from IntelliJ

1. Start any test-integration test (or a `*PlaygroundTest*` for an indefinitely-held IDE).
2. Grab the port: `grep IDE_DEBUG_PORT $(ls -t test-integration/build/test-logs/test/run-*/session-info.txt | head -1)`
   (or read the `[IDE-DEBUG]` console line).
3. In *this* IntelliJ (the mcp-steroid project), **Run → Edit Configurations → + → Remote JVM
   Debug**, host `localhost`, port `<host-port>`, classpath of module `ij-plugin`. Debug.
4. Set breakpoints in plugin sources (e.g. `DialogKiller.kt`, `DialogWindowsLookup.kt`,
   `ScriptExecutor.kt`) and drive the IDE via MCP / the test to hit them. Because the agent
   is `suspend=n`, attach/detach any time while the container is alive.

This is the live-debug counterpart to the dump-first recipe above; reach for the debugger when
a one-shot dump isn't enough (intermittent hangs, modality/EDT timing, value inspection).

To attach **programmatically** (from `steroid_execute_code`, instead of the Run-config UI) —
e.g. to script an attach to the mapped port from a controlling IDEA — see the recipe
`mcp-steroid://debugger/debug-attach-remote-jvm`
(`prompts/src/main/prompts/debugger/debug-attach-remote-jvm.md`): `RemoteConfiguration` with
`SERVER_MODE=false` at `localhost:<host-port>`, or the low-level
`DebuggerManagerEx.attachVirtualMachine`.

### Debugging the in-container devrig (npx-kt) JVM too

When a test runs the agents through the **devrig stdio bridge** (`AiMode.AI_DEVRIG`), the devrig
JVM gets its **own** JDWP agent on a **different** port — `DEVRIG_DEBUG_PORT` (`ContainerPort(5006)`,
in `intelliJ.kt`) — so the IDE and devrig can be debugged at the same time:

- `DevrigSteroidDriver.deploy` writes the launcher with
  `export DEVRIG_OPTS="-agentlib:jdwp=…,server=y,suspend=n,quiet=y,address=*:5006 …"`.
  `DEVRIG_OPTS` is the Gradle-application opts var (only the `devrig` launch reads it — it does
  **not** leak into child JVMs and double-bind the port, which `JAVA_TOOL_OPTIONS` would).
  `quiet=y` keeps the agent's own "Listening …" line **off stdout** — `devrig mcp` speaks JSON-RPC
  on stdout, so a stray line would corrupt the protocol.
- The port is published (`.ports(… DEVRIG_DEBUG_PORT)`) and Docker-mapped. The host-mapped port is
  printed on the host in the JVM's own wording — `Listening for transport dt_socket at address:
  <host-port>` + `[DEVRIG-DEBUG] attach … (module npx-kt) …` — and saved to `session-info.txt` as
  `DEVRIG_DEBUG_PORT=<host-port>`.
- Attach a **second** "Remote JVM Debug" with classpath of module **`npx-kt`** to that host port.

Caveat: the agent (`address=*:5006`, `server=y`) binds a fixed port, so it assumes a single live
`devrig` JVM per container (the long-lived `devrig mcp` MCP server). A concurrent second `devrig`
invocation in the same container would fail with "Address already in use" — fine for the stdio-bridge
tests, where only `devrig mcp` runs.

## RLM Analysis of Arena Runs (run-*/intellij/mcp-steroid/)

Each arena run creates server-side exec_code logs at `run-*/intellij/mcp-steroid/eid_*`. Structure:
- `reason.txt` — agent's intent for the call
- `script.kts` / `script-wrapped.kts` — actual Kotlin code executed
- `output.jsonl` — execution output (each line: `{"text":"..."}`)
- `success.txt` / `compilation-success.txt` — result status
- `params.json` — timeout, task_id, etc.
- `compiled/` — compiled class files

### Execution Pattern (confirmed across 6 scenarios)

Infrastructure calls (task_id: `integration-test`) run during environment setup, OUTSIDE
the agent measurement window — they are not bottlenecks. Only agent calls count.

**Agent calls (1-3 per scenario, inside measurement window):**
1. **VCS + env check**: Docker, Maven path, JDK list, VCS-modified files
2. **Compile check**: `ProjectTaskManager.buildAllModules()` — ALWAYS triggers "Resolving SDKs..." modal
3. **Error inspection** (optional): Check problem list when build reports errors

### Known Bottleneck: "Resolving SDKs..." Modal Dialog

Every `ProjectTaskManager.buildAllModules()` triggers a `Resolving SDKs...` modal that the
`modal=smart_non_modal` sweep / `closeModalDialogs()` dismisses. This causes `Build errors: true,
aborted: false` even when compilation actually succeeded. The agent then wastes an exec_code call checking the empty problem list,
then falls back to `./mvnw test-compile` via Bash (25-60s).

**Confirmed across ALL 6 scenarios**: modal fires, `Build errors: true`, problem list is empty.
In 2 of 6 scenarios, `Build errors: false` is correctly reported (modal may have resolved faster).

### Key Findings for Prompt Optimization

1. **Agents never use exec_code for test execution** — only for VCS check + compile check
2. **Agents never read MCP Steroid skill resources** (0/6 scenarios read `mcp-steroid://` URIs)
3. **JDK list is printed in first call** but agents still try wrong JDKs via Bash
4. **"Build errors: true" false positive** wastes 1 exec_code + 1 Bash call per scenario

## Configuring the IDE — pre-write VALIDATED XML before start, else `mcpExecuteCode` after

There are two mechanisms; pick by *when* the state must exist:

- **Before the IDE starts → pre-write XML** (`jdk.table.xml`, `misc.xml`'s `project-jdk-name`,
  `gradle.xml`'s `gradleJvm`). The JDK table MUST exist before project-open, because Gradle
  auto-import resolves the project JDK at open; registering JDKs only *after* the IDE is up loses
  the race and the import stalls ~8 min on `Observation.awaitConfiguration` (see
  [[jdk-table-preload-and-gradle-jvm]] memory; commits `8f07d7a5`..`8a23ea3b`). This is now the
  **primary** path and supersedes the post-open `mcpRegisterJdks` for the stall.
- **After the IDE is up → `mcpExecuteCode`** for anything that doesn't need to exist at open time
  (verification, module SDK tweaks). Still the right tool there.

**The old "never hand-write XML" rule was about *ad-hoc* XML.** A single unescaped `"` once made
`FileBasedStorage` reject `jdk.table.xml` (silent `WARN Cannot read …`), leaving the table empty and
deadlocking on a download-consent modal. The fix was NOT "never XML" — it is **generate the XML with
the XML APIs (jdom2) so it can't be malformed, and validate it against IntelliJ's own serialization**:
- `jdk-table-xml.kt` (`generateJdkTableXml`) renders the table by reading the container's JDK
  artifacts (`release`→`MODULES` for classPath sorted; `src.zip` top dirs for sourcePath; version
  from `release`; JDK ≤ 8 named `1.8`), mirroring `JavaSdkImpl`/`ProjectJdkImpl`. A
  `JdkTableIntegrationTest` asserts it equals what a live IntelliJ produces, AND that IntelliJ loads
  every entry at startup.
- `IntelliJDriver.configureProjectJdk` / `configureGradleJdk` patch `misc.xml` (`ProjectRootManager`'s
  `project-jdk-name`) and `gradle.xml` (`gradleJvm`) via jdom2 (create file/elements when absent),
  reading the file with `copyFromContainer` and writing with `copyToContainer`/`writeFileInContainer`.
- Projects declare their JDK + build systems: `IntelliJProject.jdkVersion` +
  `buildSystems: Set<ProjectBuildSystem>` (each with its own root file). `waitForProjectReady` imports
  each declared build system via the external-system API instead of the stalling `awaitConfiguration`
  NONE path. `EmptyProject` (README-only, no build system, no JDK) is for tests where the project is
  irrelevant (dialog killer, infra) — fast startup, no import.

The remaining `mcpExecuteCode` rationale (typed, fails loud) still holds for the post-open path.

The `mcpExecuteCode` path is strictly better: Kotlin is type-checked at
runtime, the canonical IntelliJ API (`JavaSdk.createJdk(name, path,
false)` / `ProjectJdkTable.addJdk` inside `writeAction { }`, or
`SdkConfigurationUtil.createAndAddSDK`) does all of the classpath /
`jrt://` wiring for us, and every failure lands as a normal exception
in the script output instead of a silent WARN.

Use the atomic driver APIs — don't hand-roll new `mcpExecuteCode`
scripts that touch `ProjectJdkTable`:

```kotlin
// Query what's registered
val jdks: List<JdkInfo> = session.mcpSteroid.mcpListJdks()

// Add one — idempotent, skips if `findJdk(name) != null`
session.mcpSteroid.mcpAddJdk(
    name = "corretto-21",
    homePath = "/usr/lib/jvm/temurin-21-jdk-arm64",
)

// Bulk: discover every Temurin dir in /usr/lib/jvm and register it under
// three aliases each — bare, corretto-N, temurin-N — so projects checked
// into VCS with `project-jdk-name="corretto-21"` resolve locally instead
// of triggering SdkLookup's download-consent modal.
session.mcpSteroid.mcpRegisterJdks(guestProjectDir)
```

Under the hood both `mcpListJdks` and `mcpAddJdk` issue a single
`steroid_execute_code` call — the embedded Kotlin uses the shortest
named-JDK path available: `JavaSdk.createJdk(name, home, isJre=false)`
plus `writeAction { ProjectJdkTable.addJdk(sdk) }`. This is two lines
because `createAndAddSDK(path, sdkType)` auto-generates a unique name
(e.g. `21-ea-1758`), which breaks the whole point of matching
`project-jdk-name="corretto-21"`.

`mcpRegisterJdks` is also called automatically by
`IntelliJ_factoryKt.create` right after `waitForMcpReady`, racing the
project-open `SdkLookup` so `findJdk(sdkName)` sees our aliases before
the download-consent modal path can fire. Tests only need to call it
again if they want to verify the state.

### Still-acceptable XML touches

The launch-time startup XML we write from Kotlin (
`options/AIOnboardingPromoWindowAdvisor.xml`, trusted-paths, consent,
early-access-registry) stay because they control bits that must be set
**before** the IDE starts — so there is no MCP server to talk to yet.
Keep those small, copy them verbatim from IntelliJ's own defaults, and
never let them carry user-provided values that could go wrong at
render time.

### Modal dialogs must never block the harness

As belt-and-suspenders against the modals that fire during project
open, import, and compile, the IDE `.vmoptions` disables **three**
registry keys — one for each entry point the IntelliJ SDK-resolution
code can take into a modal dialog:

| VM flag | Gated code path | What the modal would be |
|---|---|---|
| `-Dunknown.sdk=false` | `UnknownSdkTracker.isEnabled()` at `UnknownSdkTracker.java:57` | no tracker activity; no download fixes ever created |
| `-Dunknown.sdk.auto=false` | `UnknownSdkTracker.createCollector()` at `UnknownSdkTracker.java:76` | tracker exists but never runs; `onLookupCompleted` skipped, no `UnknownSdkFixActionDownloadBase` → no `collectConsent` |
| `-Dunknown.sdk.modal.jps=false` | `CompilerDriverUnknownSdkTracker.fixSdkSettings` at `CompilerDriverUnknownSdkTracker.kt:41` | `Task.WithResult` modal 'Resolving SDKs…' fires during `ProjectTaskManager.build()` |

With all three on, every async `SdkLookup` caller is silenced before
it can request user consent. `mcpRegisterJdks` (from
`IntelliJ_factoryKt.create` immediately after `waitForMcpReady`)
still seeds `ProjectJdkTable` with the three alias forms — bare
version, `corretto-N`, `temurin-N` — so modules that reference a
named JDK in `.idea/*.xml` still resolve without a lookup round-trip.

`waitForIdeWindow` fails fast on any modal detected during startup —
see `IntelliJContainer.kt`. When that trips, the saved PNG under
`run-*/screenshot/` shows which dialog is up and the thread-dump
recipe higher in this file identifies the caller.

### Non-Java IDEs skip JDK setup

`mcpRegisterJdks` / `mcpAddJdk` / `mcpSetProjectSdk` all import
`com.intellij.openapi.projectRoots.JavaSdk`, which is only on the
script classpath in IDEs that bundle the `com.intellij.java` plugin
(IntelliJ IDEA). PyCharm, GoLand, WebStorm, and Rider all fail to
compile `mcpListJdks`'s script with `unresolved reference 'JavaSdk'`.

To handle this cleanly, `IdeProduct` carries a `hasJavaSdk: Boolean`
flag. Both the factory's early-JDK hook and
`IntelliJContainer.waitForProjectReady` gate the JDK steps on it, so
a non-Java IDE logs `Skipping JDK setup — <IDE> has no Java plugin`
and moves on. If you add a new IDE product, set the flag truthfully.

## Architecture

```
test-integration/
  src/main/kotlin/.../infra/   # Shared infrastructure (containers, drivers, MCP client)
                               # — published as a regular library so :test-experiments can reuse it
  src/main/resources/skills/   # MCP skill resources loaded via classpath
  src/test/
    docker/
      ide-base/          # Base Docker image (Debian + X11 + agents)
      ide-agent/         # IDEA-specific image (extends base + JDKs)
      rider-agent/       # Rider-specific image (extends base + .NET SDK)
      goland-agent/      # GoLand-specific image (extends base)
      pycharm-agent/     # PyCharm-specific image (extends base)
      webstorm-agent/    # WebStorm-specific image (extends base)
      test-project/      # Kotlin project with intentional bug (IDEA)
      test-project-rider/  # .NET project with intentional bug (Rider)
      test-project-goland/ # Go project (GoLand)
      test-project-pycharm/# Python project (PyCharm)
      test-project-webstorm/# JS project (WebStorm)
    kotlin/.../
      infra/             # Pure-JVM unit tests for the infra (no Docker)
      tests/             # Stable Docker smoke tests (release matrix)
```

The Docker image / fixture-project tree under `src/test/docker/` is referenced by both modules
via the `test.integration.docker` system property (set per test task in each module's
`build.gradle.kts`). `:test-experiments` reads it as a sibling-project resource.

## Playground Tests for Interactive Debugging

Playground tests start an IDE in Docker and block indefinitely, allowing you to connect
to the running IDE via MCP and experiment interactively. This is the primary technique
for developing and debugging IDE-specific features.

### How to Start

```bash
./gradlew :test-integration:test --tests '*RiderPlaygroundTest*' \
  -Dtest.integration.ide.product=rider
```

After startup, the test prints connection info. Also check `session-info.txt` in the run directory:
```
MCP_STEROID=http://localhost:<port>/mcp
VIDEO_DASHBOARD=http://localhost:<port>/
```

### Connecting to the Playground

**Via curl (for quick API calls):**
```bash
# List projects
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"steroid_list_projects","arguments":{}}}' | python3 -m json.tool

# Execute code
cat > /tmp/mcp-request.json << 'EOF'
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"steroid_execute_code","arguments":{
  "project_name":"DemoRider",
  "reason":"test something",
  "task_id":"playground",
  "code":"println(project.name)"
}}}
EOF
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d @/tmp/mcp-request.json | python3 -m json.tool

# Discover actions at a file location — there is no dedicated MCP tool;
# fetch the recipe and run it inside steroid_execute_code instead.
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"steroid_fetch_resource","arguments":{
    "project_name":"DemoRider",
    "uri":"mcp-steroid://ide/action-discovery"
  }}}' | python3 -m json.tool

# Take a screenshot
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"steroid_take_screenshot","arguments":{
    "project_name":"DemoRider",
    "reason":"check IDE state",
    "task_id":"playground"
  }}}' | python3 -m json.tool
```

**Via Claude Code (full interactive session):**
```bash
claude --mcp-config '{"mcpServers":{"mcp-steroid":{"url":"http://localhost:<PORT>/mcp"}}}'
```

**Tip**: For complex execute_code payloads with string escaping issues, write the JSON
to a file and use `curl -d @/tmp/request.json` instead of inline `-d`.

### Available Playgrounds

| Test Class | IDE | Command |
|---|---|---|
| `RiderPlaygroundTest` | Rider | `--tests '*RiderPlaygroundTest*' -Dtest.integration.ide.product=rider` |

To create a playground for another IDE, copy `RiderPlaygroundTest.kt` and change the
`IdeProduct` and `consoleTitle`.

## How Rider Test Execution Was Discovered

This documents the experimental process used to find the correct APIs for running
.NET tests in Rider, as a reference for similar investigations with other IDEs.

### Problem

The existing test infrastructure used JUnit-specific APIs (`JUnitConfiguration`,
`JUnitConfigurationType`, `SMTRunnerConsoleView`) for running and inspecting tests.
These classes do not exist in Rider. We needed to find Rider's native test execution
mechanism.

### Step 1: Research in IntelliJ Source

Searched `~/Work/intellij` for Rider's unit test implementation:

- Found `RiderUnitTesting.xml` in `rider/resources/META-INF/` — registers all action IDs
- Found action classes in `rider/src/com/jetbrains/rider/unitTesting/actions/`
- Key actions: `RiderUnitTestRunContextAction`, `RiderUnitTestDebugContextAction`
- These extend `RiderAnAction` which dispatches to the ReSharper backend via the RD protocol

Key finding: Rider's test runner is fundamentally different from IDEA's. Tests are discovered
and executed by the ReSharper backend (C#), not by the IntelliJ frontend (Java/Kotlin).
The frontend actions just serialize the editor context and send it to the backend.

### Step 2: Start the Playground

```bash
./gradlew :test-integration:test --tests '*RiderPlaygroundTest*' \
  -Dtest.integration.ide.product=rider
```

Waited for `session-info.txt` to appear with the MCP URL.

### Step 3: Discover Available Actions

The dedicated `steroid_action_discovery` MCP tool was removed (May 2026).
The `mcp-steroid://ide/action-discovery` recipe documents the equivalent
`DaemonCodeAnalyzer.restart` + `ShowIntentionsPass.getActionsToShow`
pattern to run inside `steroid_execute_code`. At the time of this
investigation, running the tool with the caret on `class LeaderboardTests`
at offset 660 confirmed that `RiderUnitTestRunContextAction` and
`RiderUnitTestDebugContextAction` are present and enabled in the editor
popup menu — the same shape the recipe surfaces today.

### Step 4: Execute Run Action

Used `steroid_execute_code` to open the test file, position the caret, and fire the action:

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Open test file
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance()
    .refreshAndFindFileByPath(basePath + "/DemoRider.Tests/LeaderboardTests.cs")
    ?: error("Test file not found")

val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
    ?: error("No text editor")
val editor = textEditor.editor

// Position caret on test class
val text = editor.document.text
val classOffset = text.indexOf("class LeaderboardTests")
withContext(Dispatchers.EDT) {
    editor.caretModel.moveToOffset(classOffset + 6)
}

// Fire the action
val action = ActionManager.getInstance().getAction("RiderUnitTestRunContextAction")
    ?: error("Action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(
        dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null
    )
    ActionUtil.performAction(action, event)
}
println("Tests started")
```

### Step 5: Verify Results

Took a screenshot — Rider's Unit Test tool window appeared at the bottom showing
test results with failures (the intentional bug).

Checked `RunContentManager.getInstance(project).allDescriptors` — returned 0 descriptors.
This confirmed that Rider does NOT use the standard `RunContentManager` / `SMTRunnerConsoleView`
infrastructure. Test results live in Rider's own unit test session model.

### Step 6: Verify Debug Action

Ran the same pattern with `RiderUnitTestDebugContextAction`. It worked — the action
was accepted and executed. Without breakpoints set, the debug session ran to completion
immediately (`XDebuggerManager.debugSessions` was empty after), confirming tests executed.

### Key Findings

1. **Action pattern works**: Open file → position caret → get DataContext from
   `editor.contentComponent` → create `AnActionEvent` → `ActionUtil.performAction()`
2. **Context matters**: The caret must be on a test class or method for the action
   to know which tests to run
3. **No RunContentManager**: Rider test results are NOT accessible via the standard
   `RunContentManager` / `SMTRunnerConsoleView` APIs
4. **XDebugger APIs work**: `XDebuggerManager`, `XDebuggerUtil`, breakpoints, and
   expression evaluation all work identically in Rider (platform-level APIs)
5. **AnActionEvent.createFromAnAction is deprecated**: Use `AnActionEvent.createEvent()`
   with `ActionUiKind.NONE` instead

### Product Conditionals

To provide Rider-specific guidance in MCP resources, we use runtime conditionals:

```markdown
###_IF_RIDER_###
Rider-specific content here (uses RiderUnitTestRunContextAction)
###_ELSE_###
IDEA-specific content here (uses JUnitConfiguration)
###_END_IF_###
```

These are processed at runtime in `ResourceRegistrar.kt` based on `ApplicationInfo.build.productCode`.
Conditionals must be in the article body, never in the header (title/description).

## Rider/.NET Test Execution — Quick Reference

**Run tests from editor context:**
1. Open test `.cs` file with `FileEditorManager.openFile()`
2. Position caret on test class/method with `editor.caretModel.moveToOffset()`
3. Get DataContext: `DataManager.getInstance().getDataContext(editor.contentComponent)`
4. Fire action: `ActionUtil.performAction(action, event)` with `RiderUnitTestRunContextAction`

**Debug tests from editor context:**
Same as above but use `RiderUnitTestDebugContextAction`.
Set breakpoints first via `XDebuggerUtil.toggleLineBreakpoint()`.

**Action IDs:**
| Action ID | Purpose |
|---|---|
| `RiderUnitTestRunContextAction` | Run tests at caret |
| `RiderUnitTestDebugContextAction` | Debug tests at caret |
| `RiderUnitTestRunSolutionAction` | Run all tests in solution |

**What does NOT work in Rider:**
- `JUnitConfiguration` / `JUnitConfigurationType` — Java-only
- `ApplicationConfiguration` / `ApplicationConfigurationType` — Java-only
- `RunContentManager.allDescriptors` — empty for Rider test runs
- `SMTRunnerConsoleView` — not used for .NET tests

## Multi-Version Compatibility Tests

Build the plugin against IntelliJ 2025.3 (253), then run the same binary against newer IDEs.

| Test (lives in `test-integration`) | Validates | How |
|------|------------------|-----|
| `PluginBuildCompatibilityTest` | Plugin **compiles** against newer SDKs | Docker + `./gradlew buildPlugin` with patched versions |
| `PluginVerificationTest` | Built binary is **API-compatible** | Docker + Plugin Verifier (`verifyPlugin`) |
| `PluginRuntimeCompatibilityTest` | Plugin **runs** correctly in newer IDEs | Docker IDE container + MCP tool calls |

`PluginRuntimeCompatibilityTest` exercises `list_windows`, which triggers mcp-steroid#18
(ClassCastException on the `kotlin.Pair` vs `c.i.o.u.Pair` type change in 262).

```bash
./gradlew :test-integration:test --tests '*PluginBuildCompatibilityTest*'
./gradlew :test-integration:test --tests '*PluginRuntimeCompatibilityTest*'
./gradlew :test-integration:test --tests '*PluginVerificationTest*'
./gradlew :test-integration:test --tests '*PluginBuildCompatibilityTest.build plugin with IntelliJ 2025_3*'
```

Note: `PluginVerificationTest` lives inside `PluginBuildCompatibilityTest.kt`, not its own file.

Each compat test mounts the project read-only into a Docker container (`docker/build` image: Debian +
JDK 21 + git), copies to a build dir, cleans with `git clean -fdx`, applies version patches via `sed`,
then runs `./gradlew :ij-plugin:buildPlugin`. Persistent caches under `build/build-compat/` (Gradle
home, `.intellijPlatform`) make re-runs fast.

### Version patches

| Target IDE | Patches needed |
|------------|---------------|
| 2025.3 | None (project default) |
| 2026.1 | Kotlin → 2.4.0-Beta1 (IDE bundles metadata 2.4.0) |
| 262-SNAPSHOT | Kotlin → 2.4.0-Beta1 + plugin 2.14.0 + `useInstaller = false` + `nightly()` repo |

### IntelliJ Platform Gradle Plugin — snapshot resolution

The plugin (v2.13.1 in project, v2.14.0 latest) resolves IDEs in two modes:
- **Installer** (`useInstaller = true`, default): downloads `.zip` / `.dmg` from
  `download.jetbrains.com`. Releases only.
- **Maven** (`useInstaller = false`): resolves from Maven repos (`snapshots()`, `nightly()`).
  Required for snapshot/nightly versions.

Nightly builds (`262-SNAPSHOT`) need: (1) `nightly()` repo (may need auth/VPN), (2)
`useInstaller = false`, (3) plugin version ≥ 2.14.0. Source: cloned at
`~/Work/intellij-platform-gradle-plugin/` — key files
`IntelliJPlatformDependenciesHelper.kt`, `IntelliJPlatformRepositoriesExtension.kt`,
`RequestedIntelliJPlatformsService.kt`.

## Docker-test CI gotchas (TeamCity, Linux)

These rules govern how Docker-based tests behave on TeamCity Linux agents. Most surface only on Linux
(macOS/Docker Desktop's virtiofs hides UID issues).

### TeamCity DSL build wiring

(Implemented in the separate `~/Work/mcp-steroid-teamcity` repo. See its own `CLAUDE.md` for the
generate→edit→regenerate→commit workflow. Rules below describe what each Docker test build needs.)

- **`BuildType.requireLinuxDocker()` helper** (`utils/LinuxDocker.kt`): every build that shells out to
  `docker` applies `exists("docker.version")` + Linux_amd64 + `dockerSupport { loginToRegistry =
  on(PROJECT_EXT_789) }`. Registry login routes pulls through `registry.jetbrains.team`, avoiding the
  daemon mirror that occasionally 503s.
- **`freeDiskSpace { requiredSpace = "20gb"; failBuild = true }`** on every Docker test build. IDE
  image (~1.5 GB) + plugin + base layer + per-test run-dir easily hit 15 GB+; without the gate,
  mid-build ENOSPC shows up as cryptic `docker cp: Could not find the file …` against `/mcp-run-dir`.
- **`publishArtifacts` emission pattern.** Emit AT TEARDOWN (lifetime cleanup action), NOT at container
  creation — TC processes the service message immediately, not at end-of-build. Use the recursive-glob
  form (`<path>/<star><star> => <dest>`); a literal `<dir> => <zip>` spec on an empty dir yields
  "Artifacts path '…' not found". Publish video + screenshots as standalone artifacts (per-run folders)
  in addition to the zip so TC's in-browser MP4 / image preview works without downloading the zip.
- **`-PtestFilter=<pattern>` in `build.gradle.kts`, NOT `--tests` on the CLI.** TC's Gradle runner emits
  `gradleParams` BEFORE task names in the final invocation, which detaches `--tests` from its task. A
  project-property applied programmatically via `Test#filter` is task-position-independent.
- **`gradleParams` values are NOT shell-quoted.** TC passes them directly to gradle. Wrapping a value
  in single quotes (e.g. `"-PtestFilter='*X'"`) makes the project property contain literal quotes,
  which never match anything. Patterns must be whitespace-free single tokens — no quoting needed.
- **Shared secrets at the root project**, not on individual builds. Declare via
  `params { param("env.X", Ref(…).toString()) }` in `settings.kts`. TC param inheritance is parent →
  child only (NOT peer-to-peer); declaring a `credentialsJSON:UUID` on a single build leaves peers
  failing with "unresolved TeamCity reference". When a build's `properties.property` REST field shows
  the literal `%credentialsJSON:…%` instead of `******`, the substitution failed and tests MUST fail
  hard in that state — no `TestAbortedException` / `Assume.assumeTrue` to paper over it.

### Linux bind-mount UID mismatch

- **Bind mounts do NOT remap UIDs on Linux.** A host directory owned by the TC-agent user (uid e.g.
  999) is still owned by that uid inside the container, so a container user `agent` (uid 1000) cannot
  write to `/mcp-run-dir`. macOS/Docker Desktop's virtiofs VM handles UID mapping transparently, hiding
  this locally.
- **Fix pattern:** call `File.setReadable(true, false)` / `setWritable(true, false)` /
  `setExecutable(true, false)` on the host dir after `mkdirs()` and before the container starts.
  Ownership still mismatches; mode bits 777 let any uid write.
- **`git`'s "dubious ownership" check** fires on the same UID mismatch when a read-only bind mount is
  a git repo (e.g. `/repo-cache`). The only workaround that works on TC's git build is
  `git config --global --add safe.directory <path>` as a SEPARATE exec before the clone. The
  container's `~/.gitconfig` is ephemeral so this doesn't leak. `git -c safe.directory=*` and
  `git -c safe.directory=<path>` were rejected by the TC-agent's git build.
- **`escapeShellArgs` must quote glob/meta chars.** Args passed through
  `docker exec … bash -c "<joined args>"` are subject to shell word-splitting; `*`, `?`, `[`, `]`, `$`,
  `;`, `&`, `|`, `<`, `>`, `(`, `)`, `!` — quote every one or tokens get rewritten silently
  (e.g. `safe.directory=*` → `safe.directory=<cwd-file-1>`).
- **No host-credential forwarding into containers.** `setupHostMappings` (`infra/docker-flip.kt`) now
  maps **only** the Docker socket (`mountDockerSocket`); the former SSH-agent / `~/.netrc` /
  `~/.m2/settings.xml` / JetBrains-token / private-packages / JB_SPACE forwarding was removed as insecure
  (it copied host secrets into the container wholesale). The `mountSshAgent` opt is gone. No DPAIA arena /
  debugger / bright-scenario test needs it — they use public HTTPS clones and local Maven/Gradle drivers.
  Reintroduce any forwarding later behind a proper, opt-in, least-privilege mechanism as its own
  dedicated function (mirror `dockerSocketMapping`).

### Windows CI compatibility (per-OS Gradle test matrix)

- `BufferedWriter.newLine()` writes `\r\n` on Windows — use `write("\n")` for protocol output (NDJSON,
  MCP).
- `File.readText()` preserves `\r\n` — normalize with `.replace("\r\n", "\n")` when comparing against
  generated text.

### API keys on TC

Credentials stored as `credentialsJSON:*` on the TC server, referenced via `Tokens.kt` in the TC repo:

| Token | Env var on agent | Used by |
|-------|-----------------|---------|
| `ANTHROPIC_TOKEN_KEY_REF` | `ANTHROPIC_API_KEY` | `test-integration` (Cli Claude tests) |
| `OPENAI_TOKEN_KEY_REF` | `OPENAI_API_KEY` | `test-integration` (Cli Codex tests) |
| `TBE_PLUGINS_TOKEN_REF` | — (inline in script) | `Deploy plugin to TBE` |

**Missing:** `GEMINI_API_KEY` — no `credentialsJSON` ref exists yet. `CliGeminiIntegrationTest` opts
into `skipTestWhenKeyMissing` (see root CLAUDE.md → BANNED → Gemini exception).

## Agent output filters & NDJSON quirks

Each agent is invoked with specific flags to produce NDJSON output piped through `agent-output-filter`:

| Agent | Output flag | Auto-approve flag | Verbose |
|-------|------------|-------------------|---------|
| Claude | `--output-format stream-json` | `--permission-mode bypassPermissions` | **`--verbose` required** (without it, tool call details are not emitted) |
| Codex | `--json` | `--dangerously-bypass-approvals-and-sandbox` | n/a |
| Gemini | `--output-format stream-json` | `--approval-mode yolo` | n/a |

### Design principle

The output filters render NDJSON as human-readable text — they do NOT filter or suppress events. Every
event must produce some output. The only legitimate silences are structural lifecycle events whose
content arrives in a corresponding `completed` event:

- `thread.started`, `turn.started` — no content, silenced.
- `item.started` for `agent_message` — content arrives in `item.completed`.

Unknown / future event types always fall through as raw JSON so no information is lost.

### `toolDetail()` (`FilterUtils.kt`)

Extracts a human-readable summary for `>> tool_name (detail)` lines. Handles:
- `steroid_execute_code` → `reason`
- `steroid_execute_feedback` → rating + first line of explanation
- `steroid_open_project` → path
- `Read` / `Glob` / `Write` → path
- `Grep` → pattern
- Generic fallback: first short (<80 chars, no newlines) primitive value from the input JSON.

### Agent log files

`ConsoleAwareAgentSession` writes two files per `runPrompt()` call into `logDir` (always = `runDir`):
- `agent-{name}-{N}-raw.ndjson` — raw NDJSON lines from STDOUT (unfiltered).
- `agent-{name}-{N}-decoded.txt` — human-readable decoded output + stderr/info lines.

### Parsing the agent's actual tool calls (IMPROVEMENTS harness)

When a prompt-quality test needs to verify what the agent **actually called**
(e.g. did `printCsv` show up in any `steroid_execute_code` submission?),
**parse the raw NDJSON, not the decoded prose transcript**. Substring matching
on the prose is unreliable: agents fetch prompt articles via
`steroid_fetch_resource` and the article body is echoed verbatim into the
transcript — that defeats `combined.contains("printCsv")`-style checks with
false positives. The B5 / `PrintCsvPrintToonPromptTest` work hit this on round 1.

NDJSON file lookup: `test-integration/build/test-logs/test/run-<timestamp>-<title>/agent-<slug>-<n>-raw.ndjson`,
where `<slug>` is `agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")`
(`claude-code`, `codex`, `gemini`). Take the maxByOrNull-lastModified file for
the most recent `runPrompt(...)` invocation.

Three distinct tool-call shapes — a parser must handle all three:

| Agent | Locator | Tool-name format | Code field |
|---|---|---|---|
| Claude Code (2.1.x) | `obj.message.content[*]` where `type=tool_use` | `mcp__<server>__<tool>` (double underscore) | `input.code` |
| Codex | `obj.item` where `obj.type=item.completed` and `item.type=mcp_tool_call` | `item.tool` (or `item.name` on older builds), bare `steroid_execute_code` OR `__steroid_execute_code` suffix | `item.arguments.code` (fallback: `item.input.code`) |
| Gemini | Root object where `obj.type=tool_use` | `mcp_<server>_<tool>` (SINGLE underscore between `mcp` prefix and server slug) | `parameters.code` |

Match the tool name via `endsWith("steroid_execute_code")` to cover all
prefixings. Reference implementation: `PrintCsvPrintToonPromptTest.readAgentExecCodeBodies`
(`test-integration/src/test/.../tests/PrintCsvPrintToonPromptTest.kt`) — copy
into new harness tests verbatim.

Both `PrintCsvPrintToonPromptTest.readAgentExecCodeBodies` and
`FindDuplicatesPromptTest.readAgentExecCodeBodies` now handle all
three shapes (Claude, Codex, Gemini). The latter was extended during
S5 iter9 of the find-duplicates IMPROVEMENTS harness — Gemini failed
with `No steroid_execute_code calls captured in NDJSON` because its
NDJSON shape (`type=tool_use` at root, `tool_name`, `parameters.code`)
wasn't being parsed.

### `ClaudeOutputFilter`

Claude Code 2.1.x switched from streaming events (`content_block_delta`, `tool_use`, `tool_result`) to
structured `assistant` / `user` events with full `message.content` arrays. The `result.result` field is
empty in the new format; actual output is in `assistant.message.content[].type=text` blocks. The filter
handles **both formats** simultaneously for backward/forward compatibility.

MCP tool names in the new format are fully qualified: `mcp__mcp-steroid__steroid_execute_code`.
`toolDetail()` strips the prefix with `substringAfterLast("__")`.

### `CodexOutputFilter`

Codex `--json` uses different field names than Claude.

`mcp_tool_call` items (Codex actual format):
```json
{"type": "item.started",
 "item": {"id": "item_5", "type": "mcp_tool_call", "server": "mcp-steroid",
          "tool": "steroid_execute_code",
          "arguments": { "reason": "...", ... }}}
```
Note: `"tool"` (not `"name"`); `"arguments"` (not `"input"`). Completed `mcp_tool_call` result is a
structured object (`{"content": [{"type":"text","text":"..."}], "structured_content": null}`), not a
primitive.

`reasoning` items (Codex emits thinking steps):
```json
{"type": "item.completed", "item": {"type": "reasoning", "text": "Planning to..."}}
```
Rendered as `[thinking] first-non-blank-line`.

`resolveToolName()` checks `item["name"]` → `item["function"]["name"]` → `item["tool"]`.
`resolveInputObject()` checks `item["input"]` → `item["arguments"]` → `item["function"]["arguments"]`.

### Forcing agents to output required data

Use explicit output markers so agents include required information in final text (not just internal
reasoning):

```kotlin
appendLine("OUTPUT_MARKER: <required content description>")
appendLine("BUG_LINE: <the exact buggy line of code>")
appendLine("FILE_PATHS: <at least one file path ending in .java or .kt that you found>")
```

Agents (especially Gemini) sometimes find the right answer internally but omit it from final text —
markers force explicit reporting.

### Known agent quirks

- **Gemini exit 137** (SIGKILL): treat as success when NDJSON confirms success — `DockerGeminiSession`
  handles this automatically.
- **Codex exit 137** (SIGKILL): same pattern. Tests already check for required output markers before
  checking exit code.
- **Codex MCP prefix**: tool names are NOT MCP-prefixed in Codex output (no `mcp__`). Codex uses bare
  names like `steroid_execute_code`.
- **Codex `mcp_tool_call` field names**: different from `tool_call` — uses `"tool"`, `"arguments"`,
  result in `result.content[]` array. See `resolveToolName()` / `resolveInputObject()`.
- **Claude new NDJSON**: since Claude Code 2.1.x, structured `assistant`/`user` events; filter handles
  both old and new formats.

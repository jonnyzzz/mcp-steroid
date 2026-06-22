# 262 EAP support — plan and findings (2026-05-26)

**Status: implementation complete, on `origin/main`.** 30+ commits across
8 plan items + per-commit quorum fix bundles + 10 followups + final sweep.
GH Actions [run 26457342481](https://github.com/jonnyzzz/mcp-steroid/actions/runs/26457342481)
green on the JDK 25 build image. Plugin Verifier green against both
`IU-261.22158.277` and `IU-262.6228.19`. Remaining gaps tracked in
`TASKS.md` (#1 local-only `cp -a` perf, #4 / #19 needs human smoke,
#6 TC DSL repo).

Reference plan for moving the plugin build/test surface to IntelliJ Platform
**261 as the primary** and adding **262 EAP as a secondary** verification
target, while deprecating **253**.

Cross-referenced from `TASKS.md` → "Active focus — 262 EAP support
(2026-05-26)".

## Constraints (locked-in)

1. ~~**Keep root Kotlin 2.2.20.** Do not bump.~~ Updated mid-stream after
   reading the platform stdlib: `lib/util-8.jar` ships
   `kotlin/KotlinVersion.class` with `CURRENT = 2.3.20`. Root Kotlin
   bumped to **2.3.20** to match (the deprecated 2.2.20 path would have
   conflicted with the 2.3-only stdlib symbols available at runtime).
2. **Deprecate 253 entirely.** Plugin builds against 261. Tests/verifier
   cover 261 + 262.
3. **Replace `useInstaller = true`** in `pluginVerification.ides { }` with
   `local(file)` selectors fed by the in-repo `intellij-downloader` module.
4. **Use `262-EAP-SNAPSHOT`** as the per-major selector for 262 (not the
   rolling `LATEST-EAP-SNAPSHOT`, not bare `262-SNAPSHOT`). User-validated;
   the IntelliJ Platform Gradle Plugin nightly() repo accepts per-major EAP
   tags.
5. **Pin Kotlin-adjacent libs to public Maven Central versions closest to
   what 261 bundles** (not the JetBrains-internal `-intellij-N` variants —
   those aren't on Maven Central).
6. **Add a binary-side equality test:** parse the IDE
   `lib/intellij.libraries.kotlinx.*.jar` manifests + embedded `.version`
   files and assert equality with what the build resolves.
7. **Add a runtime classloader check:** Gradle verification task that runs
   our compiled bytecode against the IDE `lib/*.jar` classpath only, fails
   on `LinkageError` / `NoClassDefFoundError` / `IncompatibleClassChangeError`.
8. **Folder naming for unpacked IDEs:** `build/local-ides/IU-<full-build>-<os>-<arch>/`
   (e.g. `IU-261.24374.151-mac-aarch64`). Full build number, not version.
9. **Keep `deployPluginLocallyTo253`** task as-is. Local-use, hardcoded
   folder; do not touch.
10. **`sinceBuild = "261"`**, consolidated across `ij-plugin/build.gradle.kts`,
    `intellij-downloader/.../CompatibilityFloor.kt`, and
    `PluginCompatibilityFloorTest`.

## Verified facts (do not re-research)

### Bundled libraries in IDEA 261 `lib/`

Repackaged as `intellij.libraries.kotlinx.*.jar`. Versions read from
`META-INF/*.version` files and manifest `Implementation-Version`:

| Artifact | Bundled version | Repackaged path |
|---|---|---|
| `kotlin-stdlib` | `2.3.10-release-465` | `plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar` |
| `kotlin-reflect` | `2.3.10-release-465` | `plugins/Kotlin/kotlinc/lib/kotlin-reflect.jar` |
| `kotlinx-coroutines-core` | **`1.10.2-intellij-1`** | `lib/intellij.libraries.kotlinx.coroutines.core.jar` |
| `kotlinx-coroutines-{debug,guava,slf4j}` | `1.10.2-intellij-1` | same prefix |
| `kotlinx-serialization-core` | **`1.9.0`** (manifest) | `lib/intellij.libraries.kotlinx.serialization.core.jar` |
| `kotlinx-serialization-json` | (bundled with core) | `lib/intellij.libraries.kotlinx.serialization.json.jar` |
| `kotlinx-serialization-{cbor,protobuf}` | (bundled with core) | same prefix |
| `kotlinx-io-core` / `bytestring` | (no manifest version) | `lib/intellij.libraries.kotlinx.io.jar` |
| `kotlinx-html` | `0.12.0` | `lib/intellij.libraries.kotlinx.html.jar` |
| `kotlinx-collections-immutable` | (no version) | `lib/intellij.libraries.kotlinx.collections.immutable.jar` |
| `kotlinx-datetime` | (no version) | `lib/intellij.libraries.kotlinx.datetime.jar` |

JetBrains rule (from
[plugins.jetbrains.com/docs/intellij/using-kotlin.html#coroutinesLibraries](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#coroutinesLibraries)):
"Plugins must always use the bundled library from the target IDE and not
provide their own version."

### Maven Central pins closest to 261-bundled

The `-intellij-N` JetBrains variants are not on Maven Central. We pin to
the closest public version and use the binary-equality test to validate
real compat:

- `kotlinx-coroutines-core: 1.10.2`
- `kotlinx-serialization-core: 1.9.0`
- `kotlinx-serialization-json: 1.9.0`

### Existing dependency drift across `ij-plugin`-linked modules

| Module | coroutines | serialization-json | serialization-core | After commit 1 |
|---|---|---|---|---|
| `:mcp-core` | 1.10.1 | 1.8.1 | 1.8.1 | 1.10.2 / 1.9.0 |
| `:mcp-stdio` | 1.10.1 | 1.8.1 | 1.8.1 | 1.10.2 / 1.9.0 |
| `:mcp-http` | — | 1.8.1 | 1.8.1 | — / 1.9.0 |
| `:execution-storage` | 1.10.1 | 1.8.1 | 1.8.1 | 1.10.2 / 1.9.0 |
| `:mcp-steroid-server` | 1.10.1 | 1.8.1 | 1.8.1 | 1.10.2 / 1.9.0 |
| `:ocr-common` | — | 1.7.3 | — | — / 1.9.0 |
| `:agent-output-filter` | — | 1.8.0 | — | **out of scope** — standalone CLI, not in ij-plugin classpath |

The `ij-plugin` build.gradle.kts already excludes all `org.jetbrains.kotlinx`
group jars from the `implementation` configuration
(`ij-plugin/build.gradle.kts:53-58`), so the plugin .zip continues NOT to
bundle these — they resolve from the IDE classloader at runtime.

### Built plugin.xml depends (verified by unzipping the built .zip)

- `<depends>com.intellij.modules.platform</depends>` — mandatory.
- `<depends optional="true" config-file="mcpServer-integration.xml">com.intellij.mcpServer</depends>` — optional.

The `bundledPlugin("com.intellij.java" / "org.jetbrains.kotlin" /
"com.intellij.mcpServer")` lines in `ij-plugin/build.gradle.kts` are
compile-time-only and do NOT inject runtime `<depends>` into the built
plugin.xml. No action needed there.

### Concrete failure when building against 261 with Kotlin 2.2.20

`./gradlew :ij-plugin:compileKotlin -Pmcp.platform.version=2026.1` with the
current root Kotlin 2.2.20 fails in ~22s with 25 errors:

```
e: …/.intellijPlatform/ides/IU-2026.1/plugins/Kotlin/lib/
   kotlinc.kotlinx-serialization-compiler-plugin.jar!/META-INF/…kotlin_module
   Module was compiled with an incompatible version of Kotlin.
   The binary version of its metadata is 2.4.0, expected version is 2.2.0.
```

Affected jars (all in `plugins/Kotlin/lib/`):

- `kotlinc.kotlinx-serialization-compiler-plugin.jar`
- `kotlinc.lombok-compiler-plugin.jar`
- `kotlinc.noarg-compiler-plugin.jar`
- `kotlinc.parcelize-compiler-plugin.jar`
- `kotlinc.sam-with-receiver-compiler-plugin.jar`
- `kotlinc.scripting-compiler-plugin.jar`

These are kotlinc-internal compiler plugin implementations, not API surface.
Excluding them from the `KotlinCompile.libraries` FileCollection is the
"no Kotlin bump" fix (see Commit 2).

## 8-commit plan

Each commit is independently revertible. Merge gate listed per row.

| # | Commit | Files / details | Merge gate |
|---|---|---|---|
| 1 | **Pin Kotlin-adjacent libs across ij-plugin-linked modules** | `:mcp-core`, `:mcp-stdio`, `:mcp-http`, `:execution-storage`, `:mcp-steroid-server`, `:ocr-common` — bump coroutines `1.10.1`→`1.10.2`, serialization json/core `1.8.1`/`1.7.3`→`1.9.0`. Keep existing `exclude(group = "org.jetbrains.kotlinx")` in `ij-plugin/build.gradle.kts:53-58`. | All `:test` of touched modules green; `verifyBundledLibraries` shows plugin .zip still does not ship `kotlinx-*` jars. |
| 2 | **Kotlinc classpath filter** | In `ij-plugin/build.gradle.kts`, add `tasks.withType<KotlinCompile>().configureEach { doFirst { libraries.setFrom(libraries.files.filterNot { it.name in blockedKotlincPluginJars }) } }` where `blockedKotlincPluginJars` is the set of 6 `kotlinc.*-compiler-plugin.jar` filenames listed above. | `./gradlew :ij-plugin:compileKotlin -Pmcp.platform.version=2026.1` exits 0. |
| 3 | **`buildSrc/.../IdeCompatibilityMatrix.kt` + test** | Single source of truth: `buildTarget = IdeTarget("261","2026.1")`; `verifierTargets = listOf(IdeTarget("261","2026.1"), IdeTarget("262","262-EAP-SNAPSHOT"))`. `IdeCompatibilityMatrixTest` asserts no 253, no `LATEST-*`, each EAP entry matches `^\d{3}-EAP-SNAPSHOT$`. | `:buildSrc:test` green. |
| 4 | **Route IDE artifacts through `intellij-downloader`** | New `prepareLocalIdes` Gradle task: reads the matrix, downloads via `IdeDistribution`, unpacks via `IdeUnpacker` into `build/local-ides/IU-<full-build>-<os>-<arch>/`. `ij-plugin/build.gradle.kts:68` switches `intellijIdeaUltimate(targetIdeVersion)` → `local(matrix.buildTarget.localRoot)`. `pluginVerification.ides { }` block becomes `verifierTargets.forEach { local(it.localRoot) }`. All `useInstaller = true` lines deleted. | `./gradlew :ij-plugin:buildPlugin :ij-plugin:verifyPlugin` resolves IDEs locally; offline-after-first-download. |
| 5 | **Move sinceBuild + managed-backend floor to 261** | `ij-plugin/build.gradle.kts:192` (`"252"`→`"261"`), `intellij-downloader/.../CompatibilityFloor.kt:18` (`"252"`→`"261"`), `PluginCompatibilityFloorTest.kt:25,40` floor cases rebased to 261. | `:ij-plugin:test` green; `PluginCompatibilityFloorTest` enforces the new pair. |
| 6 | **Runtime classloader check** | New `VerifyBundledKotlinxRuntimeTask` in `buildSrc/` (sibling to existing `VerifyBundledKotlinCompatibilityTask`). Launches JVM with `-cp <IDE lib/*.jar>:<ij-plugin runtime jars>`, runs `main()` exercising `Json.encodeToString`, `runBlocking { delay(1) }`, kotlinx-io call sites. Fails on `LinkageError` / `NoClassDefFoundError` / `IncompatibleClassChangeError`. Hooked into `:ij-plugin:check` and `verifyPlugin`. | Task succeeds against the local 261 + 262 IDEs from commit 4. |
| 7 | **Binary-side version equality test** | New `:ij-plugin:test` test reads `<IDE root>/lib/intellij.libraries.kotlinx.coroutines.core.jar` `META-INF/kotlinx_coroutines_core.version` and `intellij.libraries.kotlinx.serialization.core.jar` manifest `Implementation-Version`, compares to resolved versions from the ij-plugin runtime classpath. **Coroutines comparison is normalized** — strip the JetBrains-fork suffix (`1.10.2-intellij-1` → `1.10.2`) before equality, since the public Maven artifact lacks the patch suffix. **Serialization comparison is strict** — manifests carry the public version verbatim (`1.9.0`). Fails if the IDE bumps the base (1.10.2 → 1.10.3) without a matching pin update. | Test green today; fails on real upstream bumps, not on the JetBrains-internal suffix that's structurally always present. |
| 8 | **Cleanup** | Delete `build plugin with IntelliJ 2025_3` from `PluginBuildCompatibilityTest.kt`; delete `verify plugin against IntelliJ 2025_3` from `PluginVerificationTest.kt`; remove obsolete `KOTLIN_2_4_PATCHES` / `SNAPSHOT_262_PATCHES` / IPGP 2.14.0 bump patches (made redundant by commit 2). Sweep `2025.3` / `"253"` / `253\.` for stale refs. **Keep `deployPluginLocallyTo253` task as-is.** | All test suites green. Repo grep for `253` returns only the deploy task name and its CLAUDE.md reference. |

## Resolved during round-3 quorum

**`262-EAP-SNAPSHOT` is a matrix-only label, not a Maven coord.** Gemini
round-3 validation confirmed that the IntelliJ Platform Gradle Plugin
2.13.1's `nightly()` repo only resolves the bare `262-SNAPSHOT` shape
from JetBrains Maven snapshots — `262-EAP-SNAPSHOT` does not exist
there. Our plan routes around that: `intellij-downloader` resolves the
binary via the **JetBrains products API** (CDN), where the per-major
EAP channel selector is the natural representation. The
`262-EAP-SNAPSHOT` matrix label is therefore the user-facing tag; the
downloader maps it to:

```
GET https://data.services.jetbrains.com/products?code=IIU&release.type=eap
→ pick the latest release whose build starts with "262."
→ download URL is the products-API "link" field
```

The `IdeCompatibilityMatrixTest` accepts both shapes
(`^\d{3}-EAP-SNAPSHOT$` for moving-within-major or `^\d{3}\.\d+\.\d+$`
for an exact pin) so a future switch to a fully-pinned build doesn't
break the matrix contract.

## Risks

- **EAP API drift.** 262 has at minimum the known
  `StatusBarEx.getBackgroundProcessModels()` `Pair`-direction issue
  (documented in `PluginRuntimeCompatibilityTest`). `verifyPlugin`
  against 262 surfaces all such issues.
- **`1.10.2` vs `1.10.2-intellij-1`.** Same public API surface;
  JetBrains' patch set is internal. Risk is theoretical — the runtime
  check (commit 6) and binary-equality test (commit 7) gate it.
  Commit 7's coroutines comparison strips the `-intellij-N` suffix
  before equality (the suffix is structurally always present in
  JetBrains-forked artifacts; a strict equality would fail day one
  against a public-Maven pin).
- **kotlinx-io is the most volatile dependency** across IDE versions
  (gemini round-3 callout). Commit 6's runtime check must exercise
  every kotlinx-io call site we use, not just one smoke path. The
  current repo does not directly depend on kotlinx-io; the IDE
  bundles it as part of ktor/coroutines support — verify no transitive
  appearance in `:ij-plugin` runtime classpath.
- **Plugin .zip size.** Should not change — commit 1 only bumps
  versions of jars that are excluded from the plugin .zip via the
  existing `implementation` exclude block.

## What stays unchanged

- Root Kotlin (2.2.20).
- `deployPluginLocallyTo253` task.
- IntelliJ Platform Gradle Plugin version (2.13.1) — no bump.
- The plugin .zip continues NOT to bundle `kotlinx-*` jars.
- `<depends>com.intellij.modules.platform</depends>` and the optional
  `mcpServer` depend in `plugin.xml`.

## Implementation order

Strict sequential — each commit's merge gate depends on prior commits:

```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8
```

Each commit message uses the existing repo style (`<scope>: <action>` —
see `git log --oneline | head`). Author email
`eugene.petrenko@jetbrains.com`. No AI co-author.

## Gotcha: an IC release URL can serve an Ultimate binary

When JetBrains has not shipped a Community build for a version, the products-API feed still lists an
`idea-<v>.tar.gz` URL that returns **HTTP 200 with the same Content-Length as the IU binary** (while the
correctly-named `ideaIC-<v>-*` returns 404). The resolver defends with `IdeProduct.acceptsDownloadFilename`
(the URL filename must contain a Community token, e.g. `ideaIC-`/`IC-`) and `LocalIdeProvisioner` asserts
the unpacked `product-info.json` `productCode` matches the requested product. Never relax either check —
together they stop a silent IU-for-IC swap.

# Installer — version.json-driven, generated self-contained installer

Author: Eugene Petrenko · Status: implementation-ready design

The `~/.mcp-steroid/` install-root layout and the wrapper/launcher mechanics
are reused from the prior `docs/devrig-deployment-spec.md`. **`devrig upgrade`
and any release signing are OUT OF SCOPE for this spec** — it covers install
plus the generated install scripts only.

---

## 1. Goal, scope, and the platform × script × JDK matrix

### Goal

Two **generated**, self-contained install scripts (`install.sh`, `install.ps1`)
fully describe how to install devrig + a matching JDK 25 on every supported
platform with **zero runtime manifest parsing** and **zero network calls beyond
the URLs baked into the script**. The script is dumb; the complexity lives in a
Kotlin generator and in the daily coordinate-resolution job. The committed
coordinate files are the single declarative URL source; the generated script is
the published installation artifact.

### Scope

- **In:** the two committed coordinate sources (`jdk-coordinates.json`,
  `devrig-coordinates.json`); the generator (`:installer-gen` Gradle module +
  `generateInstaller` task) and its two consumers (website Makefile,
  `:test-integration`); the two generated scripts; the `bin/` self-registration
  change in devrig; the daily coordinate-refresh GH Action; Docker integration
  tests of the generated scripts; removal of the committed static scripts.
- **Out:** `devrig upgrade` (signed-manifest self-update — deferred, tracked in
  TASKS); release signing / a signed manifest; Hugo/site authoring.

### Platform × script × JDK matrix

| platform        | `<os>-<cpu>` key | install script | JDK vendor / archive          | `format` |
|-----------------|------------------|----------------|-------------------------------|----------|
| macos-arm64     | `macos-arm64`    | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| linux-arm64     | `linux-arm64`    | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| linux-x64       | `linux-x64`      | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| windows-x64     | `windows-x64`    | `install.ps1`  | Amazon Corretto 25            | `zip`    |
| windows-arm64   | `windows-arm64`  | `install.ps1`  | **Azul Zulu 25** (Microsoft fallback) | `zip` |

- macOS and Linux use the POSIX `install.sh`; Windows uses `install.ps1`. Five
  platforms total.
- **Canonical key form is `<os>-<cpu>`** with `os ∈ {macos, linux, windows}`
  and `cpu ∈ {arm64, x64}`. The scripts' `uname`/`OSArchitecture` detection
  normalizes to these tokens (`Darwin→macos`, `aarch64|arm64→arm64`,
  `x86_64|amd64|X64→x64`). The generator is the single place that knows the
  mapping.
- **windows-arm64:** Corretto 25 ships **no** Windows/aarch64 build (verified
  404), so windows-arm64 uses a **second vendor**: primary **Azul Zulu 25**
  (`api.azul.com/metadata/v1` gives `download_url` + `sha256_hash` + version in
  one JSON call; archive dir name == archive basename), fallback **Microsoft
  Build of OpenJDK 25** (`aka.ms` redirectors + `.sha256sum.txt` sidecars). This
  is a real native arm64 JDK — there is no x64-under-emulation path.
- **JDK checksums use sha256 everywhere** — every vendor publishes sha256, and
  `shasum -a 256` / `Get-FileHash SHA256` are universal. A 12-char prefix is
  used for cache folder naming.

---

## 2. `version.json` scope: minimal, unchanged

The published `version.json` is **not** extended. It stays exactly as today —

```json
{"version-base": "<VERSION>"}
```

— produced by the website Makefile and consumed only for `version-base` by
`UpdateChecker.kt`, `DevrigUpdateChecker.kt`, and `BackendVersionSkew.kt` (all
with `ignoreUnknownKeys = true`). No installer block is published.

Rationale:

- Generated scripts bake every URL+sha in and never read a manifest at runtime.
- devrig does not re-resolve or download at runtime — the install script owns
  downloads; the `bin/` launcher just sets `JAVA_HOME` to the already-unpacked
  JDK. No runtime consumer needs an installer block.
- `devrig upgrade` — the only feature that would read a published installer
  manifest — is out of scope. A published block would be equally stale unless
  the script read it live (which would defeat the baked-in design), so it adds
  no protection. Mitigations live in the pipeline (daily byte-verify), not in a
  published manifest.

**The URL source is the committed coordinate files**
(`website/installer/jdk-coordinates.json` + `website/installer/devrig-coordinates.json`).
They are the single human-/job-edited declarative source of all URLs+shas;
`generateInstaller` consumes them and renders the self-contained scripts, which
are the published installation artifact. The coordinate files are the source;
the script is the published carrier. The IntelliJ plugin ships **inside** the
devrig artifact (devrig dist bundles `ij-plugin.zip`; `DevrigRoot` walks
`lib/`+`ij-plugin.zip`), so there is no separate plugin coordinate. The existing
`version-base` + `updatePlugins.xml` remains the marketplace plugin-update
channel.

---

## 3. Coordinate model + how it becomes scripts

The generator merges two committed coordinate files plus `project.version` and
renders the two scripts. Both files share the per-platform `<os>-<cpu>` key
form and carry vendor-/release-authoritative sha256 values.

### `website/installer/jdk-coordinates.json` — edited by the daily GH Action

The **only** file the daily JDK job edits. Committed; not served to users.

```jsonc
{
  "schema": 1,
  "platforms": {
    "linux-x64":    { "vendor": "corretto", "version": "25.0.3.9.1",
                      "url": "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-linux-x64.tar.gz",
                      "sha256": "…", "format": "tar.gz",
                      "javaHomeSubpath": "amazon-corretto-25.0.3.9.1-linux-x64" },
    "linux-arm64":  { "vendor": "corretto", "version": "25.0.3.9.1", "url": "…", "sha256": "…",
                      "format": "tar.gz", "javaHomeSubpath": "amazon-corretto-25.0.3.9.1-linux-aarch64" },
    "macos-arm64":  { "vendor": "corretto", "version": "25.0.3.9.1", "url": "…", "sha256": "…",
                      "format": "tar.gz", "javaHomeSubpath": "amazon-corretto-25.jdk/Contents/Home" },
    "windows-x64":  { "vendor": "corretto", "version": "25.0.3.9.1", "url": "…", "sha256": "…",
                      "format": "zip", "javaHomeSubpath": "jdk25.0.3_9" },
    "windows-arm64":{ "vendor": "azul-zulu", "version": "25.0.3",
                      "url": "https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip",
                      "sha256": "60b6b1faa1a93fea8e64b09f2b9ab136a86b02428f004f8378cfb04cd818a0d4",
                      "format": "zip", "javaHomeSubpath": "zulu25.34.17-ca-jdk25.0.3-win_aarch64" }
  }
}
```

Per-platform JDK fields: `vendor` (`corretto` | `azul-zulu` | `microsoft`),
`version`, `url`, `sha256` (lowercase hex), `format` (`zip` | `tar.gz` |
`tar.xz`), `javaHomeSubpath` (dir inside the verbatim JDK tree to export as
`JAVA_HOME`; `""` = unpack root — covers macOS `Contents/Home`, versioned
Linux/Win dirs uniformly).

The sha256 values are vendor-authoritative — read from each vendor's published
checksum, never by re-hashing a downloaded blob. Corretto:
`GET https://corretto.aws/downloads/latest_sha256/<asset>` (bare 64-hex). Azul:
the `sha256_hash` field in `api.azul.com/metadata/v1` package detail. Microsoft
(fallback): the `.sha256sum.txt` sidecar. `javaHomeSubpath` is read from the
real archive top-level directory for both vendors by the daily job (Azul's is
the filename minus `.zip`; Microsoft's `jdk-25.0.x+N` cannot be string-derived,
so it is inspected from the archive).

### `website/installer/devrig-coordinates.json` — written at devrig release time

Symmetric with `jdk-coordinates.json`, written by the devrig release task/CI at
**release time** (not at generation time). Per platform: `url`, `sha256`,
`size`, `format`, `binSubpath` (launcher path inside the verbatim devrig tree —
`bin/devrig` POSIX, `bin/devrig.bat` Windows). The devrig install dist zip is
universal today; this file lists it under every platform, differing only in
`binSubpath`.

Because the devrig sha is recorded at release time from the published artifact,
`generateInstaller` is a **pure data-merge** (devrig-coords + jdk-coords +
`project.version`) with **no devrig/plugin build dependency**: the Hugo site
deploy and the daily JDK PR can regenerate the scripts without building the
IntelliJ plugin, and the devrig sha is always the published-artifact sha (never
a non-reproducible local re-hash). The installDist root name is build-enforced
via `distributions { main { distributionBaseName = "devrig-$version" } }` so
`binSubpath` matches without runtime assertion.

### How the coordinate files become scripts

`generateInstaller` reads both coordinate files, merges them with
`project.version`, and renders `install.sh` + `install.ps1` with every
`url`/`sha256`/`size`/`format`/`binSubpath`/`javaHomeSubpath` for all 5
platforms **baked in** as shell `case` arms / PowerShell hashtable literals.
No `version.json` installer block is involved.

---

## 4. The generator: `:installer-gen` module + `generateInstaller` task

A small new Kotlin/JVM Gradle module `installer-gen` (sibling of
`jdk-downloader`), registered in `settings.gradle.kts`. It is its own module
because it must be invokable from **both** the website Makefile (not a Gradle
module → shells out to `./gradlew`) **and** `:test-integration` (a Gradle
dependency). A standalone module gives both a single entry point and keeps
script-templating logic out of `npx-kt`'s already-large build script. It is a
pure data-merge module — it depends on neither the devrig build nor the plugin
build (it reads `devrig-coordinates.json` for devrig data).

```
:installer-gen
  src/main/kotlin/.../InstallerGenerator.kt      // coordinate-merge model + script templater
  src/main/resources/templates/install.sh.tmpl   // POSIX template with @@PLACEHOLDERS@@
  src/main/resources/templates/install.ps1.tmpl  // PowerShell template
  src/main/resources/templates/launcher.sh.tmpl  // bin/devrig POSIX launcher template
  src/main/resources/templates/launcher.cmd.tmpl // bin/devrig.cmd
  src/main/resources/templates/launcher.ps1.tmpl // bin/devrig.ps1
  build.gradle.kts
```

**Task `generateInstaller`** (group `installer`):

- Inputs: `jdk-coordinates.json` + `devrig-coordinates.json` (both under
  `website/installer/`), `project.version`, the template resources.
- Outputs (into `-PoutDir` or default `build/installer/`):
  - `install.sh`, `install.ps1` — **data baked in** (no manifest fetch at
    runtime; the per-platform coordinates for all 5 platforms are emitted as
    shell `case` arms / PowerShell hashtable literals).
  - The launcher templates are not emitted as standalone files — they are
    embedded as heredoc/here-string bodies inside the install scripts (the
    install script writes `bin/devrig` at install time, section 5/6), with the
    same `@@PLACEHOLDERS@@` resolved per platform.
- Determinism: stable key order, `\n` line endings (the `.ps1` is `\n`-
  terminated; PowerShell tolerates LF), trailing newline — so any regenerated
  diff is minimal and reviewable.

Invocation contract (both consumers use the same flags):

```
./gradlew :installer-gen:generateInstaller \
    -PoutDir=<dir> \
    -PjdkCoordinatesFile=<path-to-jdk-coordinates.json> \
    -PdevrigCoordinatesFile=<path-to-devrig-coordinates.json>
```

### Website Makefile wiring

Add the script generation, landing both scripts in `website/static/` (which is
gitignored for these files, section 10). The `version.json` line stays as today
(the minimal `{"version-base": …}` echo):

```make
update-config: ensure-public-repo copy-root-files
	@echo "Generating install scripts (install.sh + install.ps1) for $(VERSION)"
	( cd .. && ./gradlew :installer-gen:generateInstaller \
	    -PoutDir=website/static \
	    -PjdkCoordinatesFile=website/installer/jdk-coordinates.json \
	    -PdevrigCoordinatesFile=website/installer/devrig-coordinates.json )
	@test -s static/install.sh  || { echo "ERROR: install.sh not generated"  >&2; exit 1; }
	@test -s static/install.ps1 || { echo "ERROR: install.ps1 not generated" >&2; exit 1; }
	# … existing version.json echo + updatePlugins.xml generation continue unchanged …
```

Hugo copies `static/*` to the site root, so the URLs stay
`https://mcp-steroid.jonnyzzz.com/{install.sh,install.ps1}` — no change to how
the website is generated, only new inputs in `static/`.

---

## 5. Generated `install.sh` + `install.ps1` behavior

Both scripts have the per-platform table baked in and perform **no live
discovery** — no GitHub `releases/latest` call, no `corretto.aws/latest_sha256`
call. They keep the hardened mechanics: lock-free atomic-rename install with
loser-cleanup, `.tmp.$$`/`.tmp.$PID` staging + stale-sweep, all-stderr
`log`/`Write-Log`, the `Get-ProfileRelativePath` ASCII guard, the TLS-1.2
opt-in, `throw`-not-`exit`, and a `main()` wrapper.

### Algorithm (identical for both, mechanics differ only in language)

```
1. Detect (os, cpu); DEVRIG_OS / DEVRIG_CPU override. Normalize to <os>-<cpu>.
2. key = "<os>-<cpu>". Select the BAKED-IN entry for key.
   If no entry → fail "platform <key> is not supported by this build".
3. Resolve install root: POSIX ~/.mcp-steroid; ps1 resolves home via
   $env:USERPROFILE → $HOME → [Environment]::GetFolderPath('UserProfile').
   mkdir -p <home>/{bin,binaries}
4. For each artifact in (devrig, jdk):
     sha12  = first 12 chars of <sha256>
     name   = "<artifact>-<os>-<cpu>-<version>-<sha12>"
     target = binaries/<name>/
     if target exists  → log "already installed: <name>"; continue   (idempotent)
     download <url>            → .tmp.$$.<artifact>.<ext>   (into binaries/, same FS → atomic mv)
     verify SHA-256 == <sha256>  (mandatory; mismatch → fail)
     unpack VERBATIM (no --strip) by <format>:
         zip    → unzip / Expand-Archive
         tar.gz → tar -xzf
         tar.xz → tar -xJf
       into .tmp.$$.<artifact>.unpack/
     atomic-rename .tmp → target    (lock-free; loser cleans up its nested dir)
     rm the downloaded archive
5. devrig launcher = target_devrig/<binSubpath>      (chmod +x on POSIX)
6. JAVA_HOME:
     if $DEVRIG_JAVA_HOME set → use it (skip JDK entirely; honored by launcher)
     else → target_jdk/<javaHomeSubpath>   ("" = unpack root)
7. Write <home>/bin/<launcher> (POSIX: devrig; Windows: devrig.ps1 + devrig.cmd),
   embedding launcher + JAVA_HOME paths RELATIVE to $HOME / %USERPROFILE%,
   atomically (write .tmp → mv -f). Launcher exports JAVA_HOME then exec's devrig,
   and writes only stderr before the exec (the MCP stdio channel owns stdout).
8. PATH hint to stderr. Final hint: run `devrig install` for the agent wizard.
```

Key properties:

- **Content-addressed, monotonic cache.** The folder name carries `<version>`
  and a 12-char sha (`…-<os>-<cpu>-<version>-<sha12>`). The install scripts
  never delete a cache dir — `binaries/` grows by design (see section 6).
- **windows-arm64 gets a real arm64 JDK** (Azul, Microsoft fallback) — no
  emulation branch.
- **Lock-free install.** Atomic-rename with loser-cleanup; the stale-`.tmp.*`
  sweep removes an entry only when its pid is dead OR its mtime exceeds a
  threshold, never the current pid's.

All output goes to **stderr** (stdout reserved for the JVM/MCP channel). Both
scripts stay wrapped in `main()` / a final invocation so a truncated `curl | sh`
cannot execute a partial body.

---

## 6. `~/.mcp-steroid` layout + devrig `bin/` self-registration

### Layout (content-addressed, version segment, no GC by the script)

```
~/.mcp-steroid/                       ← fixed, not configurable (HomePaths.kt)
├── bin/
│   ├── devrig                        ← POSIX launcher (sets JAVA_HOME, exec)
│   ├── devrig.ps1, devrig.cmd        ← Windows launchers
├── binaries/
│   ├── devrig-<os>-<cpu>-<version>-<sha12>/   ← verbatim devrig tree
│   ├── jdk-<os>-<cpu>-<version>-<sha12>/      ← verbatim JDK tree
│   ├── .tmp.<pid>.…                            ← per-process staging (swept)
├── backends/, caches/, downloads/, logs/, markers/, state/   ← existing (HomePaths.kt)
```

No `version.json` / signatures / allowed_signers are copied into
`~/.mcp-steroid` by the install **script** — the script needs no manifest at
runtime.

**No cleanup / no auto-GC.** `binaries/` grows monotonically by design — each
new devrig/JDK version adds roughly 200–400 MB; this is an accepted, documented
trade-off. There is no automatic garbage collection (any keep-set logic would
depend on an on-disk manifest the design deliberately omits). A manual opt-in
`devrig prune` may be added later, never automatic.

### devrig `bin/` self-registration

devrig ensures its `bin/` entry exists and points at its own downloaded
location. The existing pieces do most of the work:

- `DevrigRoot.path` (`DevrigRoot.kt`) gives devrig its own installDist root by
  walking up from `codeSource` to the dir containing `lib/` + `ij-plugin.zip`
  — i.e. `binaries/devrig-…-<sha12>/<top>/`.
- `selfMcpCommand` / launcher resolution (`InstallCommand.kt`) already prefers
  `<DevrigRoot.path>/bin/<name>` and falls back to
  `ProcessHandle.current().info().command()`.

**New code (one file): `npx-kt/.../devrig/BinLauncher.kt`**, called early in
every `devrig mcp` / `devrig install` startup:

```kotlin
fun ensureBinLauncher(home: HomePaths) {
  val binDir   = home.home.resolve("bin")
  val launcherName = if (isWindows) "devrig.ps1" else "devrig"   // + devrig.cmd on Windows
  val target   = binDir.resolve(launcherName)
  val ownRoot  = DevrigRoot.path                       // .../binaries/devrig-…-<sha12>/<top>
  val ownBin   = ownRoot.resolve(if (isWindows) "bin/devrig.bat" else "bin/devrig")
  val ownJava  = Path.of(System.getProperty("java.home"))   // the JDK that launched us
  val desired  = renderLauncher(ownBin, ownJava)       // one canonical render, relative to home
  if (!target.exists() || normalize(target.readText()) != normalize(desired)) {
      writeAtomically(binDir, target, desired)         // .tmp → mv -f; chmod +x on POSIX
      System.err.println("[mcp-steroid] (re)wrote $target -> $ownBin")
  }
}
```

This makes devrig **self-healing**: if `bin/devrig` is missing or points at a
stale tree, the next launch rewrites it to devrig's own current location and the
JDK it is currently running under (so `JAVA_HOME` always matches a present JDK).
There is **one canonical launcher renderer**, shared with the install-script
generator (section 4) so the two never drift; it emits paths **relative to
`$HOME` / `%USERPROFILE%`**. `BinLauncher` compares **normalized** content (not
raw `readText`) so it rewrites only on a real change — never every launch.
`HomePaths.kt` gains a `val binDir get() = home.resolve("bin")` accessor;
`resolveHomePathsOrDie`'s `mkdirsAll` adds `binDir`.

---

## 7. Daily GH Action — `installer-jdk-refresh.yml`

```yaml
name: installer-jdk-refresh
on:
  schedule: [{ cron: "0 6 * * *" }]   # 06:00 UTC daily
  workflow_dispatch:
```

A single Kotlin resolver, `:installer-gen:resolveJdkCoordinates`, keeps logic
testable and out of YAML:

1. **Resolve current coordinates** for all 5 platforms:
   - Corretto (4 platforms): GitHub API
     `repos/corretto/corretto-25/releases/latest` → `tag_name` (the `25.0.x.y.z`
     version) → build the `corretto.aws/downloads/resources/<ver>/<asset>` URL →
     fetch `latest_sha256/<asset>` for the checksum → known per-platform
     `javaHomeSubpath`.
   - windows-arm64 (Azul primary): `GET api.azul.com/metadata/v1/zulu/packages/?
     java_version=25&os=windows&arch=aarch64&archive_type=zip&java_package_type=jdk&
     release_status=ga&latest=true` → `download_url` + `package_uuid` → detail
     call for `sha256_hash`. On Azul failure, **Microsoft fallback**
     (`aka.ms/download-jdk/…`, `.sha256sum.txt`). Both vendors are resolved
     **daily** (Azul active, Microsoft standby recorded) so the Microsoft path
     stays warm.
2. **Byte-verify.** Download ≥1 canonical JDK artifact and verify its bytes
   against the recorded sha256 (**fail the PR on mismatch**). Inspect the real
   archive top-level dir for `javaHomeSubpath` for **both** vendors (not
   string-derived).
3. **Diff** the resolved set against `website/installer/jdk-coordinates.json`.
   If identical → exit 0 (no PR).
4. If changed → write the new `jdk-coordinates.json`, `git checkout -b
   installer/jdk-refresh-<date>`, commit ("installer: refresh JDK coordinates —
   Corretto 25.0.x → 25.0.y; …"), `gh pr create` against `main`.
5. The PR **only edits `website/installer/jdk-coordinates.json`**. When merged,
   the path filter on the existing `github-pages.yml`
   (`on.push.paths: ['website/**', 'VERSION']`) already matches
   (`website/installer/**` is under `website/**`) → the **existing** site build
   runs `make update-config`, regenerating `install.sh` + `install.ps1` from the
   new coordinates and deploying. The website generation itself is unchanged —
   the daily job edits one data file; the path-trigger does the rest. (If
   `website/installer/**` is ever not covered, add it to `github-pages.yml`'s
   `paths` list — a one-line change.)

A weekly URL-liveness check should also HEAD all 5 JDK URLs + the devrig URL.

---

## 8. Integration tests

### Module: `:test-integration`, harness `InstallerBootstrapTest.kt`

Two Docker drivers, both running **generated** scripts. `:test-integration:test`
declares `dependsOn(generateInstaller)` and consumes the task's output dir via a
**system property** — it never re-invokes `./gradlew` (no nested Gradle). The
output dir is mounted read-only into the container, proving the *generated*
artifact, not a committed one.

| Driver | Image | Runs | Proves |
|---|---|---|---|
| `test-install-sh.sh` | `ubuntu:24.04` | `sh /gen/install.sh` | POSIX path on Linux, no host JDK, space-in-HOME, idempotent re-run, no `.tmp.*` leftovers, concurrent-install (two `install.sh` in parallel in one container) |
| `test-install-ps1.ps1` | `mcr.microsoft.com/powershell:latest` | `pwsh /gen/install.ps1` | PowerShell logic (detection, hashtable lookup, `Get-FileHash` verify, atomic-rename, ASCII shim guard) cross-platform, including the **windows-arm64 / Azul** entry |

The `test-install-sh.sh` driver's assertions match the folder shape
`devrig-linux-<cpu>-<version>-<sha12>` / `jdk-linux-…`.

**Kotlin harness** (`InstallerBootstrapTest`, JUnit, in `:test-integration`):

- The generated scripts are produced by the `generateInstaller` task dependency
  and located via the system property.
- Each test `docker run --rm -v <gen>:/gen:ro <image> <driver>`, captures
  stdout, asserts the **success marker** is present.
- **Success markers:** `INSTALL_SH_E2E_OK` and `INSTALL_PS1_E2E_OK` — the ps1
  driver emits its marker after asserting: launcher present, `binaries/
  devrig-windows-…` + `jdk-windows-…` trees, second run prints exactly two
  "already installed", zero `.tmp.*` leftovers. A test asserts the generated
  launcher writes empty stdout before exec.
- **windows-arm64 (Azul) is exercised:** the pwsh-on-Linux driver runs the
  `windows-arm64` entry (`DEVRIG_OS=windows DEVRIG_CPU=arm64`): download →
  sha256-verify → `Expand-Archive` → resolve `javaHomeSubpath`. `.exe`/registry
  semantics are excluded.
- **pwsh-on-Linux runs Windows logic, not Windows semantics.** It cannot
  exercise `.exe`/backslash/registry behavior; Windows-specific bits
  (`devrig.cmd`, `java.exe` lookup) are parameterized so the Linux run
  substitutes `java`. True Windows-native verification stays a manual/host
  concern.

### Hermetic per-PR CI

Routine `ciIntegrationTests` uses `file://` fixtures (a tiny pinned fake-archive
with a known sha256) — no CDN, no detection-and-skip. Real-CDN download+verify
runs only in the daily/weekly job. The installer tests live inside
`:test-integration:test` (after the existing strict-ordered chain
`:test-helper:test` → `:ij-plugin:integrationTest` → `:test-integration:test`,
gated by the same Docker `onlyIf`), so they ride the existing aggregator entry —
no new aggregator task. They run **sequentially** (each spins a container; the
"never run Docker tests in parallel" rule applies) — enforced with
`maxParallelForks = 1` on the installer test tag.

---

## 9. Cherry-pick plan (from `0101-installer-docker`)

| Commit | Take? | Action |
|---|---|---|
| `c615ac7a` website: one-command bootstrap installers (`install.sh`/`.ps1`) | **base** | Cherry-pick as the script skeleton, then refactor: strip GitHub-release + `corretto.aws/latest_sha256` live resolution; replace with baked-in per-platform `case`/hashtable; rename folder scheme to `…-<version>-<sha12>`; standardize on sha256 naming. |
| `0c3a9259` harden bootstrap installers per review | **yes** | Keep all hardening: lock-free atomic-rename install, stale-`.tmp` sweep, `Get-ProfileRelativePath` ASCII guard, TLS-1.2 opt-in, `throw`-not-`exit`, `main()` wrapper. |
| `067044b1` WIP: installer JDK auto-download + `install-tests/` scaffolding | **partial** | Take the JDK download/verify/unpack mechanics and the `test-install-sh.sh` driver. Drop the live `latest_sha256` resolution (now baked) and the windows-arm64 x64-emulation branch (now real Azul arm64). Finish the missing `test-install-ps1.ps1`. |
| `0135699e` / `0dbcec8a` `devrig install --check` (#86) | **yes (independent)** | Unrelated to manifest choice; cherry-pick as-is — it improves the wizard. |
| `32750ec0` TODO: PS5.1 smoke-test follow-up | **note only** | Folds into section 8's "Windows-native is manual/host" caveat. |

After cherry-picks, the refactor is: (a) introduce `:installer-gen` +
templates; (b) move the per-platform table out of the scripts into template
placeholders; (c) add `jdk-coordinates.json` + `devrig-coordinates.json`;
(d) repoint the Makefile and tests at generated output; (e) delete the committed
static scripts (section 10).

---

## 10. De-binary-fication

1. **Remove committed scripts** from `main`:
   `git rm website/static/install.sh website/static/install.ps1`. They were the
   only "binary-like" generated artifacts checked in.
2. **`.gitignore`** (under `website/`):
   ```gitignore
   # generated by :installer-gen:generateInstaller (do not commit)
   static/install.sh
   static/install.ps1
   ```
3. **Coordinate files stay committed** — `jdk-coordinates.json` (daily-PR
   target) and `devrig-coordinates.json` (release-time target) are input data,
   not generated artifacts.
4. **Dev (`deployNpx`)**: dev mode pre-populates the cache and writes `bin/`
   launchers directly via `ensureBinLauncher` (section 6) + the dev-mode cache
   copy — it does not need the generated install scripts. Optionally `deployNpx`
   may run `:installer-gen:generateInstaller -PoutDir=build/installer` for local
   inspection, but that output is gitignored and not required for dev.
5. **CI**: the site build (`github-pages.yml` → `make update-config`) generates
   and deploys the scripts; `:test-integration` generates them via the task
   dependency for its Docker runs. Neither reads a committed copy. The single
   source of truth for "what gets installed" is therefore `jdk-coordinates.json`
   + `devrig-coordinates.json` + the generator — all reproducible, none binary,
   none committed-as-output.

The generated install scripts are produced only by the website build and by
integration tests; the templates live in `:installer-gen`; the generated outputs
are gitignored.

---

## 11. Install-time trust model + risks

### Trust model

Install-time trust = TLS to the website (`install.sh` / `install.ps1`) + TLS to
each JDK vendor. `version.json` is **unsigned**. Integrity is provided by full
sha256 verification of every downloaded artifact against the value baked into the
script. A signed manifest is deferred together with `devrig upgrade` (out of
scope).

### Risks / open questions

1. **Azul / Microsoft URL stability for windows-arm64.** Azul's
   `cdn.azul.com/zulu/bin/<file>.zip` URLs are versioned and stable once
   published; the daily job re-resolves via the metadata API so a vanished pin
   is caught within a day. The weekly URL-liveness check should HEAD all 5 JDK
   URLs + the devrig URL.
2. **Corretto `javaHomeSubpath` versioning.** Linux/Windows Corretto dir names
   embed the full `25.0.x.y.z`, so `javaHomeSubpath` changes on every patch —
   but it is regenerated from `jdk-coordinates.json`, so the daily job keeps it
   correct. macOS (`amazon-corretto-25.jdk/Contents/Home`) is stable.
3. **Microsoft fallback `javaHomeSubpath` (`jdk-25.0.x+N`)** cannot be derived
   from the filename (unlike Azul). The daily resolver fetches and inspects the
   archive root in the Microsoft branch.
4. **One universal devrig zip across 5 platforms.** Today's build ships a single
   dist zip; this design lists it under all platforms with differing
   `binSubpath`. If per-platform devrig zips ever appear, the coordinate model
   already supports it (per-platform `devrig.url`) with no shape change.
5. **Vendor removes an old JDK (404).** The curl-fresh published script always
   points at the current, daily-verified JDK (fresh installs OK); already-
   installed users keep the content-addressed JDK locally (unaffected); only a
   stale locally-saved script 404s — document "use the curl one-liner, don't
   cache install.sh". The daily byte-verify job fails the PR on removal/sha-drift,
   so the pipeline reacts within a day.
6. **pwsh-on-Linux ≠ Windows.** Acknowledged (section 8): logic-only coverage;
   Windows-native verification stays manual.

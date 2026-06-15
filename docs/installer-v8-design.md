# Installer v8 — version.json-driven, generated self-contained installer

Author: Eugene Petrenko · Status: design for quorum review · Supersedes:
`docs/devrig-deployment-spec.md` v7 **manifest choice** (Properties → version.json)

> **What changes vs v7.** v7 published a `version.properties` manifest and the
> wrapper parsed it at runtime in three languages (POSIX awk, PowerShell,
> `java.util.Properties`). v8 keeps a single JSON manifest (`version.json`,
> the file the website already serves) and moves *all* parsing to **build
> time**: a Kotlin generator reads `version.json` and emits `install.sh` /
> `install.ps1` with the per-platform coordinates **baked in as constants**.
> The shipped scripts never parse a manifest. Everything else in v7 — the
> `~/.mcp-steroid/` layout, content-addressed cache, verbatim unpack,
> atomic+multi-process-safe install, auto-GC, agent wizard, `DEVRIG_*`
> overrides, all-stderr discipline — is **reused verbatim**.

---

## 1. Goal, scope, and the platform × script × JDK matrix

### Goal

One published artifact set — `version.json` plus two **generated** install
scripts — fully describes how to install devrig + a matching JDK 25 on every
supported platform with **zero runtime manifest parsing** and **zero network
calls beyond the URLs named in the manifest**. The script is dumb; the
complexity lives in a Kotlin generator and in the daily coordinate-resolution
job.

### Scope

- **In:** `version.json` v8 schema; a checked-in JDK coordinates source; the
  generator (Gradle task) and its two consumers (website Makefile,
  `:test-integration`); the two generated scripts; the `bin/`
  self-registration change in devrig; the daily coordinate-refresh GH Action;
  Docker integration tests of the generated scripts; removal of the committed
  static scripts.
- **Out:** signing/two-key release (`devrig upgrade` signature flow stays as
  v7 specifies — v8 does not change it); the native-binary migration
  (v7 appendix, unchanged); Hugo/site authoring.

### Platform × script × JDK matrix (D6, D7 resolved)

| platform        | `<os>-<cpu>` key | install script | JDK vendor / archive          | `format` |
|-----------------|------------------|----------------|-------------------------------|----------|
| macos-arm64     | `macos-arm64`    | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| linux-arm64     | `linux-arm64`    | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| linux-x64       | `linux-x64`      | `install.sh`   | Amazon Corretto 25            | `tar.gz` |
| windows-x64     | `windows-x64`    | `install.ps1`  | Amazon Corretto 25            | `zip`    |
| windows-arm64   | `windows-arm64`  | `install.ps1`  | **Azul Zulu 25** (Microsoft fallback) | `zip` |

- **Canonical key form is `<os>-<cpu>`** with `os ∈ {macos, linux, windows}`
  and `cpu ∈ {arm64, x64}`. The scripts' `uname`/`OSArchitecture` detection
  normalizes to these tokens (`Darwin→macos`, `aarch64|arm64→arm64`,
  `x86_64|amd64|X64→x64`). This is a deliberate rename from v7's
  `darwin`/`x86_64` so the manifest keys read the same everywhere; the
  generator is the single place that knows the mapping.
- **windows-arm64 (D6):** Corretto 25 ships **no** Windows/aarch64 build
  (verified 404). v8 uses a **second vendor**: primary **Azul Zulu 25**
  (`api.azul.com/metadata/v1` gives `download_url` + `sha256_hash` + version
  in one JSON call; archive dir name == archive basename), fallback
  **Microsoft Build of OpenJDK 25** (`aka.ms` redirectors + `.sha256sum.txt`
  sidecars). This is a *real native arm64 JDK*, not the WIP's x64-under-
  emulation hack — the WIP's `'arm64' → 'x64' (emulated)` branch in
  `install.ps1` is **deleted** in v8.

---

## 2. `version.json` v8 schema

The existing file is `{"version-base": "<VERSION>"}` and is consumed by
`UpdateChecker.kt`, `DevrigUpdateChecker.kt`, `BackendVersionSkew.kt`, all
with `ignoreUnknownKeys = true`. v8 is **purely additive**: it keeps
`version-base` untouched and adds an `installer` object. Existing consumers
ignore `installer`; the generator reads only `installer`.

### Annotated example

```jsonc
{
  // ── EXISTING — untouched, still read by UpdateChecker/BackendVersionSkew ──
  "version-base": "0.96.20003",

  // ── NEW — additive installer block (everything below) ──
  "installer": {
    "installerSchema": 8,                       // bump on any breaking shape change
    "version": "0.96.20003-0123abcd",           // full devrig build version (== folder version segment)
    "builtAt": "2026-06-15T18:00:00Z",          // informational
    "baseUrl": "https://github.com/jonnyzzz/mcp-steroid/releases/download/v0.96.20003",

    // One object per supported "<os>-<cpu>". A platform absent here is
    // "unsupported" and the generated script fails loudly for it.
    "platforms": {
      "linux-x64": {
        "devrig": {
          "url":      "https://github.com/.../v0.96.20003/devrig-installDist.zip",
          "sha256":   "abcd…(64 hex)…",          // lowercase hex
          "size":     53412345,                  // bytes; progress only
          "format":   "zip",
          "binSubpath": "devrig-0.96.20003/bin/devrig"  // launcher inside the verbatim tree
        },
        "jdk": {
          "vendor":   "corretto",
          "url":      "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-linux-x64.tar.gz",
          "sha256":   "1234…(64 hex)…",
          "size":     197218400,
          "format":   "tar.gz",
          "javaHomeSubpath": "amazon-corretto-25.0.3.9.1-linux-x64"  // dir inside tree to use as JAVA_HOME; "" = unpack root
        }
      },
      "macos-arm64": {
        "devrig": { "url": "…", "sha256": "…", "size": 0, "format": "zip",
                    "binSubpath": "devrig-0.96.20003/bin/devrig" },
        "jdk":    { "vendor": "corretto", "url": "…", "sha256": "…", "size": 0, "format": "tar.gz",
                    "javaHomeSubpath": "amazon-corretto-25.jdk/Contents/Home" }
      },
      "linux-arm64":  { "devrig": { … }, "jdk": { "vendor": "corretto", "format": "tar.gz",
                        "javaHomeSubpath": "amazon-corretto-25.0.3.9.1-linux-aarch64", … } },
      "windows-x64":  { "devrig": { …, "binSubpath": "devrig-0.96.20003/bin/devrig.bat" },
                        "jdk": { "vendor": "corretto", "format": "zip",
                                 "javaHomeSubpath": "jdk25.0.3_9", … } },
      "windows-arm64":{ "devrig": { …, "binSubpath": "devrig-0.96.20003/bin/devrig.bat" },
                        "jdk": { "vendor": "azul-zulu", "format": "zip",
                                 "javaHomeSubpath": "zulu25.34.17-ca-jdk25.0.3-win_aarch64", … } }
    }
  }
}
```

### Field table

| Field | Type | Meaning |
|---|---|---|
| `version-base` | string | **Existing.** Plugin/update base version. Untouched. |
| `installer.installerSchema` | int | Manifest shape version. v8 = `8`. Generator asserts a known value; scripts ignore it (baked already). |
| `installer.version` | string | Full devrig build version; equals the `<version>` segment of every content-addressed folder. |
| `installer.builtAt` | string (ISO-8601) | Informational build timestamp. |
| `installer.baseUrl` | string | Release base for devrig artifacts (JDK URLs are vendor-absolute). |
| `installer.platforms.<os>-<cpu>.devrig.url` | string | devrig archive download URL. |
| `…devrig.sha256` | string | **SHA-256** lowercase hex. (v7 used sha512; v8 standardizes on **sha256** — every vendor here publishes sha256, and `shasum -a 256`/`Get-FileHash SHA256` are universal. 12-char prefix used for folder naming.) |
| `…devrig.size` | int | Bytes; progress display only; `0` = unknown. |
| `…devrig.format` | enum | `zip` \| `tar.gz` \| `tar.xz`. |
| `…devrig.binSubpath` | string | Launcher path inside the verbatim devrig tree (`bin/devrig` POSIX, `bin/devrig.bat` Windows). |
| `…jdk.vendor` | string | `corretto` \| `azul-zulu` \| `microsoft`. Diagnostics + daily-job routing; scripts don't branch on it. |
| `…jdk.url` / `.sha256` / `.size` / `.format` | as above | JDK archive coordinates. |
| `…jdk.javaHomeSubpath` | string | Dir inside the verbatim JDK tree to export as `JAVA_HOME`; `""` = unpack root. Covers macOS `Contents/Home`, versioned Linux/Win dirs uniformly. |

**ignoreUnknownKeys-safe:** the entire `installer` object is new; no existing
key is renamed or removed. Conversely the generator must tolerate unknown
*future* keys inside `installer.*` (parse with a lenient JSON reader, read
only the fields it knows).

---

## 3. JDK coordinate source + how it becomes `version.json`

### The checked-in source: `website/installer/jdk-coordinates.json`

This is the **only** file the daily job edits and the **only** non-generated
input for JDK entries. It is committed; it is *not* served to users.

```jsonc
// website/installer/jdk-coordinates.json — edited by the daily GH Action PR
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

**Who computes shas (resolved):** the **daily job** computes them, by reading
each vendor's *published* checksum (never by re-hashing a downloaded blob —
that would just echo whatever it downloaded). Corretto:
`GET https://corretto.aws/downloads/latest_sha256/<asset>` (bare 64-hex).
Azul: the `sha256_hash` field in `api.azul.com/metadata/v1` package detail.
Microsoft (fallback): the `.sha256sum.txt` sidecar. The committed file
therefore carries vendor-authoritative checksums; the generator copies them
verbatim into `version.json`. `javaHomeSubpath` for Azul is derived by
stripping `.zip` from the filename (vendor guarantee); for Microsoft it must
be read from the archive root (`jdk-25.0.x+N`) and is recorded explicitly.

### How `jdk-coordinates.json` + devrig build → `version.json` JDK entries

The generator (section 4) merges three sources into `installer.platforms`:

1. **devrig artifacts** — URL/sha256/size/format/binSubpath, produced exactly
   as today by reusing the logic in `generateVersionJson`
   (`npx-kt/build.gradle.kts:428`) but emitting `sha256` and per-platform
   `binSubpath`. (The devrig installDist zip is universal today; v8 keeps one
   devrig zip and lists it under every platform, differing only in
   `binSubpath` `devrig` vs `devrig.bat`.)
2. **JDK coordinates** — copied straight from `jdk-coordinates.json`.
3. **version + baseUrl** — from the Gradle `project.version`.

The Makefile's old one-liner (`echo '{"version-base":…}' > static/version.json`
at `website/Makefile:49`) is **replaced** by a `./gradlew` shell-out
(section 4) that writes the full v8 file.

---

## 4. The generator

### Ownership: `:installer-gen` Gradle module (new), task `generateInstaller`

A small new Kotlin/JVM module `installer-gen` (sibling of `jdk-downloader`),
registered in `settings.gradle.kts`. Rationale: it must be invokable from
**both** the website Makefile (not a Gradle module → shells out to
`./gradlew`) **and** `:test-integration` (a Gradle dependency). A standalone
module gives both a single entry point and keeps script-templating logic out
of `npx-kt`'s already-large build script. It depends on `:npx-kt`'s
`devrigPackageElements` (the dist zip) and on the `jdkManifest`-style
configuration, reusing `parseJdkManifest`/`sha256`/`stableJson` helpers.

```
:installer-gen
  src/main/kotlin/.../InstallerGenerator.kt      // version.json (v8) writer + script templater
  src/main/resources/templates/install.sh.tmpl   // POSIX template with @@PLACEHOLDERS@@
  src/main/resources/templates/install.ps1.tmpl  // PowerShell template
  src/main/resources/templates/launcher.sh.tmpl   // bin/devrig POSIX launcher template
  src/main/resources/templates/launcher.cmd.tmpl  // bin/devrig.cmd
  src/main/resources/templates/launcher.ps1.tmpl  // bin/devrig.ps1
  build.gradle.kts
```

**Task `generateInstaller`** (group `installer`):

- Inputs: `jdk-coordinates.json` (`website/installer/`), devrig dist zip
  (configuration), `project.version`, the four template resources.
- Outputs (into `-PoutDir` or default `build/installer/`):
  - `version.json` — full v8 manifest.
  - `install.sh`, `install.ps1` — **data baked in** (no manifest fetch at
    runtime; the per-platform `url`/`sha256`/`size`/`format`/`binSubpath`/
    `javaHomeSubpath` for all 5 platforms are emitted as shell `case`
    arms / PowerShell hashtable literals).
  - The launcher templates are *not* emitted as files here — they are
    embedded as heredoc/here-string bodies *inside* the install scripts (the
    install script writes `bin/devrig` at install time, section 5/6), so the
    generator templates them into the install-script body with the same
    `@@PLACEHOLDERS@@` resolved per platform.
- Determinism: stable key order, `\n` line endings (the `.ps1` is still
  `\n`-terminated; PowerShell tolerates LF), trailing newline — so the daily
  PR diff is minimal and reviewable.

Invocation contract (both consumers use the same flags):

```
./gradlew :installer-gen:generateInstaller \
    -PoutDir=<dir> -PcoordinatesFile=<path-to-jdk-coordinates.json>
```

### Website Makefile wiring

Replace the `version.json` line and add the scripts, landing everything in
`website/static/` (which is **gitignored** for these three files, section 10):

```make
update-config: ensure-public-repo copy-root-files
	@echo "Generating installer (version.json + install.sh + install.ps1) for $(VERSION)"
	( cd .. && ./gradlew :installer-gen:generateInstaller \
	    -PoutDir=website/static \
	    -PcoordinatesFile=website/installer/jdk-coordinates.json )
	@test -s static/version.json   || { echo "ERROR: version.json not generated" >&2; exit 1; }
	@test -s static/install.sh     || { echo "ERROR: install.sh not generated"   >&2; exit 1; }
	@test -s static/install.ps1    || { echo "ERROR: install.ps1 not generated"  >&2; exit 1; }
	# … existing updatePlugins.xml generation continues unchanged …
```

Hugo copies `static/*` to the site root, so the URLs stay
`https://mcp-steroid.jonnyzzz.com/{version.json,install.sh,install.ps1}` —
**no change to how the website is generated**, only new inputs in `static/`.

---

## 5. Generated `install.sh` + `install.ps1` behavior

Both scripts are the v7/WIP scripts with the **release-resolution removed**
(no GitHub `releases/latest` call, no `corretto.aws/latest_sha256` call) and
the per-platform table **baked in**. Reuse verbatim from the WIP
(`0101-installer-docker`): `promote_tree` / `Move-TreeIntoPlace`
(atomic-rename + lost-race nesting cleanup), the `.tmp.$$`/`.tmp.$PID`
staging + stale-sweep, all-stderr `log`/`Write-Log`, the
`Get-ProfileRelativePath` ASCII guard, the TLS-1.2 opt-in, `throw`-not-`exit`.

### Algorithm (identical for both, mechanics differ only in language)

```
1. Detect (os, cpu); DEVRIG_OS / DEVRIG_CPU override. Normalize to <os>-<cpu>.
2. key = "<os>-<cpu>". Select the BAKED-IN entry for key.
   If no entry → fail "platform <key> is not supported by this build".
3. mkdir -p ~/.mcp-steroid/{bin,binaries}
4. For each artifact in (devrig, jdk):
     sha12  = first 12 chars of <sha256>
     name   = "<artifact>-<os>-<cpu>-<version>-<sha12>"   (D3)
     target = binaries/<name>/
     if target exists  → log "already installed: <name>"; continue   (idempotent)
     download <url>            → .tmp.$$.<artifact>.<ext>   (into binaries/, same FS → atomic mv)
     verify SHA-256 == <sha256>  (mandatory; mismatch → fail)
     unpack VERBATIM (no --strip) by <format>:
         zip    → unzip / Expand-Archive
         tar.gz → tar -xzf
         tar.xz → tar -xJf
       into .tmp.$$.<artifact>.unpack/
     promote_tree .tmp → target    (atomic; loser cleans up)
     rm the downloaded archive
5. devrig launcher = target_devrig/<binSubpath>      (chmod +x on POSIX)
6. JAVA_HOME:
     if $DEVRIG_JAVA_HOME set → use it (skip JDK entirely; honored by launcher)
     else → target_jdk/<javaHomeSubpath>   ("" = unpack root)
7. Write ~/.mcp-steroid/bin/<launcher> (POSIX: devrig; Windows: devrig.ps1 + devrig.cmd),
   embedding launcher + JAVA_HOME paths RELATIVE to $HOME / %USERPROFILE%,
   atomically (write .tmp → mv -f). Launcher exports JAVA_HOME then exec's devrig.
8. PATH hint to stderr. Final hint: run `devrig install` for the agent wizard.
```

Differences from WIP that the generator bakes in:

- **No live discovery.** Steps "resolve latest release" and "fetch
  latest_sha256" are *gone*. URL + sha256 + format + size + subpaths are
  literals. This is the whole point of D2.
- **Folder name carries `<version>` (D3):** `…-<os>-<cpu>-<version>-<sha12>`,
  12-char sha, not the full hash. `no cleanup (only add)` — the install
  scripts never delete a cache dir (auto-GC stays a devrig-side concern,
  section 6 / v7).
- **windows-arm64 gets a real arm64 JDK** (Azul), not x64-emulated. The WIP's
  emulation branch is removed.

All output to **stderr** (stdout reserved for the JVM/MCP channel). Both
remain wrapped in `main()` / a final invocation so a truncated `curl | sh`
can't execute a partial body.

---

## 6. `~/.mcp-steroid` layout + devrig `bin/` self-registration

### Layout (v8 — content-addressed with version segment, no GC by the script)

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

No `version.json`/`version.properties`/`signatures`/`allowed_signers` copied
into `~/.mcp-steroid` by the install **script** in v8 — the script needs no
manifest at runtime. (`devrig upgrade`, an inner-Java concern unchanged from
v7, may still fetch `version.json` from the web when run.)

### devrig bin self-registration (D4) — which Kotlin changes

devrig must "ensure the `bin/` entry exists and (re)create it pointing at its
own downloaded location." The existing pieces already do most of this:

- `DevrigRoot.path` (`DevrigRoot.kt`) gives devrig its own installDist root
  by walking up from `codeSource` to the dir containing `lib/` +
  `ij-plugin.zip` — i.e. `binaries/devrig-…-<sha12>/<top>/`.
- `selfMcpCommand` / launcher resolution (`InstallCommand.kt:140-174`)
  already prefers `<DevrigRoot.path>/bin/<name>` and falls back to
  `ProcessHandle.current().info().command()`.

**New code (one file): `npx-kt/.../devrig/BinLauncher.kt`**, called early in
every `devrig mcp` / `devrig install` startup (next to the auto-GC sweep
hook):

```
fun ensureBinLauncher(home: HomePaths) {
  val binDir   = home.home.resolve("bin")
  val launcherName = if (isWindows) "devrig.ps1" else "devrig"   // + devrig.cmd on Windows
  val target   = binDir.resolve(launcherName)
  val ownRoot  = DevrigRoot.path                       // .../binaries/devrig-…-<sha12>/<top>
  val ownBin   = ownRoot.resolve(if (isWindows) "bin/devrig.bat" else "bin/devrig")
  val ownJava  = Path.of(System.getProperty("java.home"))   // the JDK that launched us
  val desired  = renderLauncher(ownBin, ownJava)       // same template the install script uses
  if (!target.exists() || target.readText() != desired) {
      writeAtomically(binDir, target, desired)         // .tmp → mv -f; chmod +x on POSIX
      System.err.println("[mcp-steroid] (re)wrote $target -> $ownBin")
  }
}
```

This makes devrig **self-healing**: if `bin/devrig` is missing or points at a
stale/GC'd tree, the next launch rewrites it to devrig's *own* current
location and the JDK it is *currently running under* (so `JAVA_HOME` in the
launcher always matches a present JDK). The render template is shared with the
install-script generator (section 4) so the two never drift. `HomePaths.kt`
gains a `val binDir get() = home.resolve("bin")` accessor;
`resolveHomePathsOrDie`'s `mkdirsAll` adds `binDir`.

---

## 7. Daily GH Action — `installer-jdk-refresh.yml`

```yaml
name: installer-jdk-refresh
on:
  schedule: [{ cron: "0 6 * * *" }]   # 06:00 UTC daily
  workflow_dispatch:
```

Steps (a single Kotlin resolver, `:installer-gen:resolveJdkCoordinates`,
keeps logic testable and out of YAML):

1. **Resolve current coordinates** for all 5 platforms:
   - Corretto (4 platforms): GitHub API
     `repos/corretto/corretto-25/releases/latest` → `tag_name` (the
     `25.0.x.y.z` version) → build the
     `corretto.aws/downloads/resources/<ver>/<asset>` URL → fetch
     `latest_sha256/<asset>` for the checksum → known per-platform
     `javaHomeSubpath`.
   - windows-arm64 (Azul primary): `GET api.azul.com/metadata/v1/zulu/packages/?
     java_version=25&os=windows&arch=aarch64&archive_type=zip&java_package_type=jdk&
     release_status=ga&latest=true` → `download_url` + `package_uuid` → detail
     call for `sha256_hash`; `javaHomeSubpath` = filename minus `.zip`. On Azul
     failure, **Microsoft fallback** (`aka.ms/download-jdk/…`, `.sha256sum.txt`).
2. **Diff** the resolved set against `website/installer/jdk-coordinates.json`.
   If identical → exit 0 (no PR).
3. If changed → write the new `jdk-coordinates.json`, `git checkout -b
   installer/jdk-refresh-<date>`, commit ("installer: refresh JDK coordinates
   — Corretto 25.0.x → 25.0.y; …"), `gh pr create` against `main`.
4. The PR **only edits `website/installer/jdk-coordinates.json`**. When merged,
   the path filter on the existing `github-pages.yml`
   (`on.push.paths: ['website/**', 'VERSION']`) already matches
   (`website/installer/**` is under `website/**`) → the **existing** site
   build runs `make update-config`, regenerating `version.json` +
   `install.sh` + `install.ps1` from the new coordinates and deploying.

> **"We don't change how the website is generated, only the inputs."** The
> daily job never touches the Hugo build, the Makefile, or the generator — it
> edits one data file. The path-trigger does the rest. If
> `website/installer/**` is not already covered, add it to `github-pages.yml`'s
> `paths` list (one-line change).

---

## 8. Integration tests (D8)

### Module: `:test-integration`, new harness `InstallerBootstrapTest.kt`

Two Docker drivers, both running **generated** scripts (the test first runs
`:installer-gen:generateInstaller -PoutDir=<tmp>` to produce a fresh
`version.json` + scripts, then mounts the output dir read-only into the
container — proving the *generated* artifact, not a committed one).

| Driver | Image | Runs | Proves |
|---|---|---|---|
| `test-install-sh.sh` (reuse WIP, repointed) | `ubuntu:24.04` | `sh /gen/install.sh` | POSIX path on Linux, no host JDK, space-in-HOME, idempotent re-run, no `.tmp.*` leftovers |
| `test-install-ps1.ps1` (**new** — WIP had `.sh` wrapper only) | `mcr.microsoft.com/powershell:latest` | `pwsh /gen/install.ps1` | PowerShell *logic* (detection, hashtable lookup, Get-FileHash verify, Move-TreeIntoPlace, ASCII shim guard) cross-platform |

The WIP `test-install-sh.sh` is reused almost verbatim; only its mount path
changes from `/website/static/install.sh` to the generated `/gen/install.sh`,
and its `grep '^devrig-linux-'` assertions update to the v8 folder shape
`devrig-linux-<cpu>-<version>-<sha12>` / `jdk-linux-…`.

**Kotlin harness** (`InstallerBootstrapTest`, JUnit, in `:test-integration`):

- `@BeforeAll` runs the generator to a temp dir.
- Each test `docker run --rm -v <gen>:/gen:ro <image> <driver>`, captures
  stdout, asserts the **success marker** is present.
- **Success markers:** `INSTALL_SH_E2E_OK` (existing) and `INSTALL_PS1_E2E_OK`
  (new, emitted by the ps1 driver after the same assertions: launcher present,
  `binaries/devrig-windows-…` + `jdk-windows-…` trees, second run prints
  exactly two "already installed", zero `.tmp.*` leftovers).
- **For pwsh-on-Linux**, the driver runs `windows-x64` *logic* but
  cannot exercise `.exe`/backslash/registry semantics; mark this explicitly in
  the driver header and keep Windows-specific bits (`devrig.cmd`,
  `java.exe` lookup) parameterized so the Linux run substitutes `java`. True
  Windows-native verification stays a manual/host concern (as today).

### CI wiring

Append to the `ciIntegrationTests` aggregator (root `build.gradle.kts`),
**after** the existing strict-ordered chain
(`:test-helper:test` → `:ij-plugin:integrationTest` → `:test-integration:test`),
gated by the same Docker `onlyIf`. The new tests live inside
`:test-integration:test`, so no new aggregator task is needed — they ride the
existing `ciIntegrationTests` entry. They must run **sequentially** (each spins
a container; the repo's "never run Docker tests in parallel" rule applies) —
enforce with `maxParallelForks = 1` on the installer test tag.

---

## 9. Cherry-pick plan (from `0101-installer-docker`)

| Commit | Take? | Action |
|---|---|---|
| `c615ac7a` website: one-command bootstrap installers (`install.sh`/`.ps1`) | **base** | Cherry-pick as the script skeleton, then **refactor**: strip GitHub-release + `corretto.aws/latest_sha256` live resolution; replace with baked-in per-platform `case`/hashtable; rename folder scheme to `…-<version>-<sha12>`; switch sha512→sha256 naming where relevant. |
| `0c3a9259` harden bootstrap installers per review | **yes** | Keep all hardening: `promote_tree`/`Move-TreeIntoPlace`, stale-`.tmp` sweep, `Get-ProfileRelativePath` ASCII guard, TLS-1.2 opt-in, `throw`-not-`exit`, `main()` wrapper. |
| `067044b1` WIP: installer JDK auto-download + `install-tests/` scaffolding | **partial** | Take the JDK download/verify/unpack *mechanics* and the `website/install-tests/test-install-sh.sh` driver. **Drop** the live `latest_sha256` resolution (now baked) and the windows-arm64 x64-emulation branch (now real Azul arm64). Finish the missing `test-install-ps1.ps1`. |
| `0135699e` / `0dbcec8a` `devrig install --check` (#86) | **yes (independent)** | Unrelated to manifest choice; cherry-pick as-is — it improves the wizard reused in v8. |
| `32750ec0` TODO: PS5.1 smoke-test follow-up | **note only** | Folds into section 8's "Windows-native is manual/host" caveat. |

After cherry-picks, the **refactor to v8** is: (a) introduce `:installer-gen`
+ templates; (b) move the per-platform table out of the scripts into template
placeholders; (c) add `jdk-coordinates.json`; (d) repoint the Makefile and
tests at generated output; (e) delete the committed static scripts (§10).

---

## 10. De-binary-fication

1. **Remove committed scripts** from `main`:
   `git rm website/static/install.sh website/static/install.ps1`.
   (They were the only "binary-like" generated artifacts checked in.)
2. **`.gitignore`** (under `website/`):
   ```gitignore
   # generated by :installer-gen:generateInstaller (do not commit)
   static/version.json
   static/install.sh
   static/install.ps1
   ```
   `version.json` was already produced at build time (Makefile `echo`), so
   ignoring it formalizes existing behavior.
3. **`jdk-coordinates.json` stays committed** — it is *input data*, the daily
   PR target, not a generated artifact.
4. **Dev (`deployNpx`)**: dev mode (v7 §"Development mode") pre-populates the
   cache and writes `bin/` launchers directly via the Kotlin `ensureBinLauncher`
   (§6) + the dev-mode cache copy — it does **not** need the generated install
   scripts at all. Optionally, `deployNpx` can run
   `:installer-gen:generateInstaller -PoutDir=build/installer` for local
   inspection, but that output is gitignored and not required for dev.
5. **CI**: the site build (`github-pages.yml` → `make update-config`)
   generates and deploys the scripts; `:test-integration` generates them into
   a temp dir for its Docker runs. Neither reads a committed copy. The
   single source of truth for "what gets installed" is therefore
   `jdk-coordinates.json` + the devrig build + the generator — all
   reproducible, none binary, none committed-as-output.

---

## 11. Risks / open questions + how v8 supersedes v7

### Risks / open questions

1. **Azul / Microsoft URL stability for windows-arm64.** Azul's
   `cdn.azul.com/zulu/bin/<file>.zip` URLs are versioned and stable once
   published; the daily job re-resolves via the metadata API so a vanished
   pin is caught within a day. *Open:* should the weekly URL-liveness GH
   Action (v7 §release-side) also HEAD the JDK URLs? **Recommend yes** — add
   all 5 JDK URLs + the devrig URL to that liveness check.
2. **Corretto `javaHomeSubpath` versioning.** Linux/Windows Corretto dir names
   embed the full `25.0.x.y.z`, so `javaHomeSubpath` changes on every patch —
   but it's regenerated from `jdk-coordinates.json`, so the daily job keeps it
   correct. macOS (`amazon-corretto-25.jdk/Contents/Home`) is stable.
3. **Microsoft fallback `javaHomeSubpath` (`jdk-25.0.x+N`) can't be derived
   from the filename** (unlike Azul). The daily resolver must read it from the
   archive (or a per-build lookup). *Decision:* only resolve Microsoft when
   Azul is unavailable, and have the resolver fetch+inspect the archive root
   in that branch.
4. **sha256 vs sha512 (v7).** v8 standardizes on sha256 — every vendor
   publishes it, all OS tools compute it, 12-char prefix is ample for cache
   addressing. This is intentional, not a regression.
5. **One universal devrig zip across 5 platforms.** Today's build ships a
   single dist zip; v8 lists it under all platforms with differing
   `binSubpath`. If per-platform devrig zips ever appear, the schema already
   supports it (per-platform `devrig.url`) with no shape change.
6. **pwsh-on-Linux ≠ Windows.** Acknowledged (§8): logic-only coverage;
   Windows-native verification stays manual.

### How v8 supersedes v7 (note for the spec)

> **v8 supersedes v7's manifest choice only.** Replace
> `version.properties` + the three runtime parsers with `version.json` (the
> file the website already serves) + a build-time **generator** that bakes the
> per-platform table into `install.sh` / `install.ps1`. The script no longer
> parses any manifest at runtime (D2). All other v7 contracts —
> `~/.mcp-steroid/` layout (now with a `<version>` segment in folder names,
> D3), verbatim unpack, atomic + multi-process-safe install, `DEVRIG_*`
> overrides, all-stderr, the agent wizard, auto-GC, dev-mode, and the
> signed-`devrig upgrade` flow — carry over unchanged. v7's native-binary
> appendix remains the documented future migration. Add windows-arm64 via a
> second JDK vendor (Azul Zulu 25, Microsoft fallback) since Corretto 25 has
> no Windows/aarch64 build (D6).
```

result: Drafted the full v8 design doc as my message above — 11 sections, implementation-ready, naming the new `:installer-gen` module + `generateInstaller`/`resolveJdkCoordinates` tasks, the `website/installer/jdk-coordinates.json` source, exact v8 `version.json` `installer.*` schema (sha256, `binSubpath`/`javaHomeSubpath`, `installerSchema: 8`), the `~/.mcp-steroid/binaries/<artifact>-<os>-<cpu>-<version>-<sha12>/` layout, `BinLauncher.kt` self-registration, the `installer-jdk-refresh.yml` daily cron + `github-pages.yml` path-trigger, the D8 Docker drivers (`ubuntu:24.04` + `mcr.microsoft.com/powershell`) with `INSTALL_SH_E2E_OK`/`INSTALL_PS1_E2E_OK` markers, the cherry-pick table, and de-binary-fication. Azul Zulu primary / Microsoft fallback for windows-arm64 incorporated. The doc is returned verbatim above (not written to disk, per instructions).
---

## 12. Quorum resolutions (v8.1)

Outcome of the 3× cross-model `run-agent.sh` quorum (Claude ×2 + Codex): 2 PASS_WITH_NOTES + 1
HOLD. Directionally sound; the following resolutions are adopted and are binding for P3.

- **R-1 — No auto-GC (honor "no cleanup").** Req#6 is explicit ("we keep adding"). v8 **removes**
  the carried-over v7 auto-GC entirely (its keep-set depended on the now-absent on-disk manifest).
  `binaries/` grows monotonically by design. Risk note: each new devrig/JDK version adds
  ~200–400 MB; acceptable per the explicit decision. A manual opt-in `devrig prune` may be added
  later, never automatic. (Closes the Codex HOLD #1; R1#2; R3#1.)
- **R-2 — Commit `website/installer/devrig-coordinates.json`** (url/sha256/size/format/binSubpath
  per platform), written at **release time** (by the devrig release task/CI), symmetric with
  `jdk-coordinates.json`. `generateInstaller` becomes a **pure data-merge** (devrig-coords +
  jdk-coords + version) with **no** devrig/plugin build dependency — so the Hugo site deploy and
  the daily JDK PR regenerate version.json + scripts without building the IntelliJ plugin, and the
  devrig sha is the published-artifact sha (never a non-reproducible local re-hash). (R1#3; R3#2.)
- **R-3 — version.json is the single script-generation source of truth.** `generateInstaller`
  builds ONE manifest model → writes `version.json` → renders `install.sh`/`install.ps1` by
  **re-reading the serialized version.json**. No side-channel. (Codex #3; req#1.)
- **R-4 — Plugin URL.** The IntelliJ plugin ships **inside** the devrig artifact (devrig dist
  bundles `ij-plugin.zip`; `DevrigRoot` walks `lib/`+`ij-plugin.zip`); no separate
  `installer.platforms.*.plugin` entry. Documented in §2/§3. The existing `version-base` +
  `updatePlugins.xml` remains the marketplace plugin-update channel. (Codex #2; R3#11.)
- **R-5 — windows-arm64 (Azul) is integration-tested.** The pwsh-on-Linux driver ALSO runs the
  `windows-arm64` entry (`DEVRIG_OS=windows DEVRIG_CPU=arm64`): download → sha256-verify →
  Expand-Archive → resolve `javaHomeSubpath`. (.exe/registry excluded.) (R1#4; R3#3.)
- **R-6 — One canonical launcher render.** A single shared renderer emits the `bin/` launcher
  **relative to `$HOME`/`%USERPROFILE%`**; devrig's `BinLauncher` compares **normalized** content
  (not raw `readText`) so it rewrites only on a real change (no rewrite-every-launch). (R1#1.)
- **R-7 — Lock-free install, correctly described.** v8 uses the WIP's lock-free atomic-rename +
  loser-cleanup (a **change** from v7's `mkdir` per-SHA locks — not "verbatim"). The stale-`.tmp.*`
  sweep removes an entry only when its pid is dead OR mtime exceeds a threshold, never the current
  pid's. D8 adds a concurrent-install case (two `install.sh` in parallel in one container).
  (R1#6; R3#7.)
- **R-8 — Hermetic per-PR CI.** Routine `ciIntegrationTests` uses `file://` fixtures / a tiny pinned
  fake-archive with a known sha256 (no CDN). Real-CDN download+verify runs only in the daily/weekly
  job. Avoids the banned detection-and-skip and CDN flakiness. (R3#10.)
- **R-9 — Byte-verify + warm fallback.** The daily resolver downloads ≥1 canonical JDK + the devrig
  zip and verifies bytes vs the recorded sha256 (fail the PR on mismatch); it resolves **both**
  vendors daily (Azul active, Microsoft standby recorded) so the MS path stays warm; and it inspects
  the actual archive top-level dir for `javaHomeSubpath` for **both** vendors (not string-derived).
  (R3#4/#5; R1#8.)
- **R-10 — pwsh home resolution.** `install.ps1` resolves the install root via
  `$env:USERPROFILE` → `$HOME` → `[Environment]::GetFolderPath('UserProfile')` (so the pwsh-on-Linux
  test can locate `~/.mcp-steroid`). D8-parameterized. (R3#6.)
- **R-11 — `devrig upgrade` is OUT OF SCOPE for v8.** The v7 signed-`version.properties` upgrade does
  NOT carry over unchanged; v8 ships install + generated scripts only. Upgrade redesign deferred
  (tracked in TASKS). (Codex #4.)
- **R-12 — Enforce installDist root name.** `distributions { main { distributionBaseName =
  "devrig-$version" } }` so `binSubpath` is build-enforced, not asserted. (R1#5.)
- **R-13 — No nested Gradle in tests.** `:test-integration:test` `dependsOn(generateInstaller)` and
  consumes its output dir via a system property; it never re-invokes `./gradlew`. (R3#8.)
- **R-14 — Launcher stdout discipline.** The generated `bin/devrig` launcher writes only stderr
  before `exec` (MCP stdio channel); a test asserts empty stdout pre-exec. (R3#9.)
- **R-15 — Install-time trust model (documented).** Trust root = TLS to the website
  (install.sh + version.json) + TLS to each JDK vendor; version.json is **unsigned** in v8;
  integrity = full sha256 verification of every downloaded artifact against the baked value. A
  signed manifest is deferred together with `devrig upgrade` (R-11). (R1#7.)

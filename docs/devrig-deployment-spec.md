# devrig deployment & update spec (v7 — Properties manifest, native-binary appendix)

Author: Eugene Petrenko · Status: design ready for implementation

> **Implementation note (2026-06, PR #117):** the Windows user launcher is
> **CMD-only** (`~/.mcp-steroid/bin/devrig.cmd`); PowerShell is used only by the
> install script, never the launcher. The devrig binary (re)writes the launcher
> on every start and owns the user-PATH entry — so the v7 staged-`.new`
> self-update mechanism below is illustrative; the implementation rewrites the
> launcher atomically on each start instead.

> **Iteration history**: v1 in-session restart via exit 239 → v2 dropped
> after quorum review (MCP spec) → v3 devrig.dev pattern → v4 bundled JDK +
> curl-bootstrap + two-key sigs → v5 Corretto + binaries/ + agent wizard +
> PowerShell-only Windows → v6 manifest-driven unpack + JDK-not-JRE + prune
> + dev-mode pre-population → v7 (this doc) automatic GC (no `prune`),
> verbatim unpack (no strip), Properties-format manifest, native-binary
> alternative analyzed.

## What the user gets

```sh
curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh -s install
```

A self-updating launcher at `~/.mcp-steroid/bin/devrig` (or `devrig.cmd`
on Windows) registered with whichever of Claude / Codex / Gemini are on
PATH. The mcp-steroid Java binary + a matching Amazon Corretto JDK are
cached under `~/.mcp-steroid/binaries/`. After install, **zero network
calls** unless `devrig upgrade` is run or a cache directory is missing.

> **Implementation note (PR #117/#124):** the install script no longer writes
> `bin/devrig` itself (the "bootstrap mode → write the script's own bytes →
> bin/devrig" step below is stale on this point). After unpack it delegates to
> the register-only subcommand `devrig install devrig --install-script=<launcher>
> --jdk-home=<jdk>`; the **devrig binary owns the wrapper + PATH** (atomic
> self-heal on every start, gated by `DEVRIG_BIN_NO_AUTO_REGISTER`). Motto: the
> install script passes ALL non-trivial params to the binary — no env-derived
> magic.
>
> **Windows PATH:** the launcher only CHECKS user-PATH membership at startup
> (pure Java can't persist user PATH; no PowerShell on the start/MCP hot path)
> and prints a one-time manual hint if absent. Persisting the entry is done via
> the **HKCU user-PATH registry** on the install/register path only. Agents
> always launch the wrapper by ABSOLUTE path, so MCP works regardless of PATH.

## Filesystem layout

```
~/.mcp-steroid/                     ← fixed location, not configurable
├── version.properties              ← single manifest (Properties format)
├── version.properties.signatures   ← two ed25519 sigs, separate file
├── allowed_signers                 ← OpenSSH allowed_signers (pins both pubkeys)
├── bin/
│   ├── devrig                      ← POSIX shell wrapper
│   ├── devrig.cmd                  ← Windows CMD wrapper
│   ├── devrig.new, devrig.cmd.new  ← staged updates, promoted on next launch
├── binaries/                       ← hardcoded; downloads + unpacked trees
│   ├── devrig-<os>-<cpu>-<sha>/    ← unpacked devrig archive (verbatim — no stripping)
│   ├── jdk-<os>-<cpu>-<sha>/       ← unpacked Corretto JDK (verbatim)
│   ├── .tmp.<pid>.<sha>/           ← per-process unpack staging
│   └── .locks/<sha>/               ← mkdir-based per-SHA install locks
├── backends/, caches/, downloads/, logs/, markers/, state/    ← existing runtime
```

**Downloaded archives are deleted after successful unpack.** The unpacked
tree is the source of truth; the wrapper never re-verifies on launch.

**Unpacked verbatim.** The archive's internal directory structure is
preserved exactly. There is no `--strip-components` step. Locations within
the unpacked tree are spelled out by the manifest (`binSubpath`,
`javaHomeSubpath`).

## Manifest format: `version.properties`

Java `.properties` flat key=value text. Chosen because:

- **Built-in Java parser** (`java.util.Properties`).
- **Trivial POSIX shell parsing** (`grep '^key=' | cut -d= -f2-`).
- **Trivial PowerShell parsing** (`Get-Content | Where-Object …` then split).
- No external dependencies anywhere — no `jq`, no PowerShell YAML module,
  no parser library on the Java side.
- ISO-grade defined, well understood, editable.

Trade-off accepted: no nesting → dot-separated keys with a deterministic
prefix scheme.

### Example

```properties
# mcp-steroid devrig manifest
# (lines beginning with # are comments; blank lines ignored)

schema=1
version=0.96.20003-0123abcd
builtAt=2026-05-28T18:00:00Z

# ─── linux-x86_64 ────────────────────────────────────────────────────────
binaries.linux-x86_64.devrig.url=https://mcp-steroid.jonnyzzz.com/dl/0.96.20003/devrig-installDist.zip
binaries.linux-x86_64.devrig.sha512=abcd0123...
binaries.linux-x86_64.devrig.size=53412345
binaries.linux-x86_64.devrig.format=zip
binaries.linux-x86_64.devrig.binSubpath=devrig-0.96.20003/bin/devrig

binaries.linux-x86_64.jdk.url=https://corretto.aws/downloads/.../amazon-corretto-25.0.x-linux-x64-jdk.tar.gz
binaries.linux-x86_64.jdk.sha512=1234abcd...
binaries.linux-x86_64.jdk.size=197218400
binaries.linux-x86_64.jdk.format=tar.gz
binaries.linux-x86_64.jdk.javaHomeSubpath=amazon-corretto-25.0.x.x.x-linux-x64

# ─── darwin-arm64 ─────────────────────────────────────────────────────────
binaries.darwin-arm64.devrig.url=…
binaries.darwin-arm64.devrig.sha512=…
binaries.darwin-arm64.devrig.format=zip
binaries.darwin-arm64.devrig.binSubpath=devrig-0.96.20003/bin/devrig

binaries.darwin-arm64.jdk.url=…
binaries.darwin-arm64.jdk.sha512=…
binaries.darwin-arm64.jdk.format=tar.gz
binaries.darwin-arm64.jdk.javaHomeSubpath=amazon-corretto-25.jdk/Contents/Home

# ─── windows-x86_64 ───────────────────────────────────────────────────────
binaries.windows-x86_64.devrig.url=…
binaries.windows-x86_64.devrig.format=zip
binaries.windows-x86_64.devrig.binSubpath=devrig-0.96.20003/bin/devrig.bat

binaries.windows-x86_64.jdk.url=…
binaries.windows-x86_64.jdk.format=zip
binaries.windows-x86_64.jdk.javaHomeSubpath=jdk25.0.x_x

# … linux-arm64, darwin-x86_64, windows-arm64 …

# ─── self-updating wrapper scripts ────────────────────────────────────────
scripts.posix.url=https://mcp-steroid.jonnyzzz.com/dl/0.96.20003/devrig
scripts.posix.sha512=f00d…

scripts.windows.url=https://mcp-steroid.jonnyzzz.com/dl/0.96.20003/devrig.cmd
scripts.windows.sha512=cafe…
```

### Key fields

| Field | Meaning |
|---|---|
| `<artifact>.url` | Download URL (https:// or file:// for dev mode) |
| `<artifact>.sha512` | Lowercase hex; verified once, immediately after download |
| `<artifact>.size` | Optional, used for progress display |
| `<artifact>.format` | `zip`, `tar.gz`, or `tar.xz` |
| `binaries.<os>-<cpu>.devrig.binSubpath` | Path within the unpacked devrig tree to the launcher |
| `binaries.<os>-<cpu>.jdk.javaHomeSubpath` | Path within the unpacked JDK tree to set as JAVA_HOME (e.g., `Contents/Home` for macOS Corretto, an empty value means the unpack root). One field covers all platforms uniformly. |

### Shell parsing snippet

```sh
# read one field from version.properties
prop() {
  key="$1"
  awk -F= -v k="$key" '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      sub(/^[[:space:]]+/, "", $0)
      pos = index($0, "=")
      if (pos == 0) next
      key = substr($0, 1, pos - 1)
      gsub(/[[:space:]]+$/, "", key)
      if (key == k) {
        value = substr($0, pos + 1)
        sub(/^[[:space:]]+/, "", value)
        print value
        exit
      }
    }
  ' "$HOME/.mcp-steroid/version.properties"
}

DEVRIG_URL=$(prop "binaries.${OS}-${CPU}.devrig.url")
DEVRIG_SHA=$(prop "binaries.${OS}-${CPU}.devrig.sha512")
DEVRIG_BIN=$(prop "binaries.${OS}-${CPU}.devrig.binSubpath")
JDK_URL=$(prop "binaries.${OS}-${CPU}.jdk.url")
JDK_SHA=$(prop "binaries.${OS}-${CPU}.jdk.sha512")
JDK_HOME_SUB=$(prop "binaries.${OS}-${CPU}.jdk.javaHomeSubpath")
```

POSIX awk only — works on macOS BSD awk, GNU awk, busybox awk. No multi-line
regex, no jq, no YAML library.

### PowerShell parsing snippet

```powershell
function Get-Prop($Path, $Key) {
  Get-Content $Path | ForEach-Object {
    if ($_ -match '^\s*#') { return }
    if ($_ -match '^\s*$') { return }
    $eq = $_.IndexOf('=')
    if ($eq -lt 0) { return }
    $k = $_.Substring(0, $eq).Trim()
    if ($k -eq $Key) { return $_.Substring($eq + 1).Trim() }
  } | Select-Object -First 1
}
```

### Java parsing

```kotlin
val props = Properties().apply {
  Files.newBufferedReader(devrigHome.resolve("version.properties")).use { load(it) }
}
val devrigUrl = props.getProperty("binaries.$os-$cpu.devrig.url")
```

`java.util.Properties` is built-in JDK; no library.

### Signatures file

`version.properties.signatures` is also Properties format:

```properties
key1=-----BEGIN SSH SIGNATURE-----\n…\n-----END SSH SIGNATURE-----
key2=-----BEGIN SSH SIGNATURE-----\n…\n-----END SSH SIGNATURE-----
```

(Multi-line values use the standard Properties backslash-newline
continuation; or we store the signature as base64 on a single line. Either
works. Defer to implementation preference.)

Java reads with the same `Properties.load()`. The shell wrapper never reads
this file — sig verification is Java-only.

## Wrapper behavior

### Hard rules (POSIX and PowerShell, both)

1. **All output to stderr.** Stdout is reserved for the JVM after `exec`.
2. **Args forwarded verbatim.**
3. **No SHA verification on launch** — once `binaries/<name>/` exists, trust it.
4. **No network calls when cache is populated.**

### Algorithm

```
1. Promote any *.new files in bin/  (wrapper self-update, see below)
2. Detect (os, cpu) from uname or PSVersionTable; DEVRIG_OS / DEVRIG_CPU override.
3. Read ~/.mcp-steroid/version.properties  (or bootstrap-fetch in `install` mode).
4. Look up binaries.<os>-<cpu>.devrig and .jdk fields.
5. For each artifact (devrig, jdk):
     target = binaries/<artifact-name>-<os>-<cpu>-<sha>/
     if target exists: continue
     acquire-mkdir-lock(sha)
     if target now exists: release lock; continue
     download url → .tmp.<pid>.<sha>.<ext>
     verify_sha512 (best-effort fallback chain)
     unpack VERBATIM (no --strip) to .tmp.<pid>.<sha>/
     mv .tmp.<pid>.<sha>/ → target/   (atomic; on collision, rm tmp + use winner)
     rm .tmp.<pid>.<sha>.<ext>
     release lock
6. If $DEVRIG_JDK_HOME is set: JAVA_HOME = $DEVRIG_JDK_HOME (skip JDK cache entirely).
   Else: JAVA_HOME = binaries/jdk-<os>-<cpu>-<sha>/<javaHomeSubpath>
                     (empty subpath means the unpack root)
7. exec  binaries/devrig-<os>-<cpu>-<sha>/<binSubpath>  "$@"
```

Three unpack format branches (`zip`, `tar.gz`, `tar.xz`) → stock OS tools
(`unzip`, `tar -xzf`, `tar -xJf` on POSIX; `Expand-Archive`, `tar.exe -xzf`
on Windows 10+). No --strip-components anywhere.

### Best-effort SHA verification + multi-process safety + wrapper self-update

(Unchanged from v6; mkdir-based per-SHA locks with 10 min timeout +
early-exit, atomic mv with loser-cleanup, `.new` rename on next launch.
See git history of this file for the full snippets.)

### Environment

| Var | Behavior |
|---|---|
| `DEVRIG_JDK_HOME` | If set, skip JDK download; set JAVA_HOME to this path |
| `DEVRIG_JAVA_HOME` | Set by the launcher to pin the JDK devrig runs under (its supported runtime). Distinct from `DEVRIG_JDK_HOME` (download opt-out). |
| `DEVRIG_BIN_NO_AUTO_REGISTER` | Gates the on-every-start launcher self-heal + register. `yes/true/1/on` = OFF; `no/false/0/off` = ON. Unset default = ON for release, **OFF for SNAPSHOT/dev/test** builds (so a dev build never clobbers the real launcher). |
| `DEVRIG_OS` | Override platform-detect (testing only) |
| `DEVRIG_CPU` | Override architecture-detect |
| `DEVRIG_DEBUG_NO_EXEC=1` | Stop after cache resolution, print resolved exec path to stderr, exit 45 |
| `MCP_STEROID_FROM_WRAPPER=1` | Set by the wrapper before exec; inner devrig suppresses the headliner |

The home is **not configurable** — always `~/.mcp-steroid`. There is no `DEVRIG_HOME` (or any other) override env var.

## `install` bootstrap mode

Same script doubles as installer. When invoked NOT from
`~/.mcp-steroid/bin/devrig` AND the first arg is `install`:

```
1. mkdir -p ~/.mcp-steroid/{bin,binaries}
2. Fetch https://mcp-steroid.jonnyzzz.com/version.properties → ~/.mcp-steroid/
3. Fetch .signatures + allowed_signers → ~/.mcp-steroid/   (stored; not verified by wrapper)
4. Write the running script's own bytes → ~/.mcp-steroid/bin/devrig (or .cmd)
5. Run standard cache resolution: download devrig + JDK, verify SHAs, unpack into binaries/.
6. exec  ~/.mcp-steroid/binaries/devrig-<os>-<cpu>-<sha>/<binSubpath>  install ${@:2}
```

The exec'd inner devrig runs the agent wizard (next section).

## Agent registration wizard (inner Java)

`devrig install` (no args) — interactive:

```
For each agent in [claude, codex, gemini]:
  1. binaryPath = lookOnPath(agent)
  2. if not found:    stderr "  • claude — not on PATH, skipping";                continue
  3. ok = run([binaryPath, "--version"], timeout = 5s, captureStdout = true)
  4. if !ok:          stderr "  • claude — `claude --version` did not respond";    continue
  5. alreadyRegistered = existing mcpServers["mcp-steroid"] points at our wrapper
     prompt = alreadyRegistered
       ? "claude is already registered. Re-register? [y/N] "
       : "Register mcp-steroid for claude? [Y/n] "
     if user declines: continue
  6. invoke existing claude-mcp-add path (selfMcpCommand + claudeMcpAddStdioArgs --scope user)
     stderr "  ✓ claude — registered (user scope)"
```

`devrig install <agent>` — direct non-interactive (current behavior).
`devrig install --yes` — wizard with no prompts; registers every available agent.

## Agent config shape

POSIX (`~/.claude.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`):

```json
{ "command": "/Users/<u>/.mcp-steroid/bin/devrig", "args": ["mcp"], "env": {} }
```

Windows:

```json
{ "command": "cmd.exe",
  "args": ["/d", "/c", "\"C:\\Users\\<u>\\.mcp-steroid\\bin\\devrig.cmd\" mcp"],
  "env": {} }
```

**No JAVA_HOME** — wrapper sets it from `version.properties` on each launch.

## `devrig upgrade` (inner Java)

```
1. GET https://mcp-steroid.jonnyzzz.com/version.properties
   GET https://mcp-steroid.jonnyzzz.com/version.properties.signatures
2. Read ~/.mcp-steroid/allowed_signers
3. ssh-keygen -Y verify (or programmatic ed25519 verify) — BOTH keys must verify.
4. Compare versions; if not newer → log "up to date", exit 0.
5. write version.properties.new → atomic rename → version.properties
   write .signatures.new → atomic rename
6. For each scripts.<x> in manifest: if remote SHA differs from on-disk, fetch +
   verify + write to bin/devrig.new or .cmd.new. Do NOT touch the running wrappers.
7. Print summary to stderr; exit 0.
```

Single-writer enforced via `mkdir`-lock on `state/upgrade.lock`. Concurrent
`devrig upgrade` calls: the loser sees the lock and exits with "upgrade
already in progress."

`devrig upgrade --check`: dry-run; sigs verified, diff printed; exit 0
(up-to-date) or 100 (upgrade available).

## Automatic cache cleanup (no `prune` subcommand)

The inner Java binary, on every `devrig mcp` startup, sweeps
`binaries/` in a background coroutine:

```
1. List binaries/devrig-*-* and binaries/jdk-*-* directories.
2. Build the "keep set":
   - The currently-resolved (os, cpu, sha) per artifact (from version.properties)
   - The most-recently-modified previous (os, cpu, sha) per artifact, if any
     (the rollback safety net)
3. For every directory NOT in the keep set:
   - try-acquire an exclusive FileChannel lock on <dir>/.lock
   - if locked by another devrig process → skip (still in use)
   - if acquired → close + rm -rf <dir>
4. Log a single summary line to stderr ("[mcp-steroid] cleaned N stale cache dirs").
```

Runs once per session. Per-version `.lock` files (held shared by the
running inner devrig for its lifetime; kernel releases on exit) prevent
deleting a dir another process is actively using.

The cleanup happens automatically; no user-facing `prune` command. Dev-mode
accumulation is therefore self-limiting (each `deployNpx` adds a new sha;
the previous one is kept; older ones are swept on next session start).

## Development mode (`./gradlew deployNpx`)

Dev mode pre-populates the cache so the wrapper does no network at all:

```
1. ./gradlew :npx-kt:installDist → npx-kt/build/install/devrig/
2. devSha = sha512(content-hash of the install tree)
3. devTarget = ~/.mcp-steroid/binaries/devrig-<host-os>-<host-cpu>-<devSha>/
4. if devTarget does not exist:
     mkdir parent; cp -R npx-kt/build/install/devrig → devTarget
5. For JDK:
     if $DEVRIG_JDK_HOME is set in the build env → leave as-is (wrapper picks it up)
     else: download Corretto once (cached at ~/.mcp-steroid/binaries/jdk-...-<sha>/)
6. Write ~/.mcp-steroid/version.properties with:
     - host-platform-only entries (other platforms omitted in dev mode)
     - file:// URL pointing at the local installDist tree
     - real Corretto URL for JDK (or local file:// if pre-downloaded)
7. Write wrapper scripts to ~/.mcp-steroid/bin/
8. NO version.properties.signatures.  NO allowed_signers.
9. Stderr hint: run `~/.mcp-steroid/bin/devrig install` for the agent wizard.
```

`devrig upgrade` detects missing `allowed_signers` → exits with
"upgrade not available in dev mode."

Auto-GC (above) sweeps old dev shas, keeping the most-recent previous one.

## Release-side machinery

(Unchanged from v6 except `version.json` → `version.properties` everywhere.)

- Two ed25519 SSH keys, two operators, two machines.
- `releaseDevrig` task: `:npx-kt:distZip` → manifest gen → both operators
  sign → upload + GitHub release.
- `verify-version-properties-urls` GH Action (weekly): HEAD every URL,
  spot-download + SHA-check, fails CI if any URL is dead or any SHA drifts.

## Test plan

Same suites as v6 (10 suites, every script branch + every Java path +
multi-process race scenarios + cross-OS smoke). Switch parser tests from
JSON to Properties. Add auto-GC tests to inner-Java suite (in addition to
the now-removed prune suite).

## Phasing

| Phase | Scope | LOC |
|---|---|---|
| **1** (must, unblocks dev) | Wrappers (POSIX + PS), `deployNpx` rewrite, Properties manifest schema + parser, JDK download + `DEVRIG_JDK_HOME` opt-out, multi-process locking, `install` bootstrap mode, agent wizard, auto-GC coroutine, `InstallCommand.kt` config-shape change, all-stderr discipline, test suites | ~850 |
| **2** (must before public release) | Key generation, two-key `releaseDevrig`, `devrig upgrade` Java subcommand, GH Pages publish, weekly URL-liveness GH Action, sig-verify test suite | ~400 |
| **3** (nice) | Standalone `install.sh` / `install.ps1` (thin shim that re-runs the main script with `install`), key-rotation docs | ~80 |

---

## Appendix: native binary alternative — research

The user asked whether a small native binary (or AOT-compiled Java) could
replace the shell+PowerShell wrappers. Findings below; recommendation
follows.

### Options surveyed

| Option | Language | Per-platform size | Build complexity | Cross-compile |
|---|---|---|---|---|
| **A** (current) | POSIX sh + PowerShell | 0 (text) | none | n/a |
| **B** | Go | 5–10 MB | `GOOS=… GOARCH=… go build` | trivial — one Linux runner builds all 6 targets |
| **C** | Rust | 2–3 MB stripped | cargo + cross-compile targets | medium — needs cross-toolchains or [`cross`](https://github.com/cross-rs/cross) |
| **D** | GraalVM native-image of npx-kt | 10–20 MB compressed; ~46 MB uncompressed pre-`upx` ([source](https://www.lambrospetrou.com/articles/kotlin-http4k-graalvm-native-and-golang/), [Kotlin Ktor build](https://dev.to/viniciusccarvalho/building-a-native-ktor-application-with-graalvm-1hgh)) | high — reflection config, Ktor's `CIO` engine constraint, native-image build agent per platform | poor — typically must build on the target platform |
| **E** | Java jar + `jpackage` (self-extracting w/ embedded JRE) | 40+ MB per platform | medium | poor — same as D |

### The macOS-curl quarantine finding

For a curl-piped install path, the verdict on macOS is much friendlier than
expected:

> `curl` does **not** set the `com.apple.quarantine` xattr on downloaded
> files. Gatekeeper signature checks are performed only to files with that
> attribute. ([HackTricks — Gatekeeper](https://hacktricks.wiki/en/macos-hardening/macos-security-and-privilege-escalation/macos-security-protections/macos-gatekeeper.html))

So a binary fetched via `curl ... -o ~/.mcp-steroid/bin/devrig` (or `curl |
sh` writing the binary) is NOT subject to Gatekeeper's notarization
requirement. This dissolves the largest deployment objection to native on
macOS — **no Apple Developer enrollment required, no notarization, no
xattr stripping** — as long as the user reaches the binary via curl rather
than via Finder/Safari (which DO set the quarantine attr).

Linux: no signing required, ever.

Windows: SmartScreen warnings target GUI launches (double-click in
Explorer). CLI invocation via `cmd.exe` / `powershell.exe` / agent CLI
spawn is **not** intercepted by SmartScreen. So a curl-installed native
binary launched by Claude/Codex/Gemini is fine; the only friction is if
the user double-clicks the .exe before that, which they won't.

**Net: code-signing burden for a curl-install distribution channel is
effectively zero** across all three OSes.

### curl|sh viability with a native binary

```sh
# shim — one POSIX shell file at https://mcp-steroid.jonnyzzz.com/install.sh
#  - detects (os, cpu)
#  - downloads the matching native installer binary
#  - SHA-checks against a hash baked into THIS file at release time
#  - chmod +x; exec it
```

The shim is ~30 lines of POSIX sh. The hash baked into the shim is
mcp-steroid's signing-key-protected mechanism for first-install
trust — a fresh shim is generated as part of each release, so the
embedded hash always matches the current native installer for that
release.

This is the same pattern used by [`rustup`](https://rustup.rs/),
[`uv`](https://github.com/astral-sh/uv), [`bun`](https://bun.sh/), and
others. Their shims are 30–60 lines. The user experience of `curl ... | sh`
stays identical.

### Tradeoff table

| Concern | Shell (A) | Go (B) | Rust (C) | GraalVM (D) |
|---|---|---|---|---|
| curl\|sh works | direct | shim (~30 lines) | shim | shim |
| Cross-OS reliability | POSIX-shell variance (busybox, dash, bash, zsh) + PowerShell-version variance | uniform | uniform | uniform |
| Build infrastructure | none | one Linux runner | one Linux runner (`cross`) | 6 platform-specific runners |
| Code-signing burden (curl distribution) | none | none | none | none (per the macOS-curl finding) |
| Initial bootstrap download | ~200 KB script | ~5–10 MB binary | ~2–3 MB binary | ~10–20 MB binary |
| Code reuse with npx-kt | none | none | none | **full** (npx-kt grows an `install` subcommand) |
| Upgrade lives in… | wrapper script + npx-kt Java | shim + Go binary OR migrate to npx-kt | shim + Rust binary OR migrate to npx-kt | npx-kt (same binary) |
| LOC | ~400 (POSIX + PS) | ~600 Go | ~600 Rust | ~150 added to npx-kt; minus the 400 shell |
| New-platform support | edit two scripts | rebuild Go for new GOOS/GOARCH | rebuild Rust target | new native-image build agent |

### Recommendation

**Phase 1 ships shell scripts (option A).** The reliability concerns about
POSIX-shell variants and PowerShell are real but addressable by tests, and
~/Work/devrig has already proven the pattern works in the wild. Bootstrap
size is by far the smallest. Build infrastructure cost is zero.

**Future migration to option D (GraalVM native-image of npx-kt) is the
right second step IF reliability issues emerge.** Reasons:

- D maximizes code reuse — the install + upgrade logic lives in one
  Kotlin/Java codebase that's already maintained.
- D is the only option that turns the install + launch + upgrade into one
  single binary (no separate Go/Rust + npx-kt to keep in lockstep).
- The macOS-curl quarantine finding removes the largest deployment blocker.

Option B (Go) is a distant second; would only be preferred if GraalVM's
build-on-target constraint becomes too painful for our CI. Skip C and E.

### What this changes about Phase 1

Nothing. Phase 1 implementation proceeds with shell scripts as designed.
This appendix exists so the decision is on the record and so a future
migration to D has a documented baseline.

### What this changes about Phase 2

Nothing immediately. If we decide to migrate to D, it would be a Phase 4 —
introduce a `:devrig-launcher` Gradle subproject containing JUST the
install + launch + upgrade logic, GraalVM-compile per platform, switch the
agent config recordings to point at the native binary, retire the shell
scripts. Estimated 2–3 weeks of focused work plus ongoing CI maintenance.

The Phase 1 architecture is deliberately designed so this migration is a
swap-out, not a rewrite — the manifest, the cache layout, and the
inner-Java upgrade subcommand all stay the same.

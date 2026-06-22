# TODO — npx bootstrapper, per-user lazy-fetch architecture

> **NOTE (2026-06-18): the `:jdk-downloader` AND `:pgp-verifier` modules referenced below have been REMOVED.**
> `:jdk-downloader` was dead — its only output, the npx-kt `version.json` manifest, was never published or
> consumed (devrig uses Java on PATH; the in-IDE update checker reads the *website* `version.json`).
> `:pgp-verifier` existed solely to PGP-verify those JDK downloads, so it went too — **note: downloaded IDEs
> are verified by SHA-256** (`:intellij-downloader`'s `IdeDownloader`, against JetBrains' published
> checksum), never PGP. The download → PGP-verify → extract pattern survives in git history, and the
> installer epic's `:site-gen` (branch `installer/version-json-driven`) carries the live JDK-pinning. If
> this bootstrapper is built, revive the pattern from there rather than expecting either module to exist.

## 2026-05-28 — Design phase complete; spec at v7 (commit b403efb7)

**Authoritative design:**
[`docs/devrig-deployment-spec.md`](docs/devrig-deployment-spec.md). The spec
was iterated through seven rounds, including a three-agent (claude/codex/
gemini) quorum review via `run-agent.sh` and an MCP-spec-grounded
verdict on mid-session restart. v7 is locked. The older roadmap below
predates the spec and is preserved for context only — when there's a
conflict, the spec wins.

**Key design decisions locked in v7** (everything below this line is now
a spec section, not a TODO):

- Wrapper at `~/.mcp-steroid/bin/devrig` (POSIX shell) +
  `~/.mcp-steroid/bin/devrig.cmd` (Windows CMD — implemented CMD-only in
  PR #117; no PowerShell launcher). The agent config on Windows records
  `cmd.exe /d /c "%USERPROFILE%\.mcp-steroid\bin\devrig.cmd"`.
- Manifest is `~/.mcp-steroid/version.properties` — Java Properties
  format (flat `binaries.<os>-<cpu>.devrig.url=…` keys). Picked over
  JSON/YAML/TOML so POSIX `awk`, PowerShell, and Java's built-in
  `Properties` class all parse it with zero deps.
- Content-addressed cache under `~/.mcp-steroid/binaries/devrig-<os>-<cpu>-<sha>/`
  and `…/jdk-<os>-<cpu>-<sha>/`. Archives deleted after unpack; wrapper
  never re-verifies on launch.
- Bundled Amazon Corretto 25 **JDK** (not JRE — `steroid_execute_code`
  may want `jcmd`/`jstack`). `DEVRIG_JDK_HOME` env opts out for offline
  installs.
- Two ed25519 SSH signatures on `version.properties.signatures`, both
  required, verified ONLY by the inner Java binary during `devrig upgrade`.
  Wrappers never touch sigs.
- **No mid-session restart.** Per MCP spec §lifecycle + observed behavior
  of all three agent CLIs (Claude / Codex / Gemini all mark stdio
  registrations as `failed` on process exit, by design), a stdio session
  ≡ the inner devrig process lifetime. New versions take effect on the
  user's next agent CLI restart. The wrapper does NOT loop on exit code.
- **Automatic cache GC** at every `mcp` startup keeps current + 1
  previous per artifact; per-version `FileChannel` lock prevents deleting
  in-use dirs. **No user-facing prune subcommand.**
- Wrappers self-update via `bin/devrig.new` + rename-on-next-launch (the
  only file op that succeeds against an in-use script on Windows).
- All wrapper output to **stderr only** — stdout is reserved for the
  JVM's MCP protocol traffic after `exec`.
- Agent registration wizard lives in the inner Java binary (probe PATH
  + `<agent> --version` with timeout, prompt to register/re-register).
- `./gradlew deployNpx` pre-populates the cache so dev mode never hits
  the network; signature verification deliberately disabled in dev.
- **Native binary alternative** (Go / Rust / GraalVM native-image of
  npx-kt / jpackage) evaluated in an appendix. Recommendation: ship
  shell scripts in Phase 1; defer GraalVM native-image of npx-kt to a
  future Phase 4 if shell-script reliability proves insufficient. Key
  finding: `curl` does NOT set `com.apple.quarantine` xattr on macOS,
  so a curl-installed native binary would not need notarization.

**Implementation phasing** (from the spec):

| Phase | Scope | LOC |
|---|---|---|
| **1** (unblocks dev) | Wrappers (POSIX + PS), `deployNpx` rewrite with cache pre-population, Properties manifest schema + parser, JDK download + `DEVRIG_JDK_HOME` opt-out, multi-process locking, `install` bootstrap mode, agent wizard, auto-GC coroutine, `InstallCommand.kt` config-shape change, all-stderr discipline, full test suites | ~850 |
| **2** (before public release) | Key generation playbook, two-key `releaseDevrig`, `devrig upgrade` Java subcommand, GH Pages publish, weekly URL-liveness GH Action, sig-verify test suite | ~400 |
| **3** (nice) | Standalone `install.sh` / `install.ps1` shims, key-rotation docs | ~80 |

**Status: design done, implementation pending user go-ahead.**

---

## Older roadmap (predates the v7 spec; kept for context)

## Current status after devrig rename (2026-05-19)

> **Contract reference.** The devrig CLI's project/backend naming,
> JSON output schemas, and on-demand routing model are now specified
> in [`docs/devrig-naming.md`](docs/devrig-naming.md). Any
> bootstrapper work that touches discovery, MCP routing, or the
> public CLI surface must keep that contract intact. The on-demand
> rebuild decision (no background scanners in the devrig process) is
> recorded in
> [`docs/devrig-scanning-research.md`](docs/devrig-scanning-research.md).

This plan predates the devrig rename and the removal of the npm TypeScript
MCP proxy. Use these facts before implementing anything from the older notes
below:

- The npm package is now named `devrig`, not `mcp-steroid-proxy`.
- The npm `:npx` module currently contains only a thin TypeScript launcher
  stub. It does not implement MCP, marker discovery, update checks, beaconing,
  registry lookup, or traffic logging.
- The Kotlin distribution still comes from Gradle module `:npx-kt`, but the
  application name and launcher are `devrig` (`bin/devrig`,
  `bin/devrig.bat`).
- The `:npx-kt` distZip no longer bundles a JVM. It ships the devrig jars, the
  MCP Steroid plugin zip, 7-Zip helpers, and license material; the generated
  launcher still expects a pre-installed Java 21 runtime via `JAVA_HOME` or
  `java` on `PATH`.
- Active Kotlin sources are under
  `com.jonnyzzz.mcpSteroid.devrig`; the old `...proxy` package and the attic
  implementation are gone.
- The bridge protocol names remain `Npx*` and `/npx/v1/*` for compatibility
  with the IDE plugin. Do not rename those protocol DTOs/routes as part of a
  bootstrapper change unless there is a separate compatibility plan.
- A future npm bootstrapper should launch the Kotlin `devrig` binary. The
  temporary stub contract is `DEVRIG_KOTLIN_LAUNCHER=<path-to-devrig> npx
  devrig ...`.

Status: **planned, not started.** The current `:npx-kt` distZip
requires a pre-installed JVM. The bootstrapper is the planned place for
runtime JDK acquisition.

This document captures the plan to replace the current JVM-dependent
npm payload with a tiny JavaScript bootstrap + a per-OS lazy fetch
from a remote feed.
**No work has started.** The `:jdk-downloader` module already owns the
pinned Amazon Corretto metadata used by `version.json`; the runtime
bootstrap still needs to consume it.

## Explicit non-solution: `gradle-jvm-wrapper`

`https://github.com/mfilippov/gradle-jvm-wrapper` was reviewed on
2026-05-24 and should **not** be applied to `:npx-kt`.

Reasons:

- The plugin patches Gradle's `wrapper` task output (`gradlew` /
  `gradlew.bat`). It does not patch the `application` plugin launchers
  under `bin/devrig`, which are the scripts end users run.
- Its injected script downloads/extracts the configured JVM into its
  own install directory and then exports `JAVA_HOME` before the normal
  Gradle wrapper Java lookup runs. That is good for making `gradlew`
  hermetic, but it does not preserve the pre-installed-JDK behaviour we
  want for the devrig launcher.
- The devrig MCP runtime must keep stdout clean for MCP frames. Any runtime
  JDK acquisition should live in the npm/bootstrap layer or a
  purpose-built generated application launcher where stderr/progress
  routing is controlled deliberately.

Keep the JDK cache under `~/.mcp-steroid/jdk/...` when the bootstrap is
implemented, and reuse the Amazon Corretto URLs/checksums produced by
`:jdk-downloader` instead of duplicating JDK coordinates.

## Goal

Replace the one-step `npx devrig` (requires Java to be
pre-installed today) with a two-tier path:

1. `npx devrig` resolves a **tiny** JavaScript wrapper (~kilobytes,
   not megabytes).
2. The wrapper fetches a small `version.json` manifest from
   `https://mcp-steroid.jonnyzzz.com/version.json`.
3. The wrapper resolves the **right** payload for the current OS /
   CPU only, downloads each component to `~/.mcp-steroid/<package>/<version>/`,
   and re-uses cached copies on every subsequent run.
4. The wrapper sets `DEVRIG_JAVA_HOME=~/.mcp-steroid/jdk/<version>/`
   (per-OS Corretto picked at fetch time) and `exec`s into the
   `:npx-kt` launcher with the rest of the args.

Expected per-user compressed payload after this lands: **~150–250 MB**
(one JDK, ~150 MB after good compression, + 80 MB plugin + a few MB
for the launcher).

## Inspiration: `~/work/devrig/`

The `devrig.dev` project at `~/work/devrig/` already implements this
pattern in Go for the IDE-wrapper case. Key files to study before
implementing:

- `~/work/devrig/devrig` — POSIX shell launcher. Detects `DEVRIG_OS`
  / `DEVRIG_CPU` from `uname -s` / `uname -m`, resolves cache root
  `DEVRIG_HOME`, dispatches into the Go binary.
- `~/work/devrig/cli/` — Go CLI that downloads pre-configured IDEs
  into `.devrig/`. Has `feed`, `feed_api`, `install`, `layout`,
  `configservice` modules — direct analogues of what `mcp-steroid`'s
  bootstrap would need (manifest fetch, sha verify, extract, layout).
- `~/work/devrig/cli/bootstrap` — the actual fetch + extract code.
- `~/work/devrig/cli/feed` — manifest schema + parser.

The differences:
- `devrig` is Go; ours would be JS (npm-native, no `gem` / `go install`
  prerequisite).
- `devrig` fetches IDEs; ours fetches a JDK + a Kotlin runtime + a
  plugin bundle.
- `devrig` uses a YAML manifest (`.idew.yaml`); ours would use JSON
  (simpler for JS, no extra dep).

## Component split

Historical installDist pressure, and the target shape for downloadable
components:

| Path                           | Size  | After split                       |
|---|---|---|
| `jdk/jdk-linux-amd64/`         | 348M  | One per OS, fetched lazily        |
| `jdk/jdk-linux-arm/`           | 348M  | (same)                            |
| `jdk/jdk-mac-arm/`             | 336M  | (same)                            |
| `jdk/jdk-windows-amd64/`       | 329M  | (same)                            |
| `ij-plugin/`                   | 226M  | Fetched once, OS-independent      |
| `lib/`                         |  31M  | Stays inside the npx-kt package   |
| `7z/`                          |  10M  | Future: Windows-only payload      |
| `licenses/`                    |   8M  | Stays                             |
| `bin/`                         |  20K  | Stays                             |
| `EULA`                         |  16K  | Stays                             |

Bundled in the **npm package** (the wrapper's payload):
- The JS bootstrap entry point (~kB).
- `lib/` (the npx-kt jars — needed to launch anything).
- `bin/devrig` launcher script (already exists).
- `EULA`.
- `licenses/` (legal must travel with the binary).

Fetched lazily into `~/.mcp-steroid/<package>/<version>/`:
- **`jdk/<os>/`** — exactly one per user. ~330 MB extracted, ~150 MB
  compressed.
- **`ij-plugin/mcp-steroid/`** — OS-independent. ~226 MB extracted.
- **`7z/win-x64/`** — only on Windows hosts after Task #18 lands.

## Cache layout

```
~/.mcp-steroid/
├── jdk/
│   └── corretto-21.0.11.10.1-linux-amd64/
│       ├── bin/, conf/, lib/, legal/, release, version.txt
│       └── .verified   ← marker file written after a successful
│                          fetch + extract + permission-audit
├── ij-plugin/
│   └── 0.95.0/                   ← matches the plugin version in the manifest
│       └── mcp-steroid/...
├── 7z/                            (only on Windows)
│   └── 23.01-win-x64/
│       └── 7z.exe, 7z.dll, License.txt
└── manifest/
    └── 2026-05-15T12:34:56Z.json  ← cached copy of the version.json
                                     that was current at fetch time
```

Versions are encoded **in the directory name** so re-fetching a new
version doesn't trample the old one (an operator pinning to an
older version stays working). The bootstrap reads the manifest to
pick which subdir to use.

## `version.json` manifest shape

Published at `https://mcp-steroid.jonnyzzz.com/version.json`. The
file is small (a few KB), updated whenever a new release is cut.

```json
{
  "schema": 1,
  "publishedAt": "2026-05-15T12:34:56Z",
  "channel": "stable",
  "packages": {
    "jdk": {
      "version": "corretto-21.0.11.10.1",
      "platforms": {
        "linux-amd64": {
          "url": "https://corretto.aws/downloads/resources/21.0.11.10.1/amazon-corretto-21.0.11.10.1-linux-x64.tar.gz",
          "sha256": "<hex>",
          "signatureUrl": "https://corretto.aws/downloads/resources/21.0.11.10.1/amazon-corretto-21.0.11.10.1-linux-x64.tar.gz.sig",
          "publicKeyUrl": "https://corretto.aws/downloads/resources/CorrettoPublicKey",
          "extractStrip": 1
        },
        "linux-arm":     { "...": "..." },
        "mac-arm":       { "...": "..." },
        "windows-amd64": { "...": "..." }
      }
    },
    "ij-plugin": {
      "version": "0.95.0-b14969e1",
      "url": "https://mcp-steroid.jonnyzzz.com/releases/0.95.0/mcp-steroid-0.95.0.zip",
      "sha256": "<hex>",
      "publishedAt": "2026-05-12T14:22:18Z"
    },
    "7zip": {
      "version": "23.01",
      "platforms": {
        "windows-amd64": {
          "url": "https://www.7-zip.org/a/7z2301-x64.exe",
          "sha256": "<hex>",
          "extract": "7z-installer-strip"   ← extractor flavour
        }
      }
    },
    "npx-kt": {
      "version": "0.95.0-b14969e1",
      "url": "https://mcp-steroid.jonnyzzz.com/releases/0.95.0/npx-kt-0.95.0-minimal.zip",
      "sha256": "<hex>"
    }
  }
}
```

Notes:
- **Per-package version key** — each component versions independently
  in the cache layout. The plugin can ship a patch without forcing a
  JDK re-download.
- **`platforms` map** — only the JDK and (eventually) 7-Zip vary by
  OS. Plugin and npx-kt are OS-independent (pure JVM bytecode).
- **`sha256` is mandatory** — the bootstrap verifies every download.
- **`signatureUrl` / `publicKeyUrl` optional** — JDKs get PGP verify
  via the existing `:pgp-verifier` CLI (run from the cache). Other
  packages stop at SHA-256.
- **`extract` discriminator** — most packages are tarball / zip;
  7-Zip on Windows is an NSIS installer that needs the bootstrap to
  invoke an existing `7z.exe` against it (chicken/egg: the
  bootstrap ships a small `7zr.exe` for the initial extract; once
  unpacked, future extracts use the cached `7z.exe + 7z.dll`).
- **`schema: 1`** — version the manifest; the bootstrap refuses
  unknown schemas with a clear "upgrade your wrapper" message.

## Bootstrap algorithm

Pseudocode, JS:

```
1. Detect OS + CPU:
     os  = process.platform     // 'linux' | 'darwin' | 'win32'
     cpu = process.arch         // 'x64' | 'arm64'
     platform = mapToInternal(os, cpu)  // 'linux-amd64' | ... | 'windows-amd64'

2. Resolve cache root: ~/.mcp-steroid/.

3. Fetch manifest:
     if (mtime(manifest cache) < 24h ago) {
         try {
             download version.json into manifest/<timestamp>.json
             use that
         } catch (offline) {
             use the most-recent cached manifest
         }
     } else {
         use the most-recent cached manifest
     }

4. For each required package in (jdk, ij-plugin, npx-kt, 7zip-if-windows):
     pkg = manifest.packages[name]
     entry = pkg.platforms?.[platform] ?? pkg   // fallback for OS-independent
     dest = ~/.mcp-steroid/<name>/<pkg.version>-<platform-if-applicable>/
     if (!dest/.verified) {
         fetch entry.url -> dest/.tmp/<archive>
         verify entry.sha256
         if (entry.signatureUrl) verify PGP via cached :pgp-verifier
         extract into dest
         apply permission preservation (chmod +x bin/*, etc.)
         touch dest/.verified
     }

5. Set environment:
     DEVRIG_JAVA_HOME=<jdk cache dir>
     DEVRIG_MCP_STEROID_HOME=<root of cache>
     PATH=<jdk cache>/bin:$PATH   (optional convenience)

6. Locate the launcher in the npx-kt cache:
     <npx-kt cache>/bin/devrig (or .bat on Windows)

7. Exec into the launcher with the remaining argv. The launcher
   reads DEVRIG_JAVA_HOME, locates its own jars, and runs.
```

## Implementation phases (when this becomes work)

Sized so each commit is reviewable on its own.

1. **Keep the npx-kt distribution JVM-free.**
   Do not add a Gradle-wrapper-level JVM bootstrap. The generated
   launcher continues to use a pre-installed JDK until the npm
   bootstrap owns JDK acquisition.

2. **Carve the npx-kt distribution into "core" + "downloadable" zips.**
   The Gradle build emits two artifacts:
   - `npx-kt-<version>-minimal.zip` — just `bin/` + `lib/` + `EULA`
     + `licenses/`. ~40 MB.
   - `npx-kt-<version>-jdk-<platform>.zip` x 4 — per-platform JDK
     payload. ~150 MB compressed each.
   The existing `distZip` stays as the direct-download/offline path
   until the bootstrap is live.

3. **Add the manifest generator.** A new Gradle task in `:npx-kt` (or
   a tiny new module `:bootstrap-manifest`) emits `version.json`
   alongside the zip artifacts. SHA-256 computed from each artifact
   at the same time. URLs are placeholders during local builds; CI
   substitutes the real CDN base URL.

4. **Set up the publish target.** `mcp-steroid.jonnyzzz.com` hosts
   the manifest + the per-component zips. Today the GitHub Pages
   site exists under `website/`; the bootstrap fetches from a CDN
   subdomain (or the same site under `/releases/`).

5. **Create `:npx-bootstrap` (the JS package).** Pure JavaScript,
   `package.json` with one `bin` entry, ~200 lines:
   - Fetch + cache manifest.
   - Per-package fetch + SHA-256 verify.
   - Per-JDK PGP verify by spawning the cached `:pgp-verifier`
     binary.
   - Extract (use `tar` + Node's built-in zlib; bring in `extract-zip`
     for `.zip`; punt on `.7z` until the Windows-7z installer needs
     it).
   - Cross-platform `chmod +x` after extract.
   - Set env + `spawnSync` into the launcher.

6. **Wire CI to publish on release.** Existing GitHub Actions builds
   the zips; new workflow uploads them to the CDN and refreshes
   `version.json`. Publish must be atomic — the manifest update
   happens last so a fetch during the publish window either gets the
   old manifest pointing at old zips or the new one pointing at new
   zips. (Two-stage publish: stage the zips at a versioned URL,
   atomically rename the manifest pointing to them.)

7. **Replace the current distZip in the npm release.** The npm
   `package.json`'s `bin` becomes `:npx-bootstrap`'s launcher; the
   full JVM distribution is no longer published as an npm payload.
   Operators who still want the offline/direct-download package can
   download it directly from the website.

8. **Permanent: dist size monitor.** A CI check that fails if the
   npx-bootstrap npm package crosses some small threshold (1 MB?).
   Keeps the bootstrap honest.

## Open questions to resolve before writing code

- **CDN host.** GitHub Pages serves the static site under
  `jonnyzzz.github.io/mcp-steroid` today. CloudFront in front of S3
  would handle the multi-hundred-MB zip distribution better. Pick
  one before the publish step lands.
- **Signing.** The npm package itself can ship signed via npm's
  built-in provenance; the downloaded zips need their own
  `signatureUrl` (we already proved PGP works in `:pgp-verifier`).
  Decide whether non-JDK zips also get PGP signatures or only SHA-256.
- **Offline behaviour.** First run always needs network. Subsequent
  runs should work offline as long as the cache is populated and the
  cached manifest hasn't expired. Pick a reasonable cache TTL (24 h?
  7 d?).
- **`DEVRIG_*` vs `MCP_STEROID_*` env namespace.** Standardise the
  prefix before scattering it through the codebase. `DEVRIG_JAVA_HOME`
  was proposed by analogy with `devrig.dev`; if mcp-steroid stays a
  separate brand from devrig, `MCP_STEROID_JAVA_HOME` is the safer
  name. Probably aliased: read `DEVRIG_JAVA_HOME` if set, else
  `MCP_STEROID_JAVA_HOME`.
- **Cache eviction.** No plan today. Each upgrade keeps the old
  version cached; over years that compounds. A `mcp-steroid prune`
  subcommand or a "keep last 2 versions" rule is a separate small
  task once the bootstrap is in.
- **`:pgp-verifier` distribution.** The verifier itself needs a JRE
  to run, and the JDK we'd verify hasn't been fetched yet —
  chicken/egg. Options: (a) ship a tiny statically-linked Go
  verifier as part of the npm bootstrap; (b) ship the pgp-verifier
  jar AND a minimal JRE inside the npm bootstrap (defeats the
  shrinking purpose); (c) skip PGP for the bootstrap-driven path and
  rely on SHA-256 + HTTPS only. Picking between (a) and (c) is the
  most architecturally consequential open question.

## References

- `~/work/devrig/` — Go implementation of essentially this pattern
  for the IDE-wrapper case.
- `~/work/devrig/cli/feed/` — manifest schema + parser.
- `~/work/devrig/cli/install/` — fetch + extract pipeline.
- This repo's `:pgp-verifier` (commits `82b3e788` / `793d7208` /
  `130e472a`) — the verify-by-CLI shape we'd reuse.
- This repo's `:intellij-downloader/SevenZipLocator.kt` — the cache
  pattern (`~/.cache/mcp-steroid/7z/<sha>/`).
- This repo's `:jdk-downloader/build.gradle.kts` — the
  download-verify-extract pipeline in Gradle form; the bootstrap
  re-implements the same flow in JS.
- `gradle-jvm-wrapper` — reviewed and rejected for `:npx-kt`; it is a
  Gradle wrapper patcher, not a runtime launcher bootstrapper.

## Non-goals

- No replacement for the standalone `:npx-kt` distZip. Operators who
  prefer the offline/direct-download package should still be able to
  download it.
- No GUI installer. CLI-only.
- No automatic IDE provisioning (the bootstrap fetches the proxy and
  its JDK; the proxy's own `backend` subcommand handles IDE
  install).

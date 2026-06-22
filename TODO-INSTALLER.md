# TODO — Installer epic (PR #113) status & remaining scope

Running plan for the installer epic. The epic was deliberately peeled into focused PRs off `main`
instead of merging the big `installer/version-json-driven` branch wholesale. This file tracks what has
landed and what is **still only on #113**.

## Landed on `main` (extracted from #113, in better form)

- **devrig launcher ownership** — `~/.mcp-steroid/bin/devrig` self-heal + PATH + wrapper registration
  (#117/#118).
- **Website generator** — `:website-gen` `WebsiteArtifacts` (version.json + updatePlugins.xml), Kotlin,
  Ktor (#119); GitHub Action runs it main-only (#120).
- **Dead-module cleanup** — removed `:jdk-downloader` + `:pgp-verifier` (#121); IDE downloads confirmed
  SHA-256-based.
- **JDK data model + cache + PGP** — `:website-gen` `resolveAllJdks` → `JdkModel`, on-disk/in-memory
  `Cache`, BouncyCastle `PgpVerifier` with **pinned** vendor fingerprints (#122). Validated live (8
  artifacts). See [website-gen/CLAUDE.md](website-gen/CLAUDE.md).

## Still only on `installer/version-json-driven` (the remaining deliverable)

The **installer-script generator** — everything else from #113 is now superseded by `main`:

| #113 file | Disposition |
|---|---|
| `site-gen/.../installer/InstallerGenerator.kt` | **PORT** — generates `install.sh` / `install.ps1` from JDK + devrig coordinates |
| `site-gen/src/main/resources/templates/install.{sh,ps1}.tmpl` | **PORT** — the script templates (the actual installer logic) |
| `site-gen/.../installer/CoordinateResolver.kt` (+ `CoordinateResolverTest`) | **REPLACE** with `:website-gen` `resolveAllJdks` `JdkModel` + a thin adapter |
| `site-gen/.../installer/site/SiteArtifacts.kt` (+ test) | **DROP** — superseded by `main`'s `WebsiteArtifacts` |
| `site-gen/.../installer/tests/InstallerBootstrapTest.kt` | **PORT** — nginx sidecar, real HTTP, sha256-verify, content-addressed dirs, idempotency |
| `site-gen/.../installer/tests/InstallerRealArtifactsTest.kt` | **PORT/ADAPT** — re-derives sha/javaHome from a downloaded file (see decision D3) |
| `site-gen/.../installer/tests/JdkCoordinatesMetadataTest.kt` | **ADAPT** — asserts the platform set (see decision D2) |
| old `:pgp-verifier`, `:jdk-downloader`, `:site-gen` build wiring | **DROP** — superseded by `:website-gen` |

### The adapter (`JdkModel` → installer-script fields)

`JdkArtifact` is information-complete; the generator needs a thin translation layer:
- platform key: `JdkOs/JdkArch` enums → `<os>-<cpu>` string with **`AARCH64`→`arm64`** (matches the
  scripts' `uname -m` normalization);
- archive: `ArchiveType` → already carries `.extension` (`tar.gz`/`zip`);
- `fileName`, `sha256`, `url`, `javaHome` map 1:1 (javaHome is forward-slash, no leading slash — matches
  `JdkCoordinatesMetadataTest`'s assertion).

## Decisions (resolved 2026-06-18)

- **D1 — module layout**: NEW `:installer-gen` module is the **lower-level** module — it owns **JDK
  detection** (move `JdkArtifacts`/`JdkModel`/`resolveAllJdks`, `PgpVerifier`, `Cache` + `HttpFetcher`
  out of `:website-gen`) **and** installer-script generation. **`:website-gen` depends on
  `:installer-gen`** and reuses its HTTP/cache infra. (Reverses the originally-assumed direction.)
- **D2 — platform coverage: NO alpine.** IDEs can't run on musl/alpine anyway, so we do NOT ship alpine
  JDKs. Drop the 2 alpine entries (and macos-x64) from the model → back to the **5** platforms #113 had
  (`macos-arm64`, `linux-arm64`, `linux-x64`, `windows-x64`, `windows-arm64`). Instead, **install.sh
  detects musl and fails fast** with a clear "not supported" message.
- **D3 — `InstallerRealArtifactsTest`: trust the model.** It already PGP-verifies + computes
  sha256/javaHome, and `InstallerBootstrapTest` does the real-HTTP+sha end-to-end check. Assert the
  model directly; no re-download.

## Execution plan for the `:installer-gen` PR — DONE (PR #124)

1. ✅ New `:installer-gen` module owns JDK detection (split `JdkModel`/`CorrettoJdk`/`AzulJdk`) + shared
   HTTP/cache; package `com.jonnyzzz.mcpSteroid.installer`; trimmed to the 5 platforms (no alpine, no
   macos-x64; `ALPINE_LINUX` removed).
2. ✅ `:website-gen` depends on `:installer-gen` and reuses `KtorHttpFetcher`; `generateJdkModel` moved.
3. ✅ Generator ported: `InstallerGenerator` adapts the `JdkModel` (no `--jdk` args), `install.sh` detects
   musl and fails fast, `generateInstaller` task + Makefile wiring. Live-verified.
4. ✅ `InstallerBootstrapTest` (minimal ubuntu/glibc lane) ported into an `installerIntegrationTest`
   source set, reworked to drive `writeInstallerScripts` with a synthetic model + nginx-served fixtures
   (no real JDK download). Passes end-to-end; joined the serialized `ciIntegrationTests` chain. The
   metadata/platform-set + render contract are covered by the hermetic unit tests (so the #113
   `JdkCoordinatesMetadataTest` / `InstallerRealArtifactsTest` did not need separate ports — D3:
   trust the model).

## Minor, non-blocking (from the #122 quorum review)

- Azul `latest` selection: trust `latest=true` + assert a single result, vs. the current sort-and-take.
- Fold `UrlKey.resolvedUrl` into the cache key (PGP currently backstops the gap).

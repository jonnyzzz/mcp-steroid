# installer-gen/CLAUDE.md

Guidance for the `:installer-gen` module. **Instructions here override the root guide for this folder.**
Read the [root CLAUDE.md](../CLAUDE.md) too (project-wide rules).

## What this module is

`:installer-gen` is **build-tooling** (no IntelliJ deps). It is the **lower** of the two distribution
generators: `:website-gen` depends on it. Two responsibilities:

1. **JDK detection** — `resolveAllJdks(cache)` (in `JdkModel.kt`) produces a `JdkModel` of the JDK 25
   builds the installer ships, each field **computed** from the live vendor sources (nothing hand-pinned).
   The vendor logic is split per file: `CorrettoJdk.kt` (Amazon Corretto) and `AzulJdk.kt` (Azul Zulu).
2. **Installer-script generation** — `InstallerGenerator` + the `install.sh` / `install.ps1` templates
   (consumes the `JdkModel`). *(Ported from PR #113.)*

The shared HTTP/cache infra (`Cache.kt`, `HttpFetcher`/`KtorHttpFetcher`) lives here too, so `:website-gen`
reuses it.

## JDK data model — the rules

- **Exactly 5 platforms** the installer supports: Amazon Corretto 25 for `linux-x64`, `linux-arm64`,
  `macos-arm64`, `windows-x64`; Azul Zulu 25 for `windows-arm64` (via the Azul Metadata API). **Latest 25
  is resolved live** — no version pin in source.
- **NO alpine / musl.** The IntelliJ IDEs require glibc, so we do not ship musl builds — `install.sh`
  detects musl and **fails fast**. There is no `ALPINE_LINUX` `JdkOs`. (Also no `macos-x64` — Apple-silicon
  only.)
- **`JdkArtifact` is self-sufficient for the script generator**: `platform`, `vendor`, `version`
  (vendor-native, NOT cross-comparable), `featureVersion` (Int, comparable), `archive` (+
  `ArchiveType.extension`), `url` (version-pinned), `fileName`, `size`, `sha256` (lowercase hex),
  `javaHome`. **`javaHome` is always archive-relative, forward-slash, no leading/trailing slash** —
  computed by scanning entries for the **shallowest** `bin/java[.exe]` (nested `jre/bin/java`-proof).
- **Vendor-natural validation is mandatory and fail-fast** (`PgpVerifier`, BouncyCastle): both vendors
  publish detached OpenPGP signatures (Corretto `<file>.sig`, Azul Metadata-API `signature-binary`). The
  signing-key **fingerprint is pinned in source** (`CORRETTO_KEY_FINGERPRINT` in `CorrettoJdk.kt`,
  `AZUL_KEY_FINGERPRINT` in `AzulJdk.kt`) and asserted before trusting the signature — the key is fetched
  live over HTTPS, so the pin defeats a compromised key endpoint. PGP runs on **every** resolve, incl.
  cache hits. Fingerprints are injectable into `resolveAllJdks` so tests can pin a generated test key.
  Vendor endpoints/keys are catalogued in the `jdk-feed-vendor-endpoints` memory.

## Caching (`Cache.kt`)

- `Cache.onDisk(root)` (one file per key, atomic temp+move) / `Cache.inMemory()` (for tests) behind a
  static factory. `getOrCompute<T>` uses kotlinx.serialization for `T`.
- **`downloadWithEtag`** — HEAD `ETag` cache; **fails fast if the host exposes no ETag** (Corretto).
- **`downloadVerifyingSha256`** — content-addressed by a vendor-published sha256 (Azul, whose CDN exposes
  no HEAD ETag); re-hashes on **every** return incl. cache hits (the cache dir is shared).
- **The cache root must live OUTSIDE any `build/` folder** and **its path is passed in from Gradle**
  (`--cache-dir`), never guessed. `generateJdkModel` roots it at `gradleUserHome/caches/mcp-steroid/…`.

## Running / testing

- Unit tests are **hermetic** (synthetic tar.gz/zip via `TestArchives`, real BouncyCastle signing via
  `TestPgp`, in-memory cache + a fake `HttpFetcher`) — no network. Run: `./gradlew :installer-gen:test`.
  Covered by `ciBuildPluginTests` (auto-swept; guarded in root `build.gradle.kts`).
- The Docker installer integration tests live in a **separate `installerIntegrationTest` source set** —
  NOT in `ciBuildPluginTests`. *(Ported from #113.)*
- `generateJdkModel` hits the network; the first resolve downloads ~1 GB (cached after; re-runs ~15 s).
  Don't run it in the unit-test loop. `KtorHttpFetcher` is `AutoCloseable` — `use {}` it.
- Deps beyond the repo norm: `org.bouncycastle:bcpg-jdk18on` (PGP), `org.apache.commons:commons-compress`
  (archive scanning). `generateJdkModel` sets `maxHeapSize = "2g"` (one ~230 MB `ByteArray` at a time).

## Gotchas

- `version` means different things per vendor (`25.0.3.9.1` vs `25.0.3`) — never compare across vendors;
  use `featureVersion`.

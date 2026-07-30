# Deploying & updating the MCP Steroid plugin into *running* IDEs — research record + spec

**Status:** research complete, design proposed, **not yet implemented**. Awaiting sign-off.
**Date:** 2026-07-30. **Branch:** `worktree-ide-rest-plugin-deploy`.
**Validation:** live-tested against 4 running IDEs (§2) **and peer-reviewed by codex over two rounds**
(§9): round 1 refuted the first draft's "live dynamic self-swap" recommendation; round 2 refuted the
v2 draft's claim that the update paths used only stable public APIs and tightened the version gate.
This doc is the corrected v3. **Bottom line: there is no fully-public SDK API for programmatically
staging a local plugin ZIP into a *running* IDE — every automated path here touches de-facto/internal
platform surfaces, so the implementation must pin baselines + guard with tests, and the only
fully-stable behavior is the *closed-IDE* file-drop / headless path.**
**Scope owner:** `docs/` (long-form contract doc, sibling to
[`devrig-deployment-spec.md`](devrig-deployment-spec.md) and
[`native-mcp-tools-design.md`](native-mcp-tools-design.md)).

Related: [`intellij-builtin-servers.md`](intellij-builtin-servers.md) (both IDE HTTP servers),
[`updates-check/devrig-auto-update.md`](updates-check/devrig-auto-update.md) (devrig's *own*
self-update — the pattern this doc mirrors for the *plugin*).

---

## 1. The problem

devrig ships a **bundled** copy of the MCP Steroid plugin (`ij-plugin.zip`, resolved via
`DevrigRoot.ijPluginZip()` / `ClasspathBundledPluginResolver.resolveBundledPluginZip()`).
Today devrig only deploys that bundle into IDEs **it manages itself** (downloaded/started by
`devrig backend`) via `ManagedBackend.deployMcpSteroidPlugin()` — a file copy into a managed
plugins dir before the IDE is first started.

For an IDE the **user already has open** (their daily-driver IntelliJ/WebStorm/GoLand/…), devrig
today does nothing but *print instructions* (`BackendProvisionCommand` emits "(a) marketplace… (b)
manual file install…" text). There is no path to:

1. **install** the plugin into a running IDE the user opened themselves, and
2. **update** an already-installed plugin when devrig bundles (or devrig.dev advertises) a newer one.

The user's ask: detect IDEs locally on the standard IntelliJ ports, then deploy/update the plugin —
**preferring devrig's bundled artifact + a version check** — with a **smooth, no-surprises UX**.

### Non-goals

- Replacing Marketplace distribution. The plugin stays published (id `com.jonnyzzz.mcp-steroid`,
  Marketplace #30019); this is an *additional* local channel.
- Managing IDEs devrig already owns (that is `ManagedBackend`'s job and already works).
- Enterprise mass-deployment (IDE Services / Toolbox Enterprise already solve that).

### TL;DR of the recommendation (after validation)

| Situation | Recommended path | Restart? | Surprise? | API stability |
|---|---|---|---|---|
| Plugin **absent**, IDE **running** | REST `action=install` (native Marketplace dialog) | no (not guaranteed) | user approves in-IDE dialog (native) | de-facto endpoint (stable in practice) |
| Plugin **absent/older**, IDE **closed** | file-drop of the bundle / headless `installPlugins` | applied next start | none | **fully stable** (filesystem / documented CLI) |
| Plugin **present**, **older** than bundle, IDE running | stage the bundle for next launch (copy to a **disposable** staging file first) + "restart to apply" notice | **yes, user-timed** | none until user restarts | **internal** staging API — pin+guard |
| Plugin **present**, want IDE to self-update (**release builds only**) | persist `devrig.dev/updatePlugins.xml` in `UpdateSettings.storedPluginHosts` → IDE's own update flow (best-effort live, else next start) | IDE-managed | native update notification | internal config API |
| Live no-restart hot-swap from devrig | **ruled out** — the bridge runs *inside* the plugin; it cannot unload its own classloader (§2.1, §9) | — | — | — |

> The two "IDE running" *update* rows require mutating in-IDE operations for which no public SDK API
> exists (§2.5, §9-r2). Where fully-stable behavior matters more than immediacy, prefer closing the
> IDE and using the file-drop / headless row (all-public, all-stable).

---

## 2. What was validated live (2026-07-30)

Everything below was verified against **running IDEs on this machine** (and, for the API-mechanics
claims, cross-checked in the IntelliJ 261 source at `origin/261` and by reflection in the live 261
runtime — see §9). Live fleet at test time (from `~/.mcp-steroid/markers/*.mcp-steroid`):

| IDE | Built-in port | Plugin version | Note |
|---|---|---|---|
| IntelliJ IDEA 2026.1 (`IU-261.22158.277`) | 64463 | `0.101.19999-SNAPSHOT-9f54111f` | dev SNAPSHOT — **port outside devrig's scan ranges** (§2.6) |
| WebStorm 2026.1.4 (`WS-261.26222.58`) | 63342 | `0.101.19999-SNAPSHOT-f969ad18` | dev SNAPSHOT |
| IntelliJ IDEA 2026.1.3 (`IU-261.25134.95`) | 63343 | `0.101.19999-SNAPSHOT-f969ad18` | dev SNAPSHOT |
| **GoLand 2026.1.4 (`GO-261.26222.72`)** | 63344 | **`0.101-40690055`** | **published Marketplace build** |

GoLand is the realistic "user already has the released plugin; is there a newer one?" case.

### 2.1 The plugin descriptor is *dynamic-eligible* — but that does NOT make a live self-swap safe ⭐

In-process, against the running GoLand:

```
version: 0.101-40690055     enabled: true
descriptor class: com.intellij.ide.plugins.PluginMainDescriptor
isRequireRestart: false
checkCanUnloadWithoutRestart(descriptor)  ->  null      # descriptor-level: eligible to unload
allowLoadUnloadWithoutRestart(descriptor) ->  true       # just re-runs the same check
```

This confirms *descriptor-level* eligibility only. It does **not** guarantee an actual live swap,
and — critically — **devrig must not attempt one for the update case**, because:

- The devrig↔IDE transport is the plugin's **own** Ktor bridge. Any update script devrig sends runs
  **inside the mcp-steroid plugin's classloader**. Replacing the plugin means unloading that
  classloader while a frame that belongs to it is on the stack.
- `PluginDownloader.installDynamically` / `DynamicPlugins.unloadPlugin` unload with a
  wait-for-classloader-GC step (verified in 261 source, §9). The still-running bridge retains the
  old classloader, so GC cannot complete → the unload stalls/fails. A "detached" plugin-owned thread
  retains it too. This is the classic self-unload hazard, and codex confirmed it is fatal here.
- Even absent that, `checkCanUnloadWithoutRestart == null` can still be defeated at unload time by
  vetoers, pre-existing restart state, dependency graphs, or listener errors — it is a *necessary*,
  not *sufficient*, condition, and it flips the moment a future plugin release adds a non-dynamic
  contribution. **Query it at runtime; never assume it.**

**Consequence:** the smoothest-looking option (silent hot-swap) is off the table. Updates are
restart-staged or delegated to the IDE's own update machinery (§4).

### 2.2 REST `checkCompatibility` works silently from a localhost origin ⭐ (CONFIRMED)

`GET /api/installPlugin` on the built-in server. Against GoLand (port 63344), with a localhost
`Origin` header:

```
GET …?pluginId=com.jonnyzzz.mcp-steroid                          -> {"name":"GoLand 2026.1.4","buildNumber":"GO-261.26222.72"}
GET …?pluginId=com.jonnyzzz.mcp-steroid&action=checkCompatibility -> {"compatible": true}
GET …?pluginId=com.jonnyzzz.does-not-exist&action=checkCompatibility -> {"compatible": false}
```

- **No modal appeared.** `RestService.isHostTrusted` lets a localhost-`Origin` (or token-signed)
  request through without the "trust unknown host?" prompt
  (`community/.../org/jetbrains/ide/RestService.kt:271-333`).
- `checkCompatibility` is a **pure Marketplace query** (`MarketplaceRequests.getLastCompatiblePluginUpdate`)
  — it installs nothing; safe against a user's live IDE. `InstallPluginService.kt` is byte-identical
  across 261/262/263 (codex-verified).
- **Qualification:** an HTTP 200 does not by itself prove an install/dialog happened — for
  `action=install` the endpoint returns OK immediately and does the work async, and returns OK even
  when the service is busy or no candidate exists. Use `checkCompatibility` for the *decision*, not
  the 200 of an `install` call for *confirmation*.

### 2.3 The marker's `x-ijt` token is NOT reliably stable — prefer localhost `Origin` (CORRECTED)

The recorded token happened to still return 200 in testing, but that only shows it was being
continuously re-accessed. `BuiltInWebServerAuth` caches tokens with `expireAfterAccess(1, MINUTES)`;
after one idle minute the entry is evicted and the next `acquireToken()` mints a **different** token
(`community/.../builtInWebServer/BuiltInWebServerAuth.kt:63-67,92-105`, codex-verified). So a marker
token can go stale. **Design rule:** treat the marker `x-ijt` as best-effort and always fall back to
a localhost `Origin` header (which is accepted regardless). Only send the token over loopback; never
log it; never send it to a non-localhost origin.

### 2.4 No custom plugin-repository host is registered in these IDEs

```
UpdateSettings.storedPluginHosts -> []   ;   pluginHosts -> []   ;   idea.plugin.hosts sysprop -> null
```

So the custom-repo path (§4, "IDE self-update") requires devrig to **register**
`https://devrig.dev/updatePlugins.xml` first — a persistent config change to the user's IDE. Heavier
footprint; opt-in only.

### 2.5 The install/update APIs exist but are NOT the silent local-install primitives the draft assumed (CORRECTED)

Verified by reflection in the live 261 runtime **and** in `origin/261` source:

| API (261) | Reality |
|---|---|
| `PluginInstaller.installFromDisk` | **package-private** `static void`, 6 params `(InstalledPluginsTableModel, PluginEnabler, Path, Project, JComponent, Consumer)`. Heavily **UI-coupled**: synchronous progress dialog, `checkThirdPartyPluginsAllowed`, multiple `MessagesEx.show*Dialog`, sets restart-required when an older install exists. This is the *"Install Plugin from Disk…" action*, not a headless API. |
| `PluginInstaller.installWithoutRestart` | **private**; only unpacks files. Not callable, not a full install. |
| `PluginInstaller.installAndLoadDynamicPlugin(Path, IdeaPluginDescriptorImpl)` | public — a genuine dynamic-load-from-file path, but for an **update** it must unload the old plugin first (→ the §2.1 self-unload hazard). Usable only where the plugin being loaded is *not* the one executing the call. |
| `PluginDownloader.createDownloader(IdeaPluginDescriptor)` | public, but the URL-building overload with a null host **ignores a local file URL and constructs a Marketplace URL** — so `createDownloader(node).installDynamically()` does **not** install devrig's local bundled zip. |
| `PluginDownloader.installDynamically(JComponent)` | public; gates on `allowLoadUnloadWithoutRestart` then `DynamicPlugins.unloadPlugin` (classloader-GC wait — §2.1). |
| `MarketplaceRequests.getLastCompatiblePluginUpdate`, `RepositoryHelper.getCustomPluginRepositoryHosts`, `InstalledPluginsState.hasNewerVersion` | present; fine for read-only queries. |

**API-stability caveat (261→263):** these are *internal* platform APIs and drift across baselines —
e.g. `DynamicPlugins.checkCanUnloadWithoutRestart` returns `String?` in 261 but `Boolean` in 262
(reason moves to `validateCanUnloadWithoutRestart`), and `installFromDisk` gains another parameter by
263. Any code that touches them needs baseline-specific handling or reflection. **The recommended
paths (§4) avoid the *worst* of these (no live self-swap, no `installFromDisk`)** — but they do **not**
avoid internal surfaces entirely: the running-IDE *update* path (Path F) stages via the
`@ApiStatus.Internal` `installAfterRestartAndKeepIfNecessary`, and persistent custom-repo registration
(Path C) writes the internal `UpdateSettings.storedPluginHosts`. Only the **closed-IDE** paths
(file-drop, headless starter) and REST `checkCompatibility` are fully-stable public/documented
surfaces. Every internal-API use in §4 must be pinned per baseline and guarded by a fail-fast drift
probe (§6). There is no fully-public SDK API for staging into a *running* IDE (§9-r2, §1 bottom line).

### 2.6 Port scanning can miss IDEs — marker discovery is authoritative for mcp-steroid IDEs (CORRECTED)

`IntelliJPortDiscovery` scans `63342..63361` + `64342..64361`. But the built-in server tries 20 ports
then **binds any free port**, and `rpc.port` can override it (`BuiltInServerManagerImpl.kt:134`,
`BuiltInServer.kt`). The live IDEA at **64463 is already outside both ranges** — proof the scan is
not exhaustive. Implications:

- For **mcp-steroid-aware** IDEs, marker discovery (`IdePidDiscoveryService`) is authoritative — the
  marker carries the exact `intellijWebServer.port`, `x-ijt`, `pluginPath`, and installed version.
- For a **fresh install** into an IDE with no plugin yet, there is no marker, and a pure port scan
  may not find it. Widen/parameterize the ranges, and treat "no IDE found" as "ask the user / accept
  partial coverage," never as "none running."
- Also fix `IdePortDiscovery.kt`'s `//TODO: same IDE can be returned multiple times` (built-in +
  MCP server of one IDE both answer) before shipping.

### 2.7 devrig plumbing that already exists vs. the gaps

Exists: `IntelliJPortDiscovery` (detection); `ClasspathBundledPluginResolver` (bundled artifact);
`readBundledPluginBuildRange()` + `PluginBuildRange.accepts(build)` (compat gate);
`mcp-core` version primitives (`DevrigVersion`, `Version`, `VersionComparatorUtil`);
`ManagedBackend.deployMcpSteroidPlugin()` (file copy into a *managed* plugins dir);
the authenticated Ktor bridge (transport for read-only in-IDE queries).

Gaps this spec creates:

- **G1** — devrig can't read the bundled plugin's `<version>` (only its build range). Needed for the
  version decision. Fix: extend the zip reader (mirror website-gen's `extractPluginCoordinates`).
- **G2** — no deploy/update path for running, *un-managed* IDEs (`deployMcpSteroidPlugin` targets
  only managed dirs).
- **G3** — `provision` only prints.
- **G4** — nothing consumes `McpSteroidServerInfo.pluginPath` (the marker already records it — useful
  for the file-drop / restart-staged path).

---

## 3. The full option space

| # | Channel | IDE running? | Source | Version pin | Surprise | Verdict |
|---|---|---|---|---|---|---|
| **A** | REST `GET /api/installPlugin?action=install` | yes | Marketplace/registered repo | ❌ latest only | native install dialog always (`installAndEnable(showDialog=true)`) | **First-install default (running IDE)** |
| **B** | In-process dynamic hot-swap via the bridge | yes | devrig bundle | ✅ | none — but **unsafe**: self-unload hazard (§2.1) | **Ruled out** |
| **C** | Custom repo `updatePlugins.xml` + host registration | yes (IDE-driven) | devrig.dev | ✅ via XML | native update notification; needs persistent host reg (§2.4) | **Opt-in IDE self-update** |
| **D** | File-drop into the plugins dir | **no** | devrig bundle | ✅ | none; applied next start (startup-only scan) | Closed-IDE install/update |
| **E** | Headless `installPlugins` / `update` starter | **no** (fails if running) | Marketplace/custom repo | repo-dependent | none (CLI, exit-code contract) | Closed-IDE install/update |
| **F** | **Restart-staged** install of the bundled zip | yes → applied on restart | devrig bundle | ✅ | none until the user restarts | **Running-IDE update default** |

Why A is fine but not for the bundle: JetBrains' own "Install to IDE" button uses exactly A and
*always* shows the install dialog — a good, native first-install UX — but it pulls the Marketplace
build, so it cannot pin devrig's *bundled* artifact and cannot update silently.

---

## 4. Recommended design

**Detect → identify → compare versions → pick the lightest *safe* path → act only with consent.**

### 4.0 Detect & identify

Join marker discovery (authoritative, §2.6) with a widened `IntelliJPortDiscovery` pass. Per running
IDE resolve `{port, productCode, buildNumber, baseUrl, x-ijt (best-effort), pluginPath, installedVersion}`.
Fix the dedup TODO first.

### 4.1 Compare versions (the "is there a newer plugin?" decision) — CORRECTED

1. **Bundled-vs-installed (authoritative for devrig's channel).** Read the bundled `<version>` (G1);
   read the installed version (marker, or in-process `PluginManagerCore.getPlugin(id).version`).
   The comparator choice is subtle and every off-the-shelf option has a trap:
   - Raw `VersionComparatorUtil` orders the dev build `0.101.19999` **below** the release
     `0.101-40690055` → it would flag dev SNAPSHOTs as "needing upgrade" and force-downgrade them.
   - `BackendVersionSkew.versionBase` truncates to `major.minor`, collapsing all same-lane builds.
   - **`DevrigVersion` ranks *every* snapshot above *every* release** — so it does **not** save you
     either: a *bundled snapshot over an installed release* looks "newer" and would replace a stable
     release with a dev build.
   - **Rule (strengthened): only auto-replace when BOTH the installed and the bundled versions are
     non-dev release builds, and the bundle is strictly newer.** If *either* side is a dev/SNAPSHOT
     build (`-SNAPSHOT` suffix or the `19999` dev marker), do not auto-replace — report only. This is
     the single gate; do not rely on the comparator alone to protect dev installs.
2. **Marketplace availability (informational).** `action=checkCompatibility` (§2.2) tells you whether
   Marketplace has a compatible build — used to choose between "offer bundled" and "offer
   Marketplace/Path A", and to warn when the bundle is *older* than what's published.

Compatibility gate for the bundle: `bundledPluginBuildRange.accepts(target.buildNumber)` (exists).
Note it currently compares build *baselines* only; fine for `since-build="261"`, but move to full
`BuildNumber` semantics if a future descriptor pins `since-build="261.NNNNN"`.

### 4.2 Act

- **First install, IDE running (plugin absent):** REST **Path A** —
  `GET …?action=install&pluginId=com.jonnyzzz.mcp-steroid` from a localhost origin. The user gets the
  native Marketplace install dialog (+ third-party-plugin consent). Best discovery UX; no bespoke
  risk. (There is no bridge yet — the plugin isn't installed — so an in-process install is
  impossible by definition.)
- **Update, IDE running (plugin present, bundle strictly newer):** **Path F — restart-staged.** Stage
  the bundled zip via `PluginInstaller.installAfterRestartAndKeepIfNecessary`
  (`(IdeaPluginDescriptor, Path, Path)`), then **notify** "MCP Steroid update staged — restart your
  IDE to apply." Never restart for the user. **Two caveats codex round-2 confirmed against 261 source:**
  1. The method is Java-`public` but `@ApiStatus.Internal` — there is **no fully-public/stable SDK
     surface** for staging an arbitrary local zip into a running IDE. The fallback if its signature
     drifts is the plugins-auto-update staging dir / `action.script` convention (also internal). This
     is an accepted, documented trade-off, not a stable public contract (§6/§7 honesty note).
  2. The method schedules **deletion of its source archive** (`PluginInstaller.java:452-454`). devrig
     must stage from a **disposable copy** of the bundled zip — never pass devrig's canonical
     `ijPluginZip()` path, or the bundle is destroyed.
  Staging performs no unload, so it does **not** hit the self-unload hazard (§2.1).
- **IDE self-update (opt-in, release builds only):** **Path C** — persistently register
  `devrig.dev/updatePlugins.xml` via **`UpdateSettings.storedPluginHosts`** (written to
  `options/updates.xml`; consumed by the next update check with no restart). Do **not** rely on
  `RepositoryHelper.amendPluginHostsProperty` — it is internal and only mutates the *current JVM's*
  `idea.plugin.hosts` (transient, lost on restart). The IDE's own scheduled update checker then finds
  new versions through **platform** code (no mcp-steroid frame on the stack), surfaced via the standard
  update notification. **But live no-restart is still only best-effort, not guaranteed:** the platform
  manual-update flow tries `installDynamically` and *falls back to restart staging*, and active bridge
  work (esp. blocking kotlinc) can still retain the classloader past the ~5 s unload wait
  (`PluginUpdateDialog`, `DynamicPlugins.kt:731-772`, `PluginAutoUpdateService.kt`). Path C also uses
  IntelliJ's **raw `VersionComparatorUtil`**, which is *outside* devrig's version guard (§4.1) — so
  **Path C must be disabled for dev/SNAPSHOT installs** to avoid re-introducing the snapshot-downgrade
  trap. Requires a persistent, user-approved config change.
- **Closed IDE:** **Path D** (copy the bundle into the discovered plugins dir; applied next start) or
  **Path E** (headless `installPlugins`/`update` starter). Reuse `deployMcpSteroidPlugin`'s copy logic
  against the discovered dir.

### 4.3 Explicitly out

**Path B (live in-process hot-swap of mcp-steroid via its own bridge).** Not safe (§2.1, §9). If a
future release ever wants a live update, it must be driven by **platform-owned** machinery with no
plugin frame on the stack (that is what Path C achieves) — not by a bridge script.

---

## 5. The "no surprises" contract

The IDE's own precedent (source-verified): never install silently — Marketplace's own button uses
`showDialog=true`, and non-JetBrains origins get a per-origin trust dialog. devrig, as a
localhost/token caller, *could* bypass those, so restraint is devrig's responsibility.

1. **Explicit, logged, once consent** before the first deploy/update into a user-opened IDE (mirrors
   `--give-consent-to-use-third-party-plugins`). Record the grant per the `updates-check/` marker
   pattern.
2. **Never restart the user's IDE.** Updates are restart-staged (Path F) with a clear "restart to
   apply" message; the user chooses when.
3. **Version-monotonic; never downgrade; never touch SNAPSHOT/dev builds** (§4.1). When the bundle is
   older than installed/Marketplace, *report*, don't act (`isDowngradeAllowed` stays off/private).
4. **Idempotent + observable.** "Everything current" → clear no-op message. Every action names the
   IDE, from→to version, and the path used.
5. **Prefer the IDE's own machinery when a live update is wanted** (Path C) over any bespoke swap.

---

## 6. Implementation outline (proposed; not built)

Ordered and each independently testable. **Honesty note on API stability (codex round-2):** the
*detection and version-check* layer (steps 1–2, 6) is built entirely on stable/public surfaces. The
*acting* layer is not uniformly so — Path A calls an existing (de-facto/internal) REST endpoint; Path
F stages via an `@ApiStatus.Internal` method (with a disposable copy, §4.2); persistent Path C writes
`UpdateSettings.storedPluginHosts`. Only the **closed-IDE paths (D/E)** are fully stable. Where an
internal API is used, devrig must pin it per baseline and gate it behind a signature/version probe so
drift fails fast rather than silently.

1. **G1 — bundled `<version>` reader** in `PluginCompatibility.kt` (parse `<version>` alongside the
   build range); unit-test with a fixture zip.
2. **Pure version-decision function** `PluginDeployDecision(installed, bundled, marketplaceCompatible,
   targetBuild, buildRange, isDevBuild) -> Action` where `Action ∈ {UpToDate, InstallFresh,
   StageUpdateForRestart, ReportNewerAvailable, Skip(reason)}`. Uses `DevrigVersion` ordering + the
   SNAPSHOT rule + `buildRange.accepts`. Fully unit-tested (incl. the `19999`-vs-`40690055` trap and
   the dev-SNAPSHOT skip).
3. **G2 — deploy/update command** for running, un-managed IDEs (working name
   `devrig plugin deploy [--to <port|all>] [--dry-run]`): run the decision, then execute Path A
   (fresh) or Path F (update), or Path D/E for closed IDEs. `--dry-run` prints the decision matrix.
4. **Consent + record** (one-time), and the "restart to apply" notification for staged updates.
5. **G3 — make `provision` act** (call into the new command; keep `--dry-run`/JSON).
6. **Detection fixes** (§2.6): dedup, widened/parameterized ranges, marker-first resolution.
7. **Optional Path C** command to register/unregister the custom repo, strictly opt-in.

Testing: exhaustive unit coverage of the decision function; a `:test-integration` Docker case that
(a) installs into a clean IDE via Path A and asserts the plugin loads, and (b) stages an update via
Path F, restarts the container IDE, and asserts the new version is active. **No** detect-and-skip; fix
infra if the Docker IDE lacks something (root CLAUDE.md).

---

## 7. Security & consent analysis (source-verified)

- The built-in web server is a recurring CVE surface (2016 CSRF/CORS; 2019–2020 open-ports;
  2022 file-read/NTLM; **CVE-2026-41882** arbitrary file read, fixed in 2026.1.1). The recommended
  paths add **no** new HTTP surface: Path A only *calls* an existing endpoint; C/D/E/F use existing
  install/update mechanisms. **Scope of mutation (corrected per codex round-2):** the mcp-steroid
  *bridge* is used only for **read-only** version/compat queries — but the deploy/update actions are
  **not** read-only overall. They mutate the target IDE's on-disk state (staged plugin files for
  Path F; `options/updates.xml` for persistent Path C) through platform machinery, some of it
  internal. The read-only claim applies to the bridge query path, not to the act step; the act step's
  safety rests on the §5 consent contract + "never restart / never downgrade / never touch dev builds."
- Trust model (`RestService.isHostTrusted`, `InstallPluginService`): token-signed or
  localhost-`Origin` requests skip the trust dialog; JetBrains web properties are the only pre-trusted
  external origins; 30 req/min rate limit. devrig is on the trusted (localhost) side — hence the §5
  consent contract is devrig's responsibility.
- Token handling per §2.3: marker `x-ijt` is best-effort, loopback-only, never logged; localhost
  `Origin` is the reliable fallback.

---

## 8. Open questions / risks

- **Q1 (settled NO).** Live in-process hot-swap of mcp-steroid via its own bridge — unsafe (§2.1,
  §9). Do not implement.
- **Q2.** Does Path A's async `installAndEnable` path perform a *dynamic* update of an already-loaded
  mcp-steroid when the bridge is idle, or does it also stage a restart? Worth a Docker test, but not
  load-bearing — Path F (restart-staged) is the committed update path regardless.
- **Q3.** `installAfterRestartAndKeepIfNecessary` signature stability 261→263 (internal API). If it
  drifts, fall back to the plugins-auto-update staging dir / `action.script` file (older, more
  stable).
- **Q4.** Custom-repo (Path C) auth/HTTPS and the IDE's 24h update cadence — acceptable latency for a
  self-update channel? Document the trade-off vs. Path F's immediacy.
- **Q5.** Port-scan coverage for fresh installs into IDEs on out-of-range ports (§2.6) — may require
  a user hint or a wider scan.

---

## 9. Validation log (codex peer review, 2026-07-30)

Two adversarial codex rounds (run via `run-agent.sh codex`) drove this spec from wrong to correct;
every finding was **independently re-verified** here by reflection in the live 261 runtime and by
reading `origin/261`/`262`/`263` source before folding in.

### Round 1 — refuted the original "live hot-swap" (Path B)

The first draft recommended a **live in-process dynamic swap** ("Path B") as the primary update path.
Confirmed corrections:

- **BLOCKER (confirmed):** the bridge cannot dynamically replace its own plugin —
  `installDynamically`→`unloadPlugin` waits for classloader GC that can't complete while the bridge
  holds the classloader (261 `PluginDownloader.installDynamically:302-411`, `DynamicPlugins.unloadPlugin`).
- **BLOCKER (confirmed by reflection):** `installFromDisk` is package-private, UI-coupled, 6-arg,
  `void`, restart-setting; `installWithoutRestart` is private/unpack-only; `createDownloader(node)`
  with a null host builds a Marketplace URL, ignoring a local file. The draft's install snippet was
  not viable.
- **MAJOR (confirmed):** internal-API drift 261→263 (`checkCanUnloadWithoutRestart` `String?`→`Boolean`;
  `installFromDisk` extra param) → prefer stable public surfaces.
- **MAJOR (confirmed):** `checkCanUnloadWithoutRestart == null` is necessary, not sufficient.
- **MAJOR (confirmed):** version ordering — raw `VersionComparatorUtil` puts `0.101.19999` below
  `0.101-40690055`; `versionBase` over-truncates → adopt the "never auto-replace SNAPSHOT" +
  `DevrigVersion` rule (§4.1).
- **MAJOR (confirmed):** fixed port ranges miss IDEs (live 64463 example) → marker-first (§2.6).
- **MINOR (confirmed):** marker `x-ijt` not reliably stable → localhost `Origin` fallback (§2.3).
- **OK (confirmed):** the REST/consent model (§2.2, §5, §7) is accurate; `InstallPluginService`
  byte-identical 261→263.

### Round 2 — refuted the v2 rewrite's over-claims

The v2 rewrite (Path B removed; Path F/C adopted) was re-submitted for a narrow "does the fix hold?"
review. codex refuted three remaining claims and one consistency issue; **all folded into this v3:**

- **BLOCKER — "Path F stages via a stable public mechanism" → REFUTE.** No fully-public SDK API
  exists to stage an arbitrary local zip into a running IDE. `installAfterRestartAndKeepIfNecessary`
  is `@ApiStatus.Internal`; `action.script` / `PluginAutoUpdateRepository` are internal formats;
  `InstalledPluginsState` is in-memory bookkeeping only. → §4.2 Path F + §6/§7 now state this
  explicitly and require per-baseline pinning + a drift probe (`PluginInstaller.java:148-185`).
- **BLOCKER — "§4.1 version rule protects dev builds" → REFUTE.** `DevrigVersion` ranks *every*
  snapshot above *every* release, so a bundled snapshot over an installed release looks "newer"; and
  Path C's raw `VersionComparatorUtil` bypasses the guard entirely. → §4.1 now requires **both** sides
  non-dev for auto-replace, and §4.2 **disables Path C for dev installs** (`DevrigVersion.kt`,
  `UpdateChecker.kt:496-520`).
- **BLOCKER — "Path C guarantees a live update" → REFUTE.** Platform ownership removes the *bridge
  self-frame* hazard, but live unload is still best-effort: the manual flow falls back to restart
  staging, and active bridge/kotlinc work can retain the classloader past the ~5 s wait. → §4.2 Path C
  now says "best-effort, not guaranteed" (`DynamicPlugins.kt:731-772`, `PluginAutoUpdateService.kt`).
- **MAJOR — restart-stage deletes its source archive → CONFIRM (qualified).** Staging avoids
  self-unload, but normally schedules deletion of its source; passing devrig's canonical
  `ijPluginZip()` would destroy the bundle. → §4.2 Path F now stages from a **disposable copy**
  (`PluginInstaller.java:452-454`).
- **MAJOR — repository registration mechanism → REFUTE (corrected).** `amendPluginHostsProperty` is
  internal + JVM-transient; persistent registration is `UpdateSettings.storedPluginHosts` in
  `options/updates.xml` (no restart needed). → §4.2 Path C corrected (`RepositoryHelper.java:64-80`,
  `UpdateSettings.java:20-47`).
- **MAJOR — §§6–7 "only stable public surfaces / read-only bridge" → REFUTE (over-claim).** Path F
  and persistent Path C mutate in-IDE state via internal APIs. → §6/§7 honesty notes added.
- **MINOR — REST first-install default → CONFIRM (qualified).** Best automated default, but
  no-restart is not guaranteed and the endpoint is de-facto/internal, not documented-stable
  (`InstallPluginService.kt:23-112`).

**Overall:** the REST detection/version-check research is sound and retained. The update path was
corrected across two rounds from "live hot-swap" → "restart-staged (Path F, disposable copy, internal
API pinned) + opt-in IDE-self-update (Path C, `storedPluginHosts`, release-builds-only, best-effort)."
The honest bottom line, stated up front (§1): **there is no fully-public/stable SDK API for
programmatic staging into a running IDE** — the closed-IDE paths (D/E) are the only fully-stable ones,
and every internal-API use is pinned per baseline behind a fail-fast drift probe. This v3 is the
submitted research result.

---

## Appendix A — reproducible probes

Localhost REST (safe; `checkCompatibility` never installs):

```bash
BASE=http://127.0.0.1:63344
curl -s -H "Origin: http://localhost" "$BASE/api/installPlugin?pluginId=com.jonnyzzz.mcp-steroid&action=checkCompatibility"
# -> {"compatible": true}
```

In-process dynamic-eligibility probe (read-only; via the bridge / steroid_execute_code):

```kotlin
val d = PluginManagerCore.getPlugin(PluginId.getId("com.jonnyzzz.mcp-steroid")) as IdeaPluginDescriptorImpl
DynamicPlugins.checkCanUnloadWithoutRestart(d)   // 261: null (String?) => descriptor-eligible ONLY
DynamicPlugins.allowLoadUnloadWithoutRestart(d)  // true — NOT a guarantee of a safe live swap (§2.1)
```

Custom repo currently live at `https://devrig.dev/updatePlugins.xml`:

```xml
<plugin id="com.jonnyzzz.mcp-steroid"
        url="https://github.com/jonnyzzz/mcp-steroid/releases/download/v0.101/mcp-steroid-0.101-40690055.zip"
        version="0.101-40690055">
  <idea-version since-build="261"/>
</plugin>
```

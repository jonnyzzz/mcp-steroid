# devrig auto-update via the install scripts (design)

Status: **APPROVED** — 3× quorum (unanimous, 0 blocking issues; see "Review log").
The judges' non-blocking nits are folded in below.
Owner: devrig CLI (`npx-kt`); coordination files under `~/.mcp-steroid/update/`.

## Goal

When `https://devrig.dev/version.json` promotes a version newer than the running
devrig, devrig updates **itself** by downloading and running the official install
script (`install.sh` on POSIX, `install.ps1` on Windows) — the exact same script a
user would run via `curl -fsSL https://devrig.dev/install.sh | sh`. No new update
channel, no new artifact format: the promoted install script is the single source
of truth for "how devrig gets installed", and auto-update just invokes it.

Multiple devrig processes run concurrently as a matter of course (Claude, Codex,
Gemini each spawn their own `devrig mcp`; short CLI commands come and go). The
design therefore centers on cross-process coordination through files under
`~/.mcp-steroid/update/`.

## What exists today (context)

- `npx-kt/.../DevrigUpdateChecker.kt` — `checkForUpdates()` fetches `version.json`
  (`version-base` field), compares via `DevrigVersion.isUpdateAvailable(current,
  promoted)`, and prints a "new version available, download from…" notice to stderr
  plus an MCP `notifications/message` broadcast. Called once per start from
  `Main.kt` for `runsTool()` commands (MCP, backend, project, install — but NOT
  `devrig version`/`help`), after a 0.2–1.3 s random delay.
- `mcp-core/.../DevrigVersion.kt` — ordering; a SNAPSHOT build is **always newer**
  than any promoted version (via the `isSnapshotBuild` short-circuit in
  `compareTo`), so dev builds never see updates. `comparableVersion` strips build
  metadata after the first `-`.
- `installer-gen` templates → published `install.sh` / `install.ps1`:
  idempotent, non-interactive, all-stderr, SHA-256-verified, content-addressed
  unpack under `~/.mcp-steroid/binaries/`, race-tolerant (`promote_tree` accepts a
  concurrent winner), and finish by handing off to
  `devrig install devrig --install-script=… --jdk-home=…` which atomically
  (re)writes `~/.mcp-steroid/bin/devrig` (or `devrig.cmd`) and registers PATH.
  The devrig version each script installs is **baked in**: `VERSION='…'` in
  install.sh; the padding-aligned `$Version      = '…'` in install.ps1.
- `npx-kt/.../BinLauncher.kt` — `ensureBinLauncher()` runs on **every devrig
  start** and rewrites `bin/devrig` to point at the *running* binary's own install
  tree (atomic write, content-compare, SNAPSHOT/env-gated).
  `ensureBinLauncherForInstallScript()` is the `devrig install devrig` variant
  (`force = true`); today it swallows write failures (`catch (e: Exception)` →
  log) and `runInstallDevrigCommand` verifies only `launcher.isRegularFile()`.
- `docs/devrig-deployment-spec.md` (v7) — sketched a signed-manifest
  `devrig upgrade` subcommand (phase 2); that was never shipped. This design
  supersedes it: the shipped install scripts are the update mechanism.

## Filesystem layout

```
~/.mcp-steroid/
├── update/                                ← NEW coordination dir (HomePaths.updateDir)
│   ├── lock                               ← THE mutual-exclusion primitive: atomic
│   │                                        exclusive create (CREATE_NEW); JSON content
│   ├── update-<pid>-version-<version>     ← in-progress state marker (observability +
│   │                                        the user-facing "who is updating" contract);
│   │                                        exists only while devrig <pid> drives an update
│   ├── updated-<version>                  ← completion marker; written by
│   │                                        `devrig install devrig` on verified success
│   ├── update-failed-<version>            ← failure counter (attempt cap / backoff)
│   ├── update-skew-<version>              ← CDN-skew counter (bounded quiet retries)
│   └── install-<pid>.sh|.ps1              ← downloaded install script (deleted after run)
└── logs/
    └── update-<pid>-<version>.log         ← installer stdout+stderr (30-day retention)
```

`<pid>` is the devrig process driving the update. `<version>` in every filename
is the **canonical base form** (`DevrigVersion.comparableVersion` — `0.101.441`,
never `0.101.441-gh-abc1234`): `devrig install devrig` writes markers from its
full build string while the supervisor works from `version.json`'s base string,
and without canonicalization one release would produce two textually different
marker files. Filenames parse unambiguously: `update-`, then digits, then
`-version-`, then the version.

Every file's **content** is JSON (kotlinx.serialization), carrying the full state
for humans and tooling — `pid`, `hostname`, `currentVersion` (of the writing
process), `targetVersion`, `startedAt`/`completedAt` (epoch millis), `logFile`,
`scriptUrl`, `installerHost` (the resolved shell/PowerShell binary); for
`update-failed-*` additionally `attempts`, `lifetimeAttempts`, `lastAttemptAt`,
`lastExitCode`; for `update-skew-*`: `firstSeenAt`, `attempts`, `parsedVersion`.

### The lock: acquire, release, reclaim

The original create-then-rescan protocol with a `(startedAt, pid)` tie-break
admits **two winners** (`startedAt` is process-supplied content, not creation
order — validation finding, CONFIRMED). Instead:

- **Acquire:** atomic exclusive create of `update/lock` (`Files.createFile`,
  `CREATE_NEW` — atomic on APFS, ext4, NTFS, NFSv4), then write the JSON content.
  `FileAlreadyExistsException` → an update is in flight elsewhere.
- **Release:** delete only after confirming the lock's JSON `pid` equals the
  releaser's own pid (a reclaimed-and-replaced lock must not be deleted by the
  old owner). Every path that acquired the lock releases it in a `finally`;
  the only ordering constraint is step 9's kill-before-release invariant.
- **Reclaim (crash cleanup):** a lock is *stale* when (a) its `hostname` matches
  the local host and `ProcessHandle.of(pid)` is absent, or (b) its age exceeds
  **24 h** — by `startedAt`, or file mtime when the content is unparsable
  (writer died between create and content write). The age bound applies **even
  when the pid reads live**: it covers PID reuse and a wedged owner whose
  updater coroutine died without cleanup, and it is safe because no genuine
  update holds the lock longer than the 30 min supervise timeout. Reclaim is
  race-free via atomic rename: `Files.move(lock, "lock.reclaimed.<ownPid>",
  ATOMIC_MOVE)` — exactly one reclaimer wins — then delete the renamed file and
  retry the acquire once. The same liveness-else-age rule cleans stale
  `update-<pid>-…` markers and orphaned `install-<pid>.*` scripts.

## The launcher version stamp

The rendered `bin/devrig` / `bin/devrig.cmd` gains one line —
`# devrig-version: <version>` (POSIX) / `rem devrig-version: <version>`
(Windows) — stamped by `ensureBinLauncherCore` from the version being
registered. This makes "which version does the launcher currently point at" a
local, parseable fact, which the reality checks below depend on. For launchers
written before this change, the fallback extracts the version from the embedded
install-tree path with a **digit-anchored** pattern (`devrig-(\d[0-9A-Za-z.]*)-`)
so `devrig-macos-arm64-…` cannot mis-parse (the path contains both the outer
`devrig-<os>-<cpu>-<version>-<sha12>` dir and the inner `devrig-<version>-<hash>`
subpath; both match the anchored form). If both fail the version is *unknown*.

## The update loop (`devrig mcp` sessions only)

The active updater runs **only in `devrig mcp` sessions** — a long-lived parent
that can supervise the installer and deliver the restart notification over MCP.
Short CLI invocations keep a passive, marker-aware notice (see "Notifications").
Their next invocation goes through `bin/devrig`, which already points at the new
version after an update, so they need no restart story.

The background slot that today runs `checkForUpdates()` once becomes a **loop**:
first tick after the existing 0.2–1.3 s random delay, then every 30–60 min
(jittered) for the life of the process. Without the loop, a session that lost
the update race — or was running while another process installed — would never
learn the update completed and never propose the restart (validation finding,
CONFIRMED). Each tick, in order:

1. **Gate.** SNAPSHOT/dev build (`isSnapshotBuild`), `DEVRIG_NO_AUTO_UPDATE`
   opt-out, **or `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out** → the entire tick,
   *including GC*, is skipped. (SNAPSHOT: it compares newer than every promoted
   version, so the GC rule in step 2 would otherwise delete every `updated-*`
   marker — the restart-pending signal. The launcher opt-out: with launcher
   writes disabled, every install attempt would fail its verify step by
   construction, burning capped attempts on a non-problem — the passive banner
   serves those setups.)
2. **GC** (local, cheap): delete `updated-<v>` where `v < current` using
   `comparableVersion` ordering only (strictly less: `updated-<current>` is kept
   one release as flip-back evidence for the no-downgrade guard); delete
   `update-<pid>-…` markers and `install-<pid>.*` scripts via the
   liveness-else-age rule; delete `update-failed-<v>` / `update-skew-<v>` for
   superseded versions (`v < current`). The 7-day decay of the failure counter
   is *windowed inside the file* — the cap check ignores attempts whose window
   started > 7 days ago — rather than file deletion, so `lifetimeAttempts`
   genuinely survives the decay (one bad-network day must not demote a release
   to the manual nag forever, but a deterministic failure still caps for good,
   see step 5). Delete `logs/update-*.log` older than 30 days.
3. **Restart pending?** (local): for the newest `updated-<v>` with `v > current`,
   read the launcher's version stamp. If the launcher version `>= v` → the
   update is real: emit the **restart notification** ("devrig `<v>` is
   installed — restart your agent session to use it"; once per process, stderr +
   MCP `notifications/message`) and end the tick. If the launcher version is
   older than `v` or unknown → the marker is **torn** (e.g. a flip-back landed
   after install): delete it and continue — the tick below re-runs the installer
   (trees are content-addressed and cached, so this is cheap). Never trust the
   marker over the launcher (validation finding, CONFIRMED critical).
   Additionally, a launcher stamp **older than `current`** (a flip-back landed
   while only newer sessions were running) is self-healed on the spot by
   re-running `ensureBinLauncher`.
   *Accepted parking behavior:* a long-lived session that already completed one
   update ends every tick here and does not chain-install further releases;
   convergence happens through new sessions (which start as the new version).
4. **Fetch** `version.json` → `promoted`. If
   `!DevrigVersion.isUpdateAvailable(current, promoted)` → end the tick. A
   promoted version moving *backward* (a pulled release) is thus never
   auto-applied; the supported rollback path is documented below.
5. **Caps** (local): `update-failed-<promoted>` at ≥ 3 attempts within the decay
   window, **or ≥ 9 lifetime attempts** (a deterministic failure — GPO blocking
   script execution, persistent SHA mismatch — must not re-download hundreds of
   MB every week forever), or the last attempt < 1 h ago → emit the
   manual-update notice (with the log path), once per process; end the tick.
   `update-skew-<promoted>` at ≥ 3 or first seen > 24 h ago → manual notice
   annotated with the skew diagnosis ("install script still serves
   `<parsedVersion>`, expected `<promoted>`") so a broken release pipeline is
   visible; end the tick.
6. **Acquire `update/lock`** (see lock section; if genuinely held → stop
   silently — the in-flight rule: no notification while another process
   installs). Then **re-verify steps 3–5 under the lock** (the scan-then-lock
   TOCTOU) — these aborts release the lock in the `finally` like every other
   path — and create `update-<ownPid>-version-<promoted>` with the full state
   JSON. All `update-failed` / `update-skew` writes below happen while holding
   the lock, so each counter has exactly one writer at a time.
7. **Download the install script** for this OS
   (`https://devrig.dev/install.sh` or `/install.ps1`) via the existing Ktor
   client → `update/install-<ownPid>.sh|.ps1`. **Skew guard:** extract the baked
   version with tolerant anchored patterns matched against the *real* templates —
   `^VERSION='([^']*)'` (install.sh), `^\s*\$Version\s*=\s*'([^']*)'`
   (install.ps1; the template pads with spaces before `=`, so an exact-literal
   match would never fire — validation finding, CONFIRMED). Three outcomes:
   - parsed and `== promoted` → proceed;
   - parsed and `!= promoted` → CDN mid-release propagation: increment
     `update-skew-<promoted>` (bounded by step 5), clean up, end the tick quietly;
   - **not parseable** → template drift or corruption: count it as a **failed
     attempt** (`update-failed-<promoted>`), so drift surfaces through the
     step-5 cap and manual notice instead of looping silently forever.
8. **Run the installer as a dedicated detached process:**
   - POSIX: `/bin/sh <script>`
   - Windows: resolve the host explicitly — `%SystemRoot%\System32\
     WindowsPowerShell\v1.0\powershell.exe` (from the `SystemRoot` env var,
     default `C:\Windows`) when it exists; only then fall back to bare
     `powershell`, then `pwsh`, via PATH. GUI-launched agents commonly carry
     stripped PATHs, and each spawn failure would burn a capped attempt on a
     non-install problem. Invoke with `-NoProfile -NonInteractive
     -ExecutionPolicy Bypass -File <script>` (`-File` runs in its own scope; the
     template has no param block and no `$PSScriptRoot` reliance, and the
     Java-written file carries no Mark-of-the-Web, so `Bypass` suffices except
     under GPO — which correctly decays to the manual notice via the caps).
   - Environment: `DEVRIG_AUTO_UPDATE=1` (inherited down to
     `devrig install devrig`; see the no-downgrade guard below).
   - stdin ← `/dev/null` / `NUL`; stdout + stderr →
     `logs/update-<ownPid>-<promoted>.log`, whose first line records the
     resolved installer host binary (also recorded in the marker JSON). Nothing
     from the installer may reach devrig's own stdout (MCP stdio rule).
   - A spawn failure (`IOException` before the installer runs) follows the same
     path as a non-zero exit.
   - The child survives devrig's own exit (validated in review): if this devrig
     dies mid-update the installer completes unsupervised, and the
     `devrig install devrig` handoff — not the dead supervisor — writes the
     completion marker.
9. **Supervise:** `waitFor` with a 30 min timeout. On timeout, kill the **whole
   installer tree before releasing anything** — `ProcessHandle.descendants()`
   `destroyForcibly()` then the root, confirmed via `onExit()` with a short
   grace — and only then record the failure and clean up. Invariant: the lock
   is never released while any process that can mutate `~/.mcp-steroid` may
   still be running (a surviving orphan finishing *hours* later could otherwise
   downgrade the launcher long after a newer release installed).
10. **On exit 0:** the installer's `devrig install devrig` has already verified
    the launcher and written `updated-<promoted>` (it is the authority — see
    below). If the marker is missing, the supervisor writes it as a fallback
    **only after re-reading the launcher stamp and confirming it is
    `>= promoted`** — exit-0-without-marker is anomalous (e.g. a PowerShell
    exit-code quirk), and an unguarded fallback would mint a torn marker plus a
    spurious restart notice; a stamp older than `promoted` is treated as a
    failed attempt instead. Then delete `update-failed-<promoted>` /
    `update-skew-<promoted>`, the downloaded script, the per-pid marker, and
    the lock — and emit the **restart notification**. The "update available"
    banner is never shown on this path — only "installed, restart" after the
    script completes.
11. **On non-zero exit / timeout / spawn failure:** increment
    `update-failed-<promoted>` (attempts + lifetimeAttempts + timestamp + exit
    code) under the lock, delete the script + per-pid marker + lock (keep the
    log), print a stderr warning with the log path. A later tick or session
    retries until the step-5 caps.

## `devrig install devrig` becomes the update authority

Three changes to the existing `runInstallDevrigCommand` /
`ensureBinLauncherForInstallScript` path (all confirmed necessary by review —
today a Windows sharing violation on `devrig.cmd` is swallowed, the command
returns 0 because the *old* launcher passes `isRegularFile()`, and a torn
install masquerades as complete):

1. **Verify, don't assume.** After the write, re-read `bin/devrig`(`.cmd`) and
   require its content to reference this binary's own install tree (the version
   stamp / embedded path). On mismatch — or on any write exception, which this
   path must **not** swallow (unlike the best-effort passive self-heal) — return
   non-zero, so `install.sh` / `install.ps1` propagate real failure to the
   supervisor and to manual `curl | sh` users.
2. **Write the completion marker.** On verified success, atomically write
   `update/updated-<comparableVersion(ownVersion)>` (this command runs *as* the
   new binary, so its own version IS the installed version) — **unless
   ownVersion is a SNAPSHOT build** (a SNAPSHOT marker would compare newer than
   every release and block release self-heal; mirrors the step-1 invariant).
   This closes the rewrite→marker ordering gap: with the marker written by the
   same process that rewrote the launcher, there is no window in which the
   launcher is new but the no-downgrade guard has no marker to see. It also
   gives manual installs the same flip-back protection and lets concurrently
   running old MCP sessions pick up the restart notice on their next tick.
3. **Honor intent.** When `DEVRIG_AUTO_UPDATE=1` (set only by the update loop's
   spawn): if a live `updated-<v>` with `v > ownVersion` exists, **skip** the
   launcher write and marker sweep and exit non-zero with a distinct message —
   a delayed/orphaned auto-update run must never downgrade past a newer
   completed update. Without the env (manual `curl | sh`, including an explicit
   rollback to an older release): first delete every `updated-<v>`,
   `update-failed-<v>`, and `update-skew-<v>` with `v > ownVersion`, then write
   the launcher — explicit install intent wins, and the swept markers cannot
   re-block the self-heal afterwards.

**Rollback policy:** auto-update never downgrades (step 4). Re-running an older
release's install script works (its `devrig install devrig` handoff has no
`DEVRIG_AUTO_UPDATE`, bypasses the guard, and clears newer markers) — but while
`version.json` still promotes the newer version, **any live `devrig mcp`
session's next tick re-upgrades**. Pinning an older version therefore requires
`DEVRIG_NO_AUTO_UPDATE` (or pulling the release from `version.json`). Note also
that a *pre-feature* release's `devrig install devrig` has none of this logic
(no verify, no sweep, unstamped launcher) — after such a rollback the next tick
treats the state as torn and reinstalls; same recipe applies.

## The `ensureBinLauncher` no-downgrade guard

`ensureBinLauncher` rewrites `bin/devrig` on every start to point at the running
binary's own tree, so an *old* devrig starting around an update could flip the
launcher back. The guard lives in the shared `ensureBinLauncherCore` (so it
covers every caller, including `devrig install <agent>`) and skips the rewrite,
with a one-line stderr note, when **either**:

- the existing launcher's version stamp parses to a version **newer** than the
  one being registered (`comparableVersion` ordering; an unparsable/missing
  stamp means *write* — preserving dev self-heal), **or**
- a live `updated-<v>` marker has `v` newer than the version being registered.

The single exception is the explicit-intent path above (`devrig install devrig`
without `DEVRIG_AUTO_UPDATE`). The launcher-content check makes the guard
independent of marker timing; the marker check catches a launcher that was
already flipped back. The residual TOCTOU (an old process descheduled between
its guard check and its atomic write) is accepted **because step 3 of the loop
makes it self-healing**: a flip-back produces a torn `updated-<v>` (marker newer
than launcher), which the next tick detects and repairs by re-running the
installer from the already-cached trees. No state is terminal.

*Transitional note:* agent registrations that predate the stable `bin/devrig`
launcher point directly at an old content-addressed tree; such pre-guard
binaries rewrite the launcher on every start, so each of those agent sessions
produces one bounded, cached, torn-marker reinstall on the next tick — repeating
until the user re-runs `devrig install <agent>`. The restart notice should hint
at re-registration when the launcher version keeps regressing.

## Notifications

`checkForUpdates()` — the single passive entry point used by short CLI commands
and by opted-out (`DEVRIG_NO_AUTO_UPDATE`) sessions — becomes **marker-aware**:
after the version comparison and before printing, it scans `update/` (a few
stat/parse calls): a live in-flight lock/marker for `promoted` → print
*nothing*; `updated-<promoted>` present (and launcher stamp ≥ promoted) with
`current < promoted` → print the **restart** notice instead of the download
banner; otherwise → today's manual banner. Without this, a short CLI command
would print "download manually" mid-install or after the install already
completed (validation finding, CONFIRMED).

| Situation | `devrig mcp` (auto-update on) | short CLI / opted-out |
|---|---|---|
| Update available, nothing in flight | none — install runs silently (log only) | manual download banner¹ |
| Install in flight (any process) | none | none |
| Install completed, `current <` installed | restart notice (once per process; stderr + MCP `notifications/message`) | restart notice |
| Failure/skew cap reached | manual banner + log path (once per process) | manual banner |

¹ Including the harmless window between a new release being promoted and the
first live `devrig mcp` tick acquiring the lock.

Restart UX ceiling (resolved question): stderr + `notifications/message` once
per process is the maximum — `devrig mcp` never refuses work or exits after
idle; killing a live MCP server mid-session harms the agent far more than
running one release behind.

## Gating and opt-out

- `DEVRIG_NO_AUTO_UPDATE` env var — `yes/true/1/on` disables the active updater
  (same parser semantics as `DEVRIG_BIN_NO_AUTO_REGISTER`); the passive
  marker-aware notice remains.
- `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out also disables the active updater (step-1
  rationale).
- SNAPSHOT/dev builds: the whole tick (updater *and* GC) is skipped — an
  explicit invariant with its own test, not just a side effect of version
  ordering.
- Active updater: `devrig mcp` only. Passive notice: all `runsTool()` commands,
  as today.

## Crash windows and failure modes (accepted behavior)

- **devrig dies mid-install:** the detached installer keeps running and its
  `devrig install devrig` handoff still verifies the launcher and writes
  `updated-<v>` itself — completion does not depend on the supervisor
  surviving. The orphaned lock is reclaimed via the dead-pid/24 h rules. A
  concurrent second installer for the *same* version is harmless (per-pid
  `.tmp.$$` staging, `promote_tree` accepts the winner, atomic launcher write);
  cross-version orphans are excluded by the timeout tree-kill plus the
  `DEVRIG_AUTO_UPDATE` no-downgrade rule.
- **PID reuse / wedged lock owners:** covered by the always-applied 24 h age
  bound on the lock (see lock section).
- **Shared NFS homes:** same-host liveness plus the age bound handle
  multi-host `~/.mcp-steroid`. Cross-*platform* shared homes (one `$HOME`
  mounted on machines of different os/arch) are **out of scope** — the single
  `bin/devrig` can only target one platform, a pre-existing limitation of the
  install layout.
- **version.json vs install-script skew during a release:** bounded quiet
  retries via `update-skew-<v>`, then a diagnosable manual notice.
- **Flip-back after install:** self-healing via the step-3 torn-marker check;
  see the no-downgrade guard section.

## Security considerations

Auto-update executes a script downloaded over TLS from `devrig.dev` — the same
trust root as the documented manual `curl | sh` install. The script itself
SHA-256-pins every artifact it downloads. What auto-update changes is *blast
radius*: a compromised devrig.dev/CDN would reach all auto-updating installs on
their next tick rather than only users who manually re-install. The v7
deployment spec's two-key signed manifest remains the designed hardening path
and is out of scope here; this design deliberately does not invent a weaker
interim signature scheme. Documented, explicit decision.

## Test plan (sketch)

- Unit: marker filename parse/format round-trip incl. `comparableVersion`
  canonicalization; lock semantics (exclusive create; loser stops; release only
  when content pid == own pid; atomic-rename reclaim — exactly one of two
  concurrent reclaimers wins; age bound overrides a live pid); failure-cap
  arithmetic (3-per-window, 1 h spacing, 7-day decay, 9-lifetime cap);
  skew-guard extraction run against **actual `InstallerGenerator`-rendered
  output** for both script flavors (not hand-written fixtures); launcher
  version-stamp render/parse + digit-anchored legacy-path fallback (fixture
  containing both `devrig-<os>-<cpu>-<version>-<sha>` and
  `devrig-<version>-<hash>` forms); no-downgrade guard × caller matrix (passive
  self-heal, `install <agent>`, `install devrig` with/without
  `DEVRIG_AUTO_UPDATE`, rollback case: `updated-<v+1>` present → manual
  `install devrig` for `v` rewrites and clears markers); startup-GC invariants
  (SNAPSHOT never deletes; `v < current` only, `comparableVersion` ordering;
  30-day log sweep); notification truth table (all rows above).
- Process-level (JVM, no Docker): fake install script (a `sh`/`.ps1` fixture
  that logs args/stdin and exits 0/1/sleeps) + redirected `HOME` — detached
  execution, stdin closed, log redirection + first-line host record, marker/
  lock lifecycle, exit-code paths, timeout tree-kill (fixture spawns a child;
  assert the whole tree dies before the lock is released). On Windows
  additionally: atomically replacing `devrig.cmd` while a `cmd.exe` launched
  through it is still running (the Windows failure story leans on cmd's
  share-delete semantics), and the sharing-violation → non-zero → capped-retry
  degradation path.
- Integration (`:test-integration`, existing installer lane): real `install.sh`
  against the nginx-served fixture model, driven through the auto-update path
  end-to-end, including the `devrig install devrig` verify+marker authority.

## Resolved review questions

1. **Lock shape:** fixed-name `update/lock` via atomic exclusive create, with
   atomic-rename reclaim and pid-checked release; per-pid markers demoted to
   observability/state. (The tie-break protocol was two-winner-unsound.)
2. **Staleness/caps:** 24 h stale bound applied liveness-independently (covers
   PID reuse, wedged owners, NFS); caps = 3 attempts per 7-day window, 1 h
   apart, 9 lifetime per version.
3. **Timeout:** kill the whole installer process tree, confirm exit, then
   release the lock.
4. **Restart UX:** notify once per process over stderr + MCP; never refuse work
   or exit.
5. **Windows host:** absolute System32 `powershell.exe` first, PATH `powershell`
   then `pwsh` as fallback; host recorded in marker + log.

## Review log

- 2026-07-30 — validation workflow (3 lens reviewers: concurrency/filesystem,
  platform/process, lifecycle/product; dedup; adversarial verification): 9
  findings CONFIRMED and incorporated (launcher/marker wedge — critical;
  two-winner lock race; ps1 skew-parse mismatch + unbounded silent aborts;
  timeout orphans; missing periodic re-check; passive-path mis-notification;
  rollback unhandled; SNAPSHOT GC trap; Windows host resolution), 1 REFUTED
  (installer detachment — the child does survive supervisor exit).
- 2026-07-30 — 3× quorum (state-machine soundness; implementability;
  requirement fidelity + operational safety): **unanimous APPROVE_WITH_NITS, 0
  blocking issues**. All 23 nits triaged; the design-level ones are folded into
  this revision (atomic-rename lock reclaim + pid-checked release +
  liveness-independent age bound; guarded step-10 fallback; `comparableVersion`
  marker canonicalization; `DEVRIG_BIN_NO_AUTO_REGISTER` gating; SNAPSHOT gate
  on the authority marker; lifetime attempt cap; 30-day log sweep; launcher
  self-heal for stamp < current; parking behavior documented; rollback-pinning
  recipe; transitional ping-pong note; NFS cross-platform out of scope;
  digit-anchored legacy stamp fallback; Windows cmd-replacement test).

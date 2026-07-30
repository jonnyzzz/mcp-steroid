# devrig auto-update via the install scripts (design, simplified)

Status: **APPROVED** — simplified design, re-validated (8 findings adopted) and
unanimously 3×-quorum-approved against the criteria *simple, minimal,
tradeoffs explicit* (0 blocking issues; see "Review log").
Owner: devrig CLI (`npx-kt`); coordination files under `~/.mcp-steroid/update/`.

## Goal

When `https://devrig.dev/version.json` promotes a version newer than the running
devrig, devrig updates **itself** by downloading and running the official install
script (`install.sh` on POSIX, `install.ps1` on Windows) — the exact same script a
user would run via `curl -fsSL https://devrig.dev/install.sh | sh`. No new update
channel, no new artifact format, and **no new obligations on the script**: its
contract stays exactly what it is today — download, verify, unpack, then call
`devrig install devrig`, which updates the launcher automatically.

Multiple devrig processes run concurrently as a matter of course (Claude, Codex,
Gemini each spawn their own `devrig mcp`). Coordination is *advisory*, through
per-process marker files — there is deliberately **no lock**.

## Design principles (from the simplification pass)

1. **No lock file.** A fixed-name lock can stay stuck after a failure and
   creates more problems than it solves. Each process writes its own
   `update-<pid>-version-<v>` file; everyone cleans up files from dead
   processes and yields to live ones. Best-effort mutual exclusion; the rare
   double-run is harmless (see Tradeoffs #1).
2. **`updated-<version>` is the single record of "this version is done."**
   Written by the supervising devrig after the install script exits 0, with the
   version taken from `version.json`. Once the file exists, that version needs
   no more work — every process checks it before starting an update.
3. **The devrig binary is not a source of truth.** No launcher version stamps,
   no content verification, no completion markers written by
   `devrig install devrig` — those coupled the install script's success to new
   devrig behavior, a heavy dependency the flow does not need. The launcher's
   correctness is owned by the (rename-based) launcher replacement itself,
   fixed independently of this design.

## Filesystem layout

```
~/.mcp-steroid/
├── update/                                ← coordination dir (HomePaths.updateDir)
│   ├── update-<pid>-version-<version>     ← per-process in-progress marker; exists only
│   │                                        while devrig <pid> drives an update
│   ├── updated-<version>                  ← completion record, written by the supervisor
│   │                                        after the install script exits 0
│   └── install-<pid>.sh|.ps1              ← downloaded install script (deleted after run)
└── logs/
    └── update-<pid>-<version>.log         ← installer stdout+stderr (30-day retention)
```

`<version>` in filenames is the canonical base form: strip build metadata, then
trailing `.0` components (`0.102.0-r-abc1234` → `0.102`, matching
`version.json`'s `0.102` — one release, one filename). Marker file contents are
human-readable JSON: `pid`, `currentVersion`, `targetVersion`, `startedAt`,
`logFile`, `scriptUrl`. The `-version-` infix in the in-progress marker name
keeps it unambiguous next to the `updated-` sibling.

**Staleness rule** (the only liveness machinery, uniform for every per-pid
file): *stale* = the pid is dead in the local pid table
(`ProcessHandle.of(pid)`), **or** the file is older than 24 h — the age bound
backstops PID reuse and shared-NFS homes alike (a foreign host's pid probe is
meaningless locally; see Tradeoff 4). Stale files are deleted by whichever
process sees them. Nothing is ever "reclaimed"; there is nothing to reclaim.

## The update tick (`devrig mcp` sessions only)

The active updater runs only in `devrig mcp` sessions — a long-lived parent that
can supervise the installer and deliver the restart notice over MCP. Short CLI
commands keep the passive notice (below); their next invocation goes through
`bin/devrig`, which already points at the new version after an update.

The background slot runs a loop: first tick after the existing 0.2–1.3 s random
delay, then **every 3–8 h (jittered)** — the retry schedule; a one-shot check
could also never tell a session that another process finished the install.
Each tick:

1. **Gate.** SNAPSHOT/dev build, `DEVRIG_NO_AUTO_UPDATE`, or
   `DEVRIG_BIN_NO_AUTO_REGISTER` (launcher writes disabled → installs could
   never take effect) → skip the entire tick, including GC. (A dev build must
   not GC or write records for real installs at all; note a SNAPSHOT `current`
   would also poison the GC bound's `min(current, promoted)` comparison.)
2. **Fetch** `version.json` → `promoted`. Fetch failed → end the tick (GC
   included — it needs `promoted` for a safe bound; housekeeping can wait).
3. **GC** (cheap): delete stale per-pid markers and orphaned `install-<pid>.*`
   scripts (staleness rule above); delete `updated-<v>` where
   `v < min(current, promoted)` — the bound includes `promoted` so a session
   running NEWER than the promoted version (the post-rollback state) never
   deletes the `updated-<promoted>` record that older sessions rely on (they
   would reinstall every tick otherwise); `updated-<current>` still naturally
   ages out one release later. Delete `logs/update-*.log` older than 30 days
   and any legacy `update-failed-*` files (a removed mechanism, below).
4. **Update available?** If
   `!DevrigVersion.isUpdateAvailable(current, promoted)` → done. (A promoted
   version moving backward is never auto-applied; rollback = pull the release
   or set `DEVRIG_NO_AUTO_UPDATE`.)
5. **Someone updating?** After the dead-file cleanup in step 3: if any live
   `update-<pid>-version-*` marker exists → another devrig is mid-update; stop
   **silently** (no notification before an install script completes).
6. **Already done?** If `updated-<promoted>` exists → this exact version was
   already installed: emit the **restart notice** ("devrig `<promoted>` is
   installed — restart your agent session to use it"; once per process, stderr
   + MCP `notifications/message`) — `current < promoted` is already guaranteed
   by step 4 here; the same check carries the condition explicitly on the
   passive path, which has no step 4. Stop. This check runs *after* the
   in-progress check, per the flow contract.
   There is deliberately **no failure tracking and no retry cap** between this
   step and the next: too many transient root causes exist (network, proxies,
   AV, GPO, disk), and the goal is to keep users up to date — every failure
   simply retries on the next scheduled tick, forever. Diagnosis lives in
   stderr and the per-attempt log files; devrig never tells the user to give
   up (see Tradeoff 9).
7. **Announce.** Write our own `update-<ownPid>-version-<promoted>` marker
   (full JSON state). This is the "I am updating" record other processes yield
   to — not a lock; see Tradeoffs #1 for the accepted race.
8. **Download the script** (`https://devrig.dev/install.sh` or `/install.ps1`)
   → `update/install-<ownPid>.sh|.ps1`. Every aberration here resolves the same
   way — a stderr line, delete own marker, retry next tick: a failed download
   (HTTP error/timeout on a ~40 KB text file), a downloaded file that cannot
   be read back, an unparsable baked version, or a **version sanity mismatch**:
   extract the baked version (`^VERSION='…'` / the padded
   `^\s*\$Version\s*=\s*'…'`) and compare to `promoted` by version ordering —
   a mismatch means CDN mid-propagation, and without this check a stale script
   would install the OLD version while `updated-<promoted>` records the new
   one — a false record.
9. **Run the installer as a dedicated detached process:** POSIX
    `/bin/sh <script>`; Windows `powershell.exe` (absolute
    `%SystemRoot%\System32\...\powershell.exe` first, PATH `powershell`, then
    `pwsh`) with `-NoProfile -NonInteractive -ExecutionPolicy Bypass -File`.
    stdin ← closed (immediate EOF); stdout+stderr →
    `logs/update-<ownPid>-<promoted>.log` (first line records the host binary).
    Supervise with `waitFor` + a 30 min timeout; on timeout kill the whole
    process tree (`descendants().destroyForcibly()` then the root) **before**
    any cleanup — so a *supervised* installer can never finish hours later
    against a newer state. (The kill can land mid-`devrig install devrig`
    launcher replacement — PR #385's rename sequence must stay crash-safe:
    never a moment with no `bin/devrig` on disk, since agents spawn devrig by
    that absolute path and a missing launcher has no self-heal trigger.) The child survives devrig's own death by design:
    an **unsupervised** orphan (supervisor died) has no timeout and can finish
    much later — see Tradeoff 5 for what that costs. No `updated-` record is
    written without a supervisor; the next session re-runs from cached
    artifacts.
10. **On exit 0:** write `updated-<promoted>` (atomic write; version taken from
    `version.json`), delete own marker + script, emit the **restart notice**.
    Telemetry: the trigger (step 9 spawn) was already captured as
    `devrig_self_update` with `target_version` = the raw version.json version.
11. **On non-zero exit / timeout / spawn failure:** delete own marker + script
    (keep the log), print a stderr warning with the log path — and retry on
    the next scheduled tick, forever. Own-marker deletion sits in a `finally` —
    a crashed tick leaves only a dead-pid marker, which any process cleans
    (step 3); nothing can stay stuck.

## Notifications

| Situation | `devrig mcp` (auto-update on) | short CLI / opted-out |
|---|---|---|
| Update available, nothing in flight | none — install runs silently (log only) | manual download banner |
| Install in flight (any live marker) | none | none |
| `updated-<promoted>` exists, `current < promoted` | restart notice (once per process) | restart notice |
| Install attempts keep failing | none — stderr + log files only, retries forever | manual download banner (same as row 1) |

The passive entry point (`checkForUpdates()`, used by short CLI commands and
opted-out sessions) does exactly two cheap file checks before printing — a live
in-progress marker → silence; `updated-<promoted>` present → restart notice.
There is no "give up" message anywhere: failures are visible in stderr and the
per-attempt logs, never as a user-facing nag (Tradeoff 9).

## What the simplification deliberately removed

Removed from the previous revision, per the simplification pass — each with the
failure mode it un-mitigates recorded under Tradeoffs:

- the fixed-name `update/lock` + atomic-rename reclaim + pid-checked release
  (→ Tradeoff 1);
- the launcher version stamp, the `ensureBinLauncher` no-downgrade guard, and
  every "never trust the marker over the launcher" reality check
  (→ Tradeoffs 2, 3);
- `devrig install devrig` as update authority (content verification, writing
  `updated-<v>` itself, the `DEVRIG_AUTO_UPDATE` env fence, the marker sweep on
  rollback) — the script contract is again just "call `devrig install devrig`"
  (→ Tradeoffs 2, 3, 5);
- the `update-skew-<v>` counter (→ Tradeoff 6);
- **failure tracking altogether** (a second owner decision, after the counter
  had already been trimmed to a bare 3-attempt cap): no `update-failed-*`
  files, no cap, no "update manually" nag — there are too many possible root
  causes, and the product goal is to keep users up to date, so updates retry
  on the 3–8 h schedule forever (→ Tradeoff 9); legacy `update-failed-*`
  files are GC'd;
- the `hostname` marker field and the same-host/foreign-host staleness branch
  (staleness is uniformly "local pid dead OR older than 24 h" → Tradeoff 4).

The Windows-specific danger of overwriting a launcher that another process
holds open is fixed at the root, independently of this design: the launcher
replacement becomes **rename-based** (rename the original aside, rename the new
file into its name — delete/overwrite-in-place can fail on Windows file locks).
That fix lands as its own PR and benefits every launcher write, not just
auto-update.

## Tradeoffs (explicit)

1. **Two updaters can run concurrently.** "Clean dead → check live → create
   own" is not atomic; two processes can both pass the check and both install.
   Accepted: the install scripts are concurrency-tolerant by construction
   (per-pid `.tmp.$$` staging, `promote_tree` accepts the winner, launcher
   written once by each `devrig install devrig`), so the worst case is a
   duplicate download of the same version. Frequency: steady-state collisions
   need two sessions ticking in the same few-second window (3–8 h apart per
   session) — but **correlated startups collide much more often**: first
   ticks fire 0.2–1.3 s after start, so launching Claude+Codex+Gemini together
   on a release morning makes a double-run fairly likely. The harm is
   unchanged (a duplicate download).
2. **`updated-<v>` is trust, not proof.** The supervisor writes it on exit 0
   without verifying the launcher actually changed. An install that exited 0
   but did not take effect (e.g. launcher managed manually, exotic failure
   swallowed by the script's environment) leaves a false "installed" record:
   the restart notice lies, and no retry happens **until the next release**
   (whose new version gets a fresh marker). Accepted as self-correcting on
   release cadence.
3. **Launcher flip-back is unguarded.** `ensureBinLauncher` rewrites the
   launcher on every start to point at the running binary; an *old* binary
   starting in the seconds around an install can repoint the launcher back,
   and with `updated-<v>` already written nothing retries until the next
   release. The window is a process-start racing the install's final moments —
   rare (starts are session-scoped), and self-correcting on release cadence.
4. **PID reuse and shared (NFS) homes can delay updates up to 24 h.** The
   staleness rule probes only the local pid table, so a recycled local pid — or
   any marker written by another NFS host — can look live; the 24 h age bound
   is the only backstop, and two hosts can double-install (see #1). Accepted
   for a mechanism that retries every tick. Cross-*platform* shared homes
   remain out of scope (single `bin/devrig` targets one os/arch —
   pre-existing).
5. **A dead supervisor forgets a successful install — and its orphan has no
   timeout.** If devrig dies while the installer runs, the install completes
   but no `updated-` record exists; the next session re-runs the installer
   (later-stage retries are served from the content-addressed cache; only a
   download-stage rerun re-fetches) and may briefly overlap the orphan
   (see #1). The sharper edge: the orphan is bounded by NO timeout (the
   30 min tree-kill dies with the supervisor, and the scripts' `curl` /
   `Invoke-WebRequest` have no transfer timeout), so an orphan stalled for
   hours can finish AFTER a newer version was installed and its terminal
   `devrig install devrig` repoints the launcher backward — with
   `updated-<newer>` already present, every session then shows a restart
   notice that restarting cannot satisfy, until the next release. Frequency:
   requires supervisor death AND a >30 min stall AND an intervening completed
   install. Accepted as release-cadence-correcting; a transfer timeout in the
   install scripts is a listed follow-up that shrinks the window with zero
   protocol complexity.
6. **Persistent version.json ↔ script skew is quietly retried forever.** No
   skew counter: a broken release pipeline (version.json promoted, script
   publish failed) costs one small script download per tick per session and is
   never surfaced to the user by devrig itself. Detection belongs to the
   release process (`release/release-instructions.md` Stage 9 agreement
   checks + the weekly URL-liveness action).
7. **Disk accretion is unbounded.** Nothing deletes superseded
   content-addressed trees under `~/.mcp-steroid/binaries/` (the scripts sweep
   only `.tmp.*` staging). Manual installs made growth rare and
   user-initiated; auto-update makes it automatic — roughly 50–200 MB per
   release (plus ~200 MB on a JDK bump) on every auto-updating machine, the
   one cost that occurs on 100% of happy paths. Accepted for now; the v7
   deployment spec's auto-GC (sweep trees not referenced by the current
   launcher, keep one previous) is the designed fix and a listed follow-up.
8. **A rollback leaves the newer `updated-<v>` marker standing.** GC deletes
   only `v < min(current, promoted)`, so after a rollback the marker survives.
   It is inert day-to-day (both notice paths key on `updated-<promoted>`, and
   after a rollback `promoted` is below the leftover marker — no notice ever
   references the pulled version), but it means a pulled version NUMBER can
   never be re-promoted: step 6 would short-circuit it forever.
   Release-pipeline invariant, recorded in the release instructions: **a
   pulled version number is never re-promoted; re-releases bump the base
   version.** Frequency: manual rollbacks/re-promotions — rare,
   operator-driven.
9. **Persistent failures retry forever, silently.** With failure tracking
   removed (owner decision — too many possible root causes; the product goal
   is keeping users up to date), a deterministically failing install (GPO
   blocking script execution, a broken environment, persistent SHA mismatch)
   re-runs the installer on every 3–8 h tick indefinitely: worst case a few
   installer runs per day per machine, with a full artifact re-download only
   when the failure is in the download/verify stage (later stages retry from
   the content-addressed cache). The user is never nagged to intervene —
   failures are visible only in stderr and `logs/update-*.log`. Accepted: an
   unbounded quiet retry is preferred over ever telling users to stop
   receiving updates.

## Gating and opt-out

- `DEVRIG_NO_AUTO_UPDATE` = `yes/true/1/on` → active updater off; passive
  notice remains.
- `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out → active updater off (launcher writes
  disabled — an "install" could never take effect).
- SNAPSHOT/dev builds: whole tick off (explicit invariant with its own test).
- Active updater: `devrig mcp` only. CLI-invocations-running-MCP-tools support
  is tracked separately (issue #383).

## Security considerations

Unchanged: auto-update executes a script fetched over TLS from `devrig.dev` —
the same trust root as the documented manual `curl | sh`; the script SHA-256-
pins every artifact. Auto-update raises blast radius (a compromised origin
reaches all auto-updating installs on their next tick); the v7 deployment
spec's signed manifest remains the designed hardening path, out of scope here.

## Test plan (sketch)

- Unit: marker name parse/format + canonical version (incl. `.0-r-` release
  lane); the uniform staleness rule (local pid dead; > 24 h age; the age bound
  overriding a live-looking pid); baked-version extraction against the real
  installer-gen templates; tick decision tree with fakes (yield to live
  marker; `updated-` short-circuit + restart notice; download failure / skew /
  unparsable script → quiet retry with no state written; exit-0 happy path;
  failing installer retries on EVERY tick with no cap, no state, and no
  user-facing notice; gate matrix); passive-notice truth table; GC invariants
  (SNAPSHOT never GCs; `v < min(current, promoted)` including the
  post-rollback keep-case — a session newer than `promoted` must NOT delete
  `updated-<promoted>`; legacy `update-failed-*` swept unconditionally; log
  sweep).
- Process-level (JVM, POSIX): real `/bin/sh` fixture — stdin EOF, log
  redirection + host first-line, exit-code propagation, timeout tree-kill of a
  grandchild.
- Integration (`:test-integration`, existing installer lane): real `install.sh`
  against the nginx fixture model through the auto-update path end-to-end.

## Review log

- 2026-07-30 — validation workflow (3 lenses, adversarial verify): 9 findings
  confirmed, 1 refuted → v1 design; 3× quorum: unanimous approve, 0 blocking.
- 2026-07-30 — **simplification pass** (owner decision): drop the lock file
  (stuck-lock risk outweighs the double-run it prevents), keep per-pid markers
  as the only coordination; `updated-<version>` written by the supervisor from
  version.json is the completion record; stop using the devrig binary as
  source of truth (no stamps/guards/authority — the script contract is only
  `devrig install devrig`); launcher replacement made rename-based in a
  separate dedicated PR (#385, Windows file locks).
- 2026-07-30 — re-validation of the simplified design (3 lenses: correctness
  within the binding decisions, further minimality, tradeoff completeness):
  8 findings, all adopted — GC bound became `min(current, promoted)` with
  fetch-before-GC (rollback reinstall-loop fix); the 1 h spacing arm dropped
  (a pacing condition fired the manual banner); script-download failure
  reclassified as quiet retry (no counter burn on network blips); `hostname` +
  the host-branch dropped (uniform staleness rule); orphan-flip-back, disk
  accretion, and rollback-leftover-marker recorded as Tradeoffs 5/7/8; the
  never-re-promote release invariant recorded.
- 2026-07-30 — 3× quorum on the simplified design (protocol soundness;
  minimality; tradeoff honesty + implementability): **unanimous
  APPROVE_WITH_NITS, 0 blocking issues.** All nits folded in: stale test-plan
  text (spacing arm, host vocabulary, old GC bound), the SNAPSHOT-gate
  rationale, Tradeoff 1's correlated-startup frequency, Tradeoff 8 trimmed to
  its real residual (the leftover marker is inert; only re-promotion is
  blocked), the unreadable-script case classified with download failure, the
  passive failure-cap row clarified, the PR #385 crash-safety cross-reference,
  and DO-NOT-REFORMAT guards on the templates' baked-version lines.
- 2026-07-30 — **owner decision, round 2:** failure tracking removed entirely
  (the 3-attempt cap included). There are too many possible root causes to
  ever stop; updates retry on a **3–8 h jittered schedule, forever**, and no
  "update manually" nag exists — diagnosis lives in stderr + the per-attempt
  logs (Tradeoff 9). `update-failed-*` becomes a legacy name swept by GC.

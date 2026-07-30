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
   processes and yields to live ones — and when two announce in the same
   window, the lowest pid wins (step 8). Best-effort mutual exclusion; the
   rare double-run is harmless (see Tradeoffs #1).
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
human-readable, pretty-printed JSON (`pid`, `currentVersion`, `targetVersion`,
`startedAt`, `logFile`, `scriptUrl`) — but they are **write-only debugging
information**: devrig never parses them back. Everything coordination needs is
in the *filename* (the pid, the version) plus filesystem metadata (mtime), so
the JSON format can change freely between versions with no compatibility
concern, and a corrupted/truncated file behaves exactly like a pristine one.
The `-version-` infix in the in-progress marker name keeps it unambiguous next
to the `updated-` sibling.

**Staleness rule** (the only liveness machinery, uniform for every per-pid
file): *stale* = the pid — parsed from the **filename**, never from the file's
contents — is dead in the local pid table (`ProcessHandle.of(pid)`), **or**
the file **mtime** is older than 24 h — the age bound backstops PID reuse and
shared-NFS homes alike (a foreign host's pid probe is meaningless locally; see
Tradeoff 4). Stale files are deleted by whichever process sees them. Nothing
is ever "reclaimed"; there is nothing to reclaim.

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
   (full JSON state — write-only debugging information, never parsed back).
   This is the "I am updating" record other processes yield to — not a lock;
   see Tradeoffs #1 for the accepted race.
8. **Recheck — lowest PID wins.** After announcing, re-scan for OTHER live
   `update-<pid>-version-*` markers (any version, staleness rule as always,
   filenames only): if one carries a **lower** pid than ours, that process
   wins — delete our own marker and stop **silently**; the next tick
   re-evaluates. Two processes can both pass step 5 in the same window and
   both announce; this recheck settles that race deterministically (equal
   pids are impossible on one host, and a higher-pid rival whose recheck
   runs after ours sees our lower pid and yields the same way). The residual
   double-run window is only the announce↔recheck race itself: the
   lower-pid process announces *after* the higher-pid one's recheck already
   scanned (see Tradeoff 1 — shrunk, still accepted).
9. **Download the script** (`https://devrig.dev/install.sh` or `/install.ps1`)
   → `update/install-<ownPid>.sh|.ps1`. A failed download (HTTP error/timeout
   on a ~40 KB text file) resolves as a stderr line, delete own marker, retry
   next tick. The downloaded script is deliberately **never inspected** —
   devrig does not parse the script and has no dependency on its internal
   format; anything wrong with the file surfaces when the installer runs it
   (the same quiet-retry path). A mid-propagation stale script therefore
   installs the previous version under the new record — an accepted tradeoff
   (Tradeoff 6).
10. **Run the installer as a dedicated detached process:** POSIX
    `/bin/sh <script>`; Windows `powershell.exe` (absolute
    `%SystemRoot%\System32\...\powershell.exe` first, PATH `powershell`, then
    `pwsh`) with `-NoProfile -NonInteractive -ExecutionPolicy Bypass -File`.
    stdin ← closed (immediate EOF); stdout+stderr →
    `logs/update-<ownPid>-<promoted>.log` — per-pid, so concurrent devrig
    processes never clash; retries of the same version by the same process
    append to the same file, each attempt opening with a timestamped separator
    line followed by a record of the resolved host binary.
    Supervise with `waitFor` + a 1 h timeout; on timeout force-kill ONLY the
    process we started (the shell/PowerShell host) **before** any cleanup,
    with a short bounded wait for it to die. There is deliberately no
    process-tree walk: children of the killed shell may survive (grandchildren
    are NOT killed — tree management is too much process-plumbing detail for
    this path; a survivor is the same class of unbounded orphan as Tradeoff
    5's, and the next tick retries anyway). (The kill can land
    mid-`devrig install devrig` launcher replacement — PR #385's rename
    sequence must stay crash-safe: never a moment with no `bin/devrig` on
    disk, since agents spawn devrig by that absolute path and a missing
    launcher has no self-heal trigger.) The child survives devrig's own death
    by design: an **unsupervised** orphan (supervisor died) has no timeout and
    can finish much later — see Tradeoff 5 for what that costs. No `updated-`
    record is written without a supervisor; the next session re-runs from
    cached artifacts.
    "Detached" means own stdio and a lifetime independent of the JVM — the
    child deliberately stays in devrig's own session/process group, NOT
    re-parented into its own session: every real shield is a platform branch
    (`setsid(1)` exists on Linux, macOS ships no such binary, Windows would
    need CreateProcess flags the JVM cannot set), and full daemonization
    (double-fork) would break the supervisor's `waitFor` → `updated-`
    contract outright. Consequence: an agent CLI that signals devrig's whole
    process group on session close kills installer and supervisor together —
    rare, accepted (documentation over platform branches; Tradeoff 5); the
    next session re-runs from cached artifacts, and a shielded survivor
    would only have become Tradeoff 5's unrecorded orphan anyway.
11. **On exit 0:** write `updated-<promoted>` (atomic write; version taken from
    `version.json`), delete own marker + script, emit the **restart notice**.
    Telemetry: the trigger (step 10 spawn) was already captured as
    `devrig_self_update` with `target_version` = the raw version.json version.
12. **On non-zero exit / timeout / spawn failure:** delete own marker + script
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
- the baked-version extraction (`^VERSION='…'` / `^\s*\$Version\s*=\s*'…'`)
  and the version sanity (skew) check on the downloaded script — devrig no
  longer parses the install script at all, and the DO-NOT-REFORMAT comments
  briefly added to the templates are reverted with it (the templates match
  `main` again; auto-update depends on nothing in their text) (→ Tradeoff 6);
- **failure tracking altogether** (a second owner decision, after the counter
  had already been trimmed to a bare 3-attempt cap): no `update-failed-*`
  files, no cap, no "update manually" nag — there are too many possible root
  causes, and the product goal is to keep users up to date, so updates retry
  on the 3–8 h schedule forever (→ Tradeoff 9); legacy `update-failed-*`
  files are GC'd;
- the `hostname` marker field and the same-host/foreign-host staleness branch
  (staleness is uniformly "local pid dead OR older than 24 h" → Tradeoff 4);
- **reading marker contents back** (the JSON `pid`/`startedAt` fields as
  staleness inputs, and the parse-failure fallback that came with them):
  parsing our own files couples liveness to a JSON format that must then stay
  compatible across versions — error-prone for zero benefit, since the
  filename already carries the pid and the version, and mtime carries the age.
  Contents remain pretty-printed JSON, but strictly as write-only debugging
  information;
- the timeout process-tree kill (`descendants().destroyForcibly()` before the
  root): on timeout — now 1 h instead of 30 min — devrig force-kills only the
  process it started; children of the killed shell may survive as the same
  class of unbounded orphan as a dead supervisor's (→ Tradeoff 5).

The Windows-specific danger of overwriting a launcher that another process
holds open is fixed at the root, independently of this design: the launcher
replacement becomes **rename-based** (rename the original aside, rename the new
file into its name — delete/overwrite-in-place can fail on Windows file locks).
That fix lands as its own PR and benefits every launcher write, not just
auto-update.

## Tradeoffs (explicit)

1. **Two updaters can still run concurrently — in a much smaller window.**
   "Clean dead → check live → create own → recheck" is not atomic; the
   step-8 lowest-pid-wins recheck settles the common both-announce case, so
   a double-run now needs the **lower**-pid process to announce only *after*
   the higher-pid one's recheck already scanned (both then proceed). That is
   the announce↔recheck race — sub-second, versus the whole install duration
   before the recheck existed. Accepted: the install scripts are
   concurrency-tolerant by construction (per-pid `.tmp.$$` staging,
   `promote_tree` accepts the winner, launcher written once by each
   `devrig install devrig`), so the worst case is a duplicate download of the
   same version. Frequency: steady-state collisions need two sessions ticking
   in the same few-second window (3–8 h apart per session) — **correlated
   startups collide much more often** (first ticks fire 0.2–1.3 s after
   start, so launching Claude+Codex+Gemini together on a release morning
   lands ticks close together), but even those now also need the sub-second
   announce↔recheck interleaving. The harm is unchanged (a duplicate
   download).
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
   (see #1). The sharper edge: the orphan is bounded by NO timeout (the 1 h
   kill dies with the supervisor, and the scripts' `curl` /
   `Invoke-WebRequest` have no transfer timeout), so an orphan stalled for
   hours can finish AFTER a newer version was installed and its terminal
   `devrig install devrig` repoints the launcher backward — with
   `updated-<newer>` already present, every session then shows a restart
   notice that restarting cannot satisfy, until the next release. The same
   class of orphan also survives a *fired* timeout: the kill takes down only
   the started shell, so a mid-stall child of that shell keeps running
   unbounded (step 10 — accepted, no tree-kill). Frequency: requires supervisor
   death (or a timeout-kill with a surviving child) AND a >1 h stall AND an
   intervening completed install. Accepted as release-cadence-correcting; a
   transfer timeout in the install scripts is a listed follow-up that shrinks
   the window with zero protocol complexity.
   The sibling case — the whole process **group** is signalled (agent CLIs
   may kill devrig's group when the user closes a session) — takes supervisor
   and installer down together: no record AND no orphan; the next session
   re-runs from cached artifacts. Shielding the installer into its own
   session was considered and rejected (step 10): no `setsid(1)` on macOS,
   platform branches everywhere else, for a survivor that would only land in
   this tradeoff's unrecorded-orphan state.
6. **A stale install script installs the previous version under the new
   version's record.** The downloaded script is never inspected, so when
   version.json already promotes `v_new` while the CDN still serves the
   `v_old` script (mid-release propagation window — or a broken pipeline that
   published version.json without the script), the first tick to see the
   promotion runs the stale script (a cheap re-install of `v_old`), gets
   exit 0, and the supervisor records `updated-<v_new>`: a **false record**.
   From then on step 6 short-circuits — no tick re-attempts `v_new`, even
   after the CDN settles minutes later — GC never deletes the marker (it is
   not below `min(current, promoted)`), and every session on `v_old` shows a
   restart notice that restarting cannot satisfy. The state heals only at the
   **next release** (whose new version gets a fresh marker) or via a manual
   `curl | sh` install once the CDN settles — the same release-cadence
   self-correction as Tradeoffs 2 and 3. Frequency: a tick must land inside
   the propagation window; first ticks fire 0.2–1.3 s after session start, so
   a release-morning session start can hit it. Pipeline-skew detection
   belongs to the release process (`release/release-instructions.md` Stage 9
   agreement checks + the weekly URL-liveness action).
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
Tracked follow-up: issue #389 — sign the install scripts, publish signatures
in version.json, verify before executing.

## Test plan (sketch)

- Unit: marker name parse/format + canonical version (incl. `.0-r-` release
  lane); the uniform staleness rule (filename pid dead; > 24 h mtime age; the
  age bound overriding a live-looking pid; contents never read — an
  unparsable marker behaves exactly like a valid one); tick decision tree with
  fakes (yield to live
  marker; the step-8 announce race — both announce in the same window, the
  higher-pid tick yields after announcing and deletes its OWN marker only,
  the lower-pid tick proceeds; `updated-` short-circuit + restart notice;
  download failure →
  quiet retry with no state written and no trigger reported; exit-0 happy path;
  failing installer retries on EVERY tick with no cap, no state, and no
  user-facing notice; gate matrix); passive-notice truth table; GC invariants
  (SNAPSHOT never GCs; `v < min(current, promoted)` including the
  post-rollback keep-case — a session newer than `promoted` must NOT delete
  `updated-<promoted>`; legacy `update-failed-*` swept unconditionally; log
  sweep); stdout purity (`AutoUpdaterStdoutPurityTest`: streams swapped for
  capture buffers around full ticks — happy path, failing installer,
  installer timeout, download failure, yield to a live marker — stdout is
  the MCP JSON-RPC channel and must stay EMPTY in every case, with the
  expected notice/warning lines landing on stderr).
- Process-level (JVM, POSIX): real `/bin/sh` fixture — stdin EOF, log
  redirection behind a per-attempt separator + host-record line, retries
  appending to the same per-pid log, exit-code propagation, timeout kill of
  the started process ONLY (null returned promptly; the detached grandchild
  survives by design — the test asserts it and cleans it up).
- Integration (planned — not yet implemented): drive the real `install.sh`
  through the auto-update path end-to-end against the nginx fixture model of
  the existing installer lane (`:installer-gen:installerIntegrationTest`,
  `InstallerBootstrapTest`); today that lane covers the script side alone.

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
  and DO-NOT-REFORMAT guards on the templates' baked-version lines (later
  reverted with the parsing removal — the templates carry no auto-update
  coupling).
- 2026-07-30 — **owner decision, round 2:** failure tracking removed entirely
  (the 3-attempt cap included). There are too many possible root causes to
  ever stop; updates retry on a **3–8 h jittered schedule, forever**, and no
  "update manually" nag exists — diagnosis lives in stderr + the per-attempt
  logs (Tradeoff 9). `update-failed-*` becomes a legacy name swept by GC.
- 2026-07-30 — **owner directives, round 3 (ten decisions, simplicity
  first):** one pass over the whole flow, every point traded toward less
  machinery. (a) The downloaded install script is **never parsed** — the
  baked-VERSION extraction and the version sanity (skew) check are removed;
  the mid-propagation consequence (a stale script installs the previous
  version while `updated-<promoted>` records the new one, healing only at
  the next release) is folded into Tradeoff 6. (b) The templates'
  DO-NOT-REFORMAT guards are reverted with it — devrig must not depend on
  script internals (the templates match `main` again). (c) Marker contents
  are **never read back**: staleness keys on the filename pid + file mtime
  only; the pretty JSON stays as write-only debugging information. (d) No
  descendant/process-tree kill — too much process-plumbing detail: on
  timeout, raised from 30 min to **1 h**, only the started shell/PowerShell
  host is force-killed (step 10, Tradeoff 5). (e) The installer runs
  **detached** where sensible — own stdio, lifetime independent of the JVM —
  but deliberately stays in devrig's session/process group: every real
  shield against a group-wide kill is a platform branch (`setsid(1)` on
  Linux only; macOS ships none; Windows needs CreateProcess flags the JVM
  cannot set), full daemonization would break the `waitFor` → `updated-`
  contract, and a shielded survivor would only become Tradeoff 5's
  unrecorded orphan — documentation over code (step 10). (f) stdin of the
  started script is closed (immediate EOF) and its output goes to the
  clash-free per-pid `logs/update-<pid>-<version>.log`, each attempt behind
  a timestamped separator (step 10). (g) A **post-announce recheck** settles
  the both-announce race: the lowest pid wins (new step 8; Tradeoff 1
  shrinks to the sub-second announce↔recheck window). (h) stdout purity of
  the whole update path is pinned by test (`AutoUpdaterStdoutPurityTest`) —
  everything user-facing is stderr-only; stdout is the JSON-RPC channel.
  (i) Signed install scripts — signatures published via version.json,
  verified before executing, the jonnyzzz/devrig release-process approach —
  are filed as follow-up issue #389, deliberately out of scope here.

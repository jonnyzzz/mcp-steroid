# devrig auto-update via the install scripts

Status: **APPROVED** — three review cycles, each closed by a unanimous 3× quorum
(see the compact Review log at the end).
Owner: devrig CLI (`npx-kt`); coordination files under `~/.mcp-steroid/update/`.

## Goal

When `https://devrig.dev/version.json` promotes a version newer than the running
devrig, devrig updates **itself** by downloading and running the official install
script (`install.sh` on POSIX, `install.ps1` on Windows) — the exact same script a
user would run via `curl -fsSL https://devrig.dev/install.sh | sh`. No new update
channel, no new artifact format, and **no new obligations on the script**: its
contract stays exactly what it is today — download, verify, unpack, then call
`devrig install devrig`, which updates the launcher automatically.

## How it works, in one paragraph

Each `devrig mcp` session runs a background tick every 3–8 hours. A tick fetches
`version.json`; if a newer version is promoted and nobody else is already
updating (visible as another process's live marker file), the session announces
itself with its own `update-<pid>-version-<version>` file, downloads the install
script, and runs it as a detached, supervised child with stdin closed and all
output going to a per-pid log. When the script exits 0, the session writes the
`updated-<version>` completion record and proposes a restart. Every failure, of
any kind, retries on the next tick — forever. There is no lock, no failure
bookkeeping, and devrig never reads back the contents of its own marker files.

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

`<version>` in filenames is `version.json`'s string used as-is (defensively
truncated at the first `-` or `/`) — every filename is derived from the same
source, so one release is one filename. Marker file contents are
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
2. **Fetch** `version.json` → `promoted` — a FRESH download on every tick,
   with a cache-busting query so a 3–8 h re-check sees the current promotion,
   never Cloudflare's cached copy. Fetch failed → end the tick (GC included —
   it needs `promoted` for a safe bound; housekeeping can wait).
3. **GC** (cheap): delete stale per-pid markers, orphaned `install-<pid>.*`
   scripts, and orphaned `.tmp.<pid>.*` atomic-write staging (staleness rule
   above); delete `updated-<v>` where `v < min(current, promoted)` — the bound
   includes `promoted` so a session running NEWER than the promoted version
   (the post-rollback state) never deletes the `updated-<promoted>` record
   that older sessions rely on (they would reinstall every tick otherwise);
   `updated-<current>` still naturally ages out one release later. Delete
   `logs/update-*.log` older than 30 days.
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
   double-run window is only the announce↔recheck race itself (see
   Tradeoff 1).
9. **Download the script** (`https://devrig.dev/install.sh` or `/install.ps1`)
   → `update/install-<ownPid>.sh|.ps1` — freshly on every attempt (the file is
   deleted after each run, and the GET carries the same cache-buster), so a
   retry picks up a server-side fix made during the wait. A failed download
   (HTTP error/timeout
   on a ~40 KB text file) resolves as a stderr line, delete own marker, retry
   next tick. The downloaded script is deliberately **never inspected** —
   devrig does not parse the script and has no dependency on its internal
   format; anything wrong with the file surfaces when the installer runs it
   (the same quiet-retry path). A mid-propagation stale script therefore
   installs the previous version under the new record — an accepted tradeoff
   (Tradeoff 6).
10. **Recheck again — after the download, before the spawn.** The download
    took real time; re-scan for other live markers and yield if one carries a
    lower pid, exactly as in step 8 (own marker + script are cleaned on the
    way out).
11. **Run the installer as a dedicated detached process:** POSIX
    `/bin/sh <script>`; Windows `powershell.exe` (absolute
    `%SystemRoot%\System32\...\powershell.exe` first, PATH `powershell`, then
    `pwsh`) with `-NoProfile -NonInteractive -ExecutionPolicy Bypass -File`.
    stdin ← closed (immediate EOF); stdout+stderr →
    `logs/update-<ownPid>-<promoted>.log` — per-pid, so concurrent devrig
    processes never clash; retries of the same version by the same process
    append to the same file, each attempt opening with a timestamped separator
    line followed by a record of the resolved host binary.
    Supervise with `waitFor` + a **1 h** timeout; on timeout force-kill ONLY
    the process we started (the shell/PowerShell host) **before** any cleanup,
    with a short bounded wait for it to die. There is deliberately no
    process-tree walk: children of the killed shell may survive (grandchildren
    are NOT killed — tree management is too much process-plumbing detail for
    this path; a survivor is the same class of unbounded orphan as Tradeoff
    5's, and the next tick retries anyway). The kill can land
    mid-`devrig install devrig` launcher replacement — covered by the
    launcher-replacement contract below (retried in place, self-heals on the
    next devrig start; the brief availability gap is accepted).
    "Detached" means own stdio and a lifetime independent of the JVM — the
    child deliberately stays in devrig's own session/process group, NOT
    re-parented into its own session: every real shield is a platform branch
    (`setsid(1)` exists on Linux, macOS ships no such binary, Windows would
    need CreateProcess flags the JVM cannot set), and full daemonization
    (double-fork) would break the supervisor's `waitFor` → `updated-`
    contract outright. Consequence: an agent CLI that signals devrig's whole
    process group on session close kills installer and supervisor together —
    rare, accepted (documentation over platform branches; Tradeoff 5); the
    next session re-runs from cached artifacts. The child DOES survive
    devrig's own death: an **unsupervised** orphan (supervisor died) has no
    timeout and can finish much later — see Tradeoff 5. No `updated-` record
    is written without a supervisor; the next session re-runs from cached
    artifacts.
12. **On exit 0:** write `updated-<promoted>` (atomic write; version taken from
    `version.json`), delete own marker + script, emit the **restart notice**.
    Telemetry: the update lifecycle is captured to the beacon as
    `devrig_self_update_started` (installer spawning), then exactly one of
    `devrig_self_update_completed` or `devrig_self_update_failed` (with
    `exit_code` when the installer returned one) — all carrying
    `target_version` = the raw version.json version. Quiet aborts before the
    spawn (yield, download failure) report nothing.
13. **On non-zero exit / timeout / spawn failure:** delete own marker + script
    (keep the log), print a stderr warning with the log path — and retry on
    the next scheduled tick, forever. Own-marker deletion sits in a `finally` —
    a crashed tick leaves only a dead-pid marker, which any process cleans
    (step 3); nothing can stay stuck.

## Launcher replacement (the `devrig install devrig` handoff)

The install script's last step, `devrig install devrig`, replaces
`~/.mcp-steroid/bin/devrig` (POSIX) / `devrig.cmd` (Windows). That replacement
uses **one algorithm for all platforms and every launcher write** (PR #385) —
the file is never edited in place, and the sequence is identical whether or
not the original file exists:

1. Create `<original-name>.new<pid>` with the new launcher content
   (executable bit set).
2. Attempt to move it onto the original name atomically; then attempt a
   non-atomic move.
3. Rename the original file to `<original-name>.old<pid>` (frees the name on
   Windows, where replacing an open file fails but a plain rename succeeds).
4. Repeat the two move attempts.
5. Delete own `.old<pid>`.
6. If anything failed: wait 10 ms and repeat the whole sequence, up to 5
   attempts, then give up with a stderr log — the next devrig start rewrites
   the launcher anyway.

The brief availability gap between the rename-aside and the move-in is
**accepted** (as atomic as practical, not theoretically perfect). Each process
touches only its OWN `.new<pid>`/`.old<pid>` files — no directory scanning, no
cross-process cleanup; a crash may leave one tiny leftover file behind,
accepted.

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

## Gating and opt-out

- `DEVRIG_NO_AUTO_UPDATE` = `yes/true/1/on` → active updater off; passive
  notice remains.
- `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out → active updater off (launcher writes
  disabled — an "install" could never take effect).
- SNAPSHOT/dev builds: whole tick off (explicit invariant with its own test).
- Active updater: `devrig mcp` only. CLI-invocations-running-MCP-tools support
  is tracked separately (issue #383).

## Design decisions (what was deliberately rejected, and why)

Each rejection trades a rare failure mode (recorded under Tradeoffs) for less
machinery:

- **No lock file.** A fixed-name lock can stay stuck after a failure and
  creates more problems than it solves. Per-pid markers + dead-pid cleanup +
  yield-to-live + the lowest-pid-wins recheck are the only coordination
  (→ Tradeoff 1).
- **The devrig binary is not a source of truth.** No launcher version stamps,
  no content verification, no completion markers written by
  `devrig install devrig` — those coupled the install script's success to new
  devrig behavior, a heavy dependency the flow does not need
  (→ Tradeoffs 2, 3). The launcher's correctness is owned by the
  rename-based replacement above.
- **No failure tracking.** No `update-failed-*` files, no attempt cap, no
  "update manually" nag: too many possible root causes exist, and the product
  goal is to keep users up to date — retries run on the 3–8 h schedule forever
  (→ Tradeoff 9).
- **The downloaded install script is opaque.** No baked-version extraction, no
  skew check — devrig depends on nothing inside the script's text, and the
  templates carry no auto-update coupling (→ Tradeoff 6).
- **Marker contents are never read back.** Coordination keys on filenames
  (pid, version) and mtime only; the pretty JSON is write-only debugging
  information, free to change format at any time.
- **No process-tree kill.** On timeout only the started shell/PowerShell host
  is force-killed; grandchildren may survive as the same class of orphan a
  dead supervisor leaves (→ Tradeoff 5).
- **No process-group shielding.** The installer keeps its own stdio and
  survives devrig's death, but stays in devrig's session/process group —
  every real shield is a platform branch, and full daemonization would break
  the supervisor contract (→ Tradeoff 5).

## Tradeoffs (explicit)

1. **Two updaters can still run concurrently — in a small window.**
   "Clean dead → check live → create own → recheck" is not atomic; the
   step-8 lowest-pid-wins recheck settles the common both-announce case, so
   a double-run now needs the **lower**-pid process to announce only *after*
   the higher-pid one's recheck already scanned (both then proceed). That is
   the announce↔recheck race — sub-second. Accepted: the install scripts are
   concurrency-tolerant by construction (per-pid `.tmp.$$` staging,
   `promote_tree` accepts the winner, launcher written once by each
   `devrig install devrig`), so the worst case is a duplicate download of the
   same version. Frequency: steady-state collisions need two sessions ticking
   in the same few-second window (3–8 h apart per session) — **correlated
   startups collide much more often** (first ticks fire 0.2–1.3 s after
   start, so launching Claude+Codex+Gemini together on a release morning
   lands ticks close together), but even those also need the sub-second
   announce↔recheck interleaving.
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
   unbounded (step 11 — accepted, no tree-kill). Frequency: requires
   supervisor death (or a timeout-kill with a surviving child) AND a >1 h
   stall AND an intervening completed install. Accepted as
   release-cadence-correcting; a transfer timeout in the install scripts is a
   listed follow-up that shrinks the window with zero protocol complexity.
   The sibling case — the whole process **group** is signalled (agent CLIs
   may kill devrig's group when the user closes a session) — takes supervisor
   and installer down together: no record AND no orphan; the next session
   re-runs from cached artifacts.
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
   **next release** or via a manual `curl | sh` install once the CDN settles.
   Frequency: a tick must land inside the propagation window; first ticks
   fire 0.2–1.3 s after session start, so a release-morning session start can
   hit it. Pipeline-skew detection belongs to the release process
   (`release/release-instructions.md` Stage 9 agreement checks + the weekly
   URL-liveness action).
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
9. **Persistent failures retry forever, silently.** A deterministically
   failing install (GPO blocking script execution, a broken environment,
   persistent SHA mismatch) re-runs the installer on every 3–8 h tick
   indefinitely: worst case a few installer runs per day per machine, with a
   full artifact re-download only when the failure is in the download/verify
   stage (later stages retry from the content-addressed cache). The user is
   never nagged to intervene — failures are visible only in stderr and
   `logs/update-*.log`. Accepted: an unbounded quiet retry is preferred over
   ever telling users to stop receiving updates.

## Security considerations

Auto-update executes a script fetched over TLS from `devrig.dev` — the same
trust root as the documented manual `curl | sh`; the script SHA-256-pins every
artifact it downloads. Auto-update raises blast radius (a compromised origin
reaches all auto-updating installs on their next tick); the designed hardening
path is tracked as follow-up **issue #389** — sign the install scripts, publish
signatures in `version.json`, verify before executing (the jonnyzzz/devrig
release-process approach).

## Test plan

- Unit: marker name parse/format; the uniform staleness rule (filename pid dead; > 24 h mtime age; the
  age bound overriding a live-looking pid; contents never read — an
  unparsable marker behaves exactly like a valid one); tick decision tree with
  fakes (yield to live marker; the step-8 announce race — both announce in the
  same window, the higher-pid tick yields after announcing and deletes its OWN
  marker only, the lower-pid tick proceeds; `updated-` short-circuit + restart
  notice; download failure → quiet retry with no state written and no trigger
  reported; exit-0 happy path; failing installer retries on EVERY tick with no
  cap, no state, and no user-facing notice; gate matrix); passive-notice truth
  table; GC invariants (SNAPSHOT never GCs; `v < min(current, promoted)`
  including the post-rollback keep-case — a session newer than `promoted` must
  NOT delete `updated-<promoted>`; orphaned `.tmp.<pid>.*` staging swept; log
  sweep); stdout purity
  (`AutoUpdaterStdoutPurityTest`: streams swapped for capture buffers around
  full ticks — happy path, failing installer, installer timeout, download
  failure, yield to a live marker — stdout is the MCP JSON-RPC channel and
  must stay EMPTY in every case, with the expected notice/warning lines
  landing on stderr).
- Process-level (JVM, POSIX): real `/bin/sh` fixture — stdin EOF, log
  redirection behind a per-attempt separator + host-record line, retries
  appending to the same per-pid log, exit-code propagation, timeout kill of
  the started process ONLY (null returned promptly; the detached grandchild
  survives by design — the test asserts it and cleans it up).
- Launcher replacement (PR #385): the unified `.new<pid>` → move →
  `.old<pid>` → move → delete-own-`.old<pid>` sequence with the 10 ms × 5
  retry, exercised on POSIX via an injected failing move; missing-original
  and everything-fails paths covered. Validated on REAL Windows (NTFS,
  PowerShell 5.1) with genuine holders — cmd.exe executing the launcher
  imposes no contention (full sharing); a memory-mapped holder blocks the
  replace but the rename-aside lands the new content (the exact case the
  sequence exists for); a no-delete-share holder blocks everything → loud
  5-round give-up with the original intact, healing on the next write.
- Integration (planned — not yet implemented): drive the real `install.sh`
  through the auto-update path end-to-end against the nginx fixture model of
  the existing installer lane (`:installer-gen:installerIntegrationTest`,
  `InstallerBootstrapTest`); today that lane covers the script side alone.

## Review log (compact)

All on 2026-07-30, each cycle closed by a unanimous 3× quorum with 0 blocking
issues:

1. **v1** — full validation (3 adversarial lenses: 9 findings confirmed,
   1 refuted) + quorum. Included a fixed-name lock, launcher version stamps, a
   no-downgrade guard, `devrig install devrig` as update authority, and
   failure/skew counters.
2. **Simplification (owner):** lock, stamps, guard, and authority removed —
   per-pid markers as the only coordination; supervisor-written `updated-<v>`;
   the binary is not a source of truth. Re-validated (8 findings adopted:
   `min(current, promoted)` GC bound, quiet-retry classifications, uniform
   staleness) + quorum.
3. **Owner round 2:** failure tracking removed entirely; retries every
   3–8 h forever, no caps, no nags.
4. **Owner round 3 (ten directives, one dedicated agent each):** no script
   parsing (templates carry no coupling); marker JSON write-only; no
   tree-kill (1 h timeout, kill the started process only); detached-in-group
   with the rationale documented; stdin/log handling audited; lowest-pid-wins
   recheck added; stdout purity pinned by test; signed-scripts follow-up
   filed as issue #389. Quorum: unanimous, first round.
5. **Launcher replacement unified (owner, PR #385):** one algorithm for all —
   write `.new<pid>`, two move attempts, rename-aside to `.old<pid>`, move
   again, delete own `.old<pid>`; 10 ms × 5 retries of the whole sequence;
   no directory sweeps (each process touches only its own files); the brief
   availability gap accepted.

Related: PR #380 (this feature), PR #385 (launcher replacement), issue #383
(CLI-runs-MCP-tools auto-update), issue #389 (signed install scripts),
`release/release-instructions.md` (the website-advance rollout gate +
never-re-promote invariant).

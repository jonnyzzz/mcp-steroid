# Spike: bundle devrig inside the IDE plugin — REJECTED

**Status: rejected 2026-07-28.** Kept for the measurements and so the idea is not re-litigated.
Measured on `0.100.19999-SNAPSHOT-c6568a61`.

## The decision

**devrig is the future, not the plugin — so the plugin keeps living inside devrig, as it does today.**
The plugin's job is the opposite of what this spike proposed: it is a **migration path**. Many users
already have the IDE plugin; the plugin must move them onto devrig by running our canonical install
scripts (`curl … install.sh | sh` / `irm … install.ps1 | iex`), because that is the one *correct* way
to install devrig.

Two reasons the reverse direction (devrig fetching the plugin on demand) was rejected:

1. It is harder than it looks and adds a **runtime dependency on the plugin repository**, which can
   break exactly in our own scenario (`devrig backend download/start` provisioning a fresh IDE).
2. Freshness is solved on the devrig side instead: devrig must keep itself up to date, and then the
   plugin it carries is up to date too. That is not fully true yet — it is known work with a known
   shape, owned on the devrig side.

## What the spike measured (still useful)

devrig distribution — 226 MB compressed / 232.5 MB uncompressed:

| Entry | Uncompressed | Purpose |
|---|---|---|
| `ij-plugin.zip` | 190.7 MB | provisioning the plugin into a devrig-managed IDE — **stays** |
| `lib/` | 39.5 MB | devrig + deps |
| `7z/` | 2.3 MB | native archive extraction for IDE downloads |
| `bin/`, `licenses/`, `EULA` | <0.1 MB | |

Install total ≈ 611 MB = pinned JDK (~385 MB) + devrig (226 MB).

Plugin zip — 191 MB compressed / 230.4 MB uncompressed: `kotlinc/lib` 86 MB, `ocr-tesseract/*` 123 MB
(both out-of-classpath side-cars launched on the IDE's JBR via `System.getProperty("java.home")` —
`KotlincProcessClient.kt:49`, `OcrProcessClient.kt:52,67`), plugin's own `lib/` ~21 MB.
Marketplace ceiling is 400 MB ([upload API](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html),
[common errors](https://plugins.jetbrains.com/docs/marketplace/list-of-common-errors-and-warnings.html)).

Consumers of the bundled `ij-plugin.zip` (all of them stay):

| Where | What it does |
|---|---|
| `ManagedBackend.kt:401,409` | unpacks the plugin into an IDE devrig downloaded itself |
| `BackendProvisionCommand.kt:208` | prints where to take the plugin from and where to put it |
| `PluginCompatibility.kt:88` | reads `since-build`/`until-build` for the version-skew check |

Note `devrig install plugin` (the claude-first path) never touches this archive — it asks the running IDE
to install from Marketplace via `/api/installPlugin`. It is unaffected by anything here.

## What actually follows from the decision

> **Superseded in part (2026-08-01).** The gaps below were addressed, but not all in the shape proposed
> here. The offer moved to the **settings page** rather than being made louder — a balloon has no room to
> justify a 611 MB download, and a status-bar widget is not ours to take uninvited (the widget was later
> deleted outright; the startup notification is behind `mcp.steroid.devrig.widget.enabled`, off by
> default). The install also no longer registers an agent (the shipped verb is `devrig install claude`):
> that is a separate, explicit step. See `ij-plugin/README.md` for what ships. The measurements above
> stand.

The plugin is a **migrator**. Mechanism-wise that already exists on the `claude-plugin` branch:

- `DevrigSetup.kt:26-28` — `installerArgv()` builds exactly the canonical one-liner
  (`/bin/sh -c "curl -fsSL https://devrig.dev/install.sh | sh"`, and the PowerShell
  `irm … | iex` on Windows);
- `DevrigSetupRunner.runEnable()` — runs it in a background task, then the agent registration (the
  shipped verb is `devrig install claude`), then reports the outcome through the onboarding
  notification group;
- `DevrigOnboardingService` — decides whether to offer at all.

So the remaining work is **not** the mechanism. It is these gaps:

1. **The offer is missable.** `notificationGroup id="jonnyzzz.mcp.steroid.onboarding"` is
   `displayType="BALLOON"` (plugin.xml:100-101) → auto-hides in ~10 s, fires once per IDE run at
   project open (the noisiest moment), and no decision is persisted, so we can neither escalate nor
   stop. Fixes: `STICKY_BALLOON`, `Enable / Later / Don't ask again` with persisted state, plus a
   status-bar widget as the always-visible fallback.
2. **"Installed" is not the same as "migrated".** `devrigInstalled()` (`OnboardingDecision.kt:36`)
   only checks that the launcher file exists — an ancient devrig counts as done. Since the migration
   story is "get the user onto a *correct, current* devrig", the plugin should compare the installed
   version against `version.json` (it already fetches that URL for itself, `UpdateChecker.kt:58`) and
   offer the same install script to update. devrig's own `DevrigUpdateChecker.kt:36,63` only prints to
   stderr, which a Claude user never sees.
3. **No progress on a ~611 MB download.** `runCommand()` uses `ExecUtil.execAndGetOutput`, which
   buffers output, so the background task can only show static text for up to 30 minutes. The
   installer prints progress; streaming it into the `ProgressIndicator` (fraction + MB) would make
   the wait legible.
4. **No funnel data.** `analyticsBeacon` exists but nothing records offered → enabled → install-ok →
   connected, so every argument about drop-off (including this spike) is guesswork.
5. **Failure state is inconsistent between paths.** The claude-plugin wrappers write
   `~/.mcp-steroid/markers/bootstrap-install.failed`; the IDE path only shows a notification. Writing
   the same marker would let `/devrig:status` and the SessionStart hook see an IDE-side failure.

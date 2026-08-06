# Install-scripts contract: install devrig, register PATH — nothing else

Status: locked (issue #398, PR #399) · Owner: `installer-gen/` templates + `devrig install devrig`

## The contract

The generated bootstrap installers (`install.sh` / `install.ps1`, served from the website and run as
`curl | sh` / `irm | iex`) have exactly one job:

1. **Install devrig** — download, verify, and unpack the devrig distribution and its bundled JDK into
   `~/.mcp-steroid/binaries/`.
2. **Register devrig on PATH** — by delegating to `devrig install devrig`, which (re)writes the stable
   `~/.mcp-steroid/bin/devrig`(`.cmd`) launcher and registers it on the user PATH. The devrig binary
   owns the launcher and PATH entry; the scripts never write either themselves.

That is the whole job. Everything else is the **user's explicit decision**.

## What the install flow must NEVER do

- **Never auto-register devrig with an AI agent** (Claude Code, Codex, Gemini, or any future agent).
  Registering an MCP server edits the agent's own configuration — state outside `~/.mcp-steroid` that
  belongs to the user. The only way devrig touches an agent config is the explicit
  `devrig install <agent>` command, run by the user.
- **Never auto-install the MCP Steroid plugin into IDEs.** Driving an IDE's plugin-install endpoint is
  likewise an action on the user's environment; it happens only via the explicit
  `devrig install plugin` command.

Instead of performing these actions, the flow **promotes** them: `devrig install devrig` ends with one
info message listing the exact commands the user may run next —

```
devrig is installed: ~/.mcp-steroid/bin/devrig
If 'devrig' is not found, add ~/.mcp-steroid/bin to PATH.

Next steps:
  devrig install plugin                 install the MCP Steroid plugin into your running JetBrains IDEs
  devrig install claude                 connect devrig to Claude Code
  devrig install codex                  connect devrig to Codex
  devrig install gemini                 connect devrig to Gemini
  devrig install config                 print the mcp.json snippet for any other MCP client
```

Each displayed command must be directly copyable. Do not compress mutually exclusive choices with `|` in
a shell-looking line: POSIX shells interpret it as a pipeline. The production-output alignment is tracked
in `TODO.md`.

The scripts print no next-steps block of their own — devrig's message is the single source, so the
guidance can never drift between the script and the binary.

## Why

- **Predictability.** `devrig install devrig` behaves identically on every call — the launcher + PATH
  registration is the same self-heal that runs on every devrig start, so the command is effectively
  informational. A user (or script) can run it any number of times with the same result.
- **Consent.** Agent configs and IDEs are the user's tools. An installer that silently rewires them
  turns a "download this CLI" decision into a "modify my agents" decision the user never made.
- **Debuggability.** When agent registration is a separate explicit step, a broken registration has a
  one-command reproduction (`devrig install <agent>`, dry-run via `--check`) instead of being buried
  inside a curl-pipe bootstrap.

## Mechanics worth knowing

- **The handoff sends `--install-script=<launcher>` and `--jdk-home=<jdk>` by design** — a forward
  contract a future devrig may use. Today's devrig accepts and **ignores** them: it derives the install
  tree and JDK from its own running process, so every spelling (including blank values) behaves
  identically.
- **The handoff runs under `DEVRIG_JAVA_HOME`** — devrig's own variable, honored by the dist launcher —
  never by setting the user's `JAVA_HOME`. `install.ps1` restores the variable afterwards because
  `irm | iex` executes in the caller's session.
- **Non-interactive, always.** The scripts hand `devrig install devrig` a stdin that reads EOF
  immediately (`< /dev/null` / `$null |`); the command and everything it calls must never read stdin or
  prompt.

## Enforcement

| Guard | Where |
|---|---|
| `install devrig`'s only side effect is launcher registration (no agent CLI, no IDE reachable from its seams) | `npx-kt` `InstallDevrigCommandTest` |
| Scripts contain no agent-registration call and no duplicate next-steps block | `installer-gen` `InstallerGeneratorTest` |
| End-to-end: fake devrig proves no `install <agent>` invocation ever happens during bootstrap, and the handoff runs under the bundled JDK | `installer-gen` `installerIntegrationTest` (`InstallerBootstrapTest`, `InstallerBootstrapPs1Test`) |

Related docs: [devrig-deployment-spec.md](devrig-deployment-spec.md) (install layout, manifest, wrapper
ownership), [updates-check/devrig-auto-update.md](updates-check/devrig-auto-update.md) (the auto-updater
reuses these scripts, so it inherits this contract).

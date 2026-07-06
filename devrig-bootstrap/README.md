# devrig-bootstrap

A tiny Go MCP server bundled inside the Claude plugin. While the real `devrig`
binary (~611 MB, needs a JDK) is not yet installed, the plugin launches this
instead so Claude sees a **green** MCP server (never "✗ Failed to connect"). It:

- serves a minimal MCP server exposing one tool, `devrig_status`;
- downloads the real devrig in the background (the canonical `install.sh` /
  `install.ps1`), guarded by a single-flight lock with a heartbeat. The download
  runs in a **detached re-exec of this binary** (`DEVRIG_BOOTSTRAP_ROLE_INSTALLER=1`,
  a "supervisor" that owns the lock heartbeat + failure markers) so it **survives
  Claude quitting or restarting** — a later bootstrap picks up the finished binary;
- **stays alive as a proxy** and activates tools in up to three tiers, firing
  `notifications/tools/list_changed` at each swap so Claude re-fetches the
  toolset on the user's next message — no restart at any point:
  - **Tier 0** — before any backend is ready, serves one tool, `devrig_status`;
  - **Tier 1 (seconds, no download)** — if an IDE running the MCP Steroid plugin
    is already open, it advertises its HTTP MCP endpoint in
    `~/.mcp-steroid/markers/<pid>.mcp-steroid` (`mcpSteroidServer.baseUrl`). The
    bootstrap bridges to it over Streamable HTTP and proxies the full IDE
    toolset immediately, on the IDE's own JBR;
  - **Tier 2 (after the ~611 MB download)** — spawns `devrig mcp`, swaps to it
    (superseding Tier 1), adding cross-backend routing and managed-backend
    lifecycle. If no IDE is open, the proxy goes Tier 0 → Tier 2 directly, exactly
    as before;
- gets out of the way on subsequent launches: once `~/.mcp-steroid/bin/devrig`
  exists, the launcher runs that directly and this binary is no longer used.

## Build

```
./gradlew :devrig-bootstrap:buildBootstrapBinaries   # cross-compiles all 6 targets
cd devrig-bootstrap && go test ./...                 # unit tests
```

Builds are byte-reproducible (`-buildid=`, `-buildvcs=false`, and the pinned
`toolchain` in `go.mod`) — that's what lets the drift check below byte-compare.

## ⚠️ The binaries are committed — regenerate after every Go change

The plugin is installed by **cloning** its files, so the binaries must live in
git at `claude-plugin/bin/bootstrap-*`. They are NOT built at install time.

**After changing any `.go` file:**

```
./gradlew :claude-plugin:updateBundledBinaries   # rebuilds + copies into claude-plugin/bin/
git add claude-plugin/bin/bootstrap-*            # commit the regenerated binaries
```

`./gradlew :claude-plugin:check` runs `verifyBundledBinariesUpToDate`, which
byte-compares the committed binaries against a fresh build and **fails** if you
forgot to regenerate them.

## Files & knobs

| File | What |
|---|---|
| `mcp.go` | minimal MCP stdio server + `devrig_status` tool; advertises `listChanged` capability |
| `status.go` | install-state detection (`installed`/`installing`/`failed`/`absent`) + user messages |
| `install.go` | background install, single-flight lock, heartbeat |
| `progress.go` | markers, log, download-size progress |
| `jsonrpc.go` | JSON-RPC message types (`rpcRequest`, `rpcResponse`, `rpcMessage`) and framing helpers |
| `backend.go` | `backend`: wraps the spawned `devrig mcp` process, owns its stdin/stdout pipes + a `shutdown` hook |
| `marker.go` | `discoverIdeEndpoints`: reads `<pid>.mcp-steroid` markers, returns running IDEs' HTTP MCP endpoints (newest first) |
| `httpmcp.go` | `httpMcpClient`: minimal Streamable-HTTP MCP client (POST + `Mcp-Session-Id`) for the IDE endpoint |
| `httpbackend.go` | `newHTTPBackend`: presents a running IDE's HTTP endpoint as a `backend` so the proxy forwards to it unchanged |
| `proxy.go` | `proxy`: tiered hot-swap MCP proxy — Tier 1 (`swapToIde`) then Tier 2 (`swapToDevrig`); forwards client↔backend traffic, ID-prefixes server-initiated requests, fires `tools/list_changed` at each swap |
| `statusline.go` | `--statusline` mode: prints the one-line progress segment (`devrig 41% · 250/611 MB` / `devrig ✓` / `devrig ⚠`). One source of truth for both the status-line bar and the hook |
| `settings.go` | add-only/remove-only editor for `~/.claude/settings.json` `statusLine`; bar-vs-hook detection (`shouldUseHookMode`); `statusline.owner` marker |

**Passive progress (issue #225).** While downloading, the bootstrap surfaces progress automatically —
no command to run. On startup it picks a mode: if you have **no** status line anywhere it **adds** a
transient `statusLine` to `~/.claude/settings.json` running `<bootstrap> --statusline` (`refreshInterval:2`,
removed on Tier-2 swap / exit / signal, and self-healed by the SessionStart hook's `--remove-statusline`);
if you **already** have a status line it touches nothing and the plugin's `UserPromptSubmit` hook shows a
per-turn `⏳ …` line instead. The chosen mode is recorded in `~/.mcp-steroid/markers/statusline.owner`.

Tunable constants: `approxInstallMB` (progress total, `progress.go`),
`heartbeatInterval` / `installLockStaleAfter` (`status.go`), installer URLs
(`install.go`), `swapPollInterval` (`proxy.go` — how often the proxy polls for
the downloaded launcher before spawning `devrig mcp`). State lives under
`~/.mcp-steroid/markers/` (`bootstrap-install.lock` / `.failed` / `.log`).

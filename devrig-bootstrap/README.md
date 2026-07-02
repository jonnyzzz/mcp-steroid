# devrig-bootstrap

A tiny Go MCP server bundled inside the Claude plugin. While the real `devrig`
binary (~500 MB, needs a JDK) is not yet installed, the plugin launches this
instead so Claude sees a **green** MCP server (never "✗ Failed to connect"). It:

- serves a minimal MCP server exposing one tool, `devrig_status`;
- downloads the real devrig in the background (the canonical `install.sh` /
  `install.ps1`), guarded by a single-flight lock with a heartbeat;
- **stays alive as a proxy**: when the download finishes, the bootstrap spawns
  `devrig mcp`, hot-swaps to it, and fires `notifications/tools/list_changed`
  so Claude activates the full toolset on the user's next message — no restart
  needed;
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
| `backend.go` | `backend`: wraps the spawned `devrig mcp` process, owns its stdin/stdout pipes |
| `proxy.go` | `proxy`: hot-swap MCP proxy — forwards client↔backend traffic, ID-prefixes server-initiated requests, fires `tools/list_changed` after swap |

Tunable constants: `approxInstallMB` (progress total, `progress.go`),
`heartbeatInterval` / `installLockStaleAfter` (`status.go`), installer URLs
(`install.go`), `swapPollInterval` (`proxy.go` — how often the proxy polls for
the downloaded launcher before spawning `devrig mcp`). State lives under
`~/.mcp-steroid/markers/` (`bootstrap-install.lock` / `.failed` / `.log`).

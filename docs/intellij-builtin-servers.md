# IntelliJ's built-in HTTP servers — what we talk to

Reference notes for code in this repo that interoperates with the IntelliJ
Platform's own HTTP servers. Compiled from the IntelliJ community source
tree (`~/Work/intellij/community`).

There are **two** IntelliJ-side servers that are interesting to us:

1. **Built-in web server** (Netty, always-on) — IDE remote control + REST.
2. **MCP Server** — a separate ktor-CIO endpoint bundled in
   `com.intellij.mcpServer`, off by default, optional.

These are independent of **our** `mcp-steroid` HTTP server.

---

## 1. Built-in web server (`BuiltInServerManager`)

Module: `intellij.platform.builtInServer` /
`intellij.platform.builtInServer-api`.

### Lifecycle / endpoint

- API: `BuiltInServerManager.getInstance()`
  - `port: Int` — the chosen port (auto-picks the first free port at startup
    starting from a default, typically **63342**). devrig probes the same
    default range during port discovery; see
    [`docs/devrig-naming.md`](devrig-naming.md) § "Backend
    naming" for how a port-discovered IDE is hashed into an exposed
    backend id.
  - `address: InetAddress` — usually the loopback address.
  - `isOnBuiltInWebServer(url): Boolean` — does the URL point at us?
  - `addAuthToken(url): Url` — appends the per-session auth token query
    parameter expected by `BuiltInWebServerAuth`-gated REST services.

### REST endpoints (`/api/<service>/...`)

All `RestService` subclasses register under the `/api/<getServiceName()>`
prefix. The handler list discovered in the community source:

| Path | Source | Purpose |
|---|---|---|
| `/api/about` | `AboutHttpService.kt` | Returns IDE application info (JSON). Used by JetBrains Toolbox + protocol-handler integrations. |
| `/api/installPlugin?id=...` | `InstallPluginService.kt` | Install a plugin by id from the marketplace. |
| `/api/startUpMeasurement` | `StartUpMeasurementService.kt` | Internal startup telemetry. |
| `/api/toolbox` | `ToolboxRestService.kt` | Toolbox app integration channel. |
| `/api/logs/...` | `UploadLogsService.kt` | Upload IDE logs to a JetBrains endpoint. |
| `/api/file?file=...&line=...&column=...` | `OpenFileHttpService.kt` (`com.intellij.remote-control` plugin) | Focus the IDE and open a file at a position. |
| `/api/settings?name=...` | `OpenSettingsHttpService.kt` (`com.intellij.remote-control` plugin) | Open the Settings dialog at a specific page. |
| `/api/projectSet` | `ProjectSetRequestHandler.java` (`com.intellij.remote-control` plugin) | Open a project set (clone+open from JSON spec). |

Additional plugins add their own services (Qodana web UI, JS debugger,
collaboration-tools OAuth callbacks, Toolbox subcommands, etc.). The
authoritative list at runtime comes from the
`org.jetbrains.ide.RestService` / `httpRequestHandler` extension points.

### Authentication

- Read endpoints exposed via the built-in server typically gate by
  `BuiltInWebServerAuth.isAuthorized(...)`. The CSRF-style token is
  available via `BuiltInServerManager.addAuthToken(url)` — the caller
  rewrites the URL it returns to clients so the token rides along.
- The token is also exposed for "trusted" clients via the
  `jetbrains.io.auth-token` system property (per `BuiltInWebServerAuth`
  internals — verify before relying on it).

---

## 2. IntelliJ MCP Server plugin (`com.intellij.mcpServer`)

Module: `community/plugins/mcp-server`.

### Plugin shape

- Plugin ID: `com.intellij.mcpServer`
- Plugin name: "MCP Server" (category: AI-Powered)
- **Bundled** in modern IntelliJ Platform builds, but **off by default**:
  `McpServerSettings.state.enableMcpServer = false` until the user opts
  in (Settings → Tools → MCP Server, or via the status-bar widget).

### Runtime endpoint

The plugin runs an embedded `ktor-server-cio` engine on
`127.0.0.1:<port>` once enabled.

- Default port: **64342** (`McpServerSettings.DEFAULT_MCP_PORT`).
- Configurable via `mcpServer.xml` IDE state, or:
  - Force enable: `-Didea.mcp.server.force.enable=true`
  - Force port:   `-Didea.mcp.server.force.port=<int>`
- Bound to `127.0.0.1` only — no LAN exposure.
- Falls back to the next free port if the configured port is busy
  (unless force-port pins it).

### URLs

`McpServerConnectionAddressProvider`:

| Property | Path | Notes |
|---|---|---|
| `serverStreamUrl` | `/stream` | MCP "streamable HTTP" transport. |
| `serverSseUrl` | `/sse` | MCP SSE transport (legacy). |
| `httpUrl(path, port?, host?)` | configurable | Helper for arbitrary paths. |

### Authentication

- The **globally-enabled** server is **unauthenticated** — protection is
  purely the localhost bind + (optional) host-validation interceptor
  (`installHostValidation()` in `McpServerService.startServer`).
- A separate "private" server on `DEFAULT_MCP_PRIVATE_PORT = 64442` runs
  per `authorizedSession { ... }` block and gates by an
  `IJ_MCP_AUTH_TOKEN` HTTP header containing a UUID generated for the
  session. We do not need this path for monitoring use cases.

### Detecting it from another plugin (optional dependency, no reflection)

We use the canonical IntelliJ Platform "optional plugin descriptor"
pattern:

1. `ij-plugin/build.gradle.kts` declares
   `bundledPlugin("com.intellij.mcpServer")` so the API is on the
   compile classpath (verified bundled in IDEA 2025.3+).
2. `ij-plugin/src/main/resources/META-INF/plugin.xml` adds
   `<depends optional="true" config-file="mcpServer-integration.xml">com.intellij.mcpServer</depends>`.
3. `mcpServer-integration.xml` registers
   `IntelliJMcpServerProbeImpl` as the application service for the
   `IntelliJMcpServerProbe` interface. The descriptor — and therefore
   the impl class — is only processed by the IDE when the optional
   dependency is satisfied.

At call sites we use `IntelliJMcpServerProbe.getInstanceOrNull()`:
when the optional config isn't active, no service is registered and
the call returns `null` cleanly. No reflection, no
`NoClassDefFoundError` window.

The 253-bundled `McpServerService` exposes `isRunning`, `getPort`, and
`getServerSseUrl`. `serverStreamUrl` is a newer addition; the probe
derives the `/stream` URL from the SSE URL (same listener, sibling
path) so the marker carries both.

### Tool surface

Toolsets registered in `plugin.xml` (extension point
`com.intellij.mcpServer.mcpToolset`) cover the typical agent surface:
`Execution`, `Analysis`, `File`, `Formatting`, `Universal` (router),
`Read`, `Text`, `Patch`, `Search`, `CodeInsight`, `Refactoring`, plus
optional `terminal` / `vcs` add-ons.

---

## What this repo does with these endpoints

- **Built-in web server**: the IDE-side `mcp-steroid` plugin now records
  `BuiltInServerManager` metadata on `PidMarker.intellijWebServer`
  (port, tokenized `/api/about` URL, and auth token). The devrig monitor
  still talks directly to our `mcp-steroid` ktor server for the
  main bridge flow; the built-in server metadata is discovery context.
- **MCP Server plugin**: detected at marker-write time and surfaced on
  `PidMarker.intellijMcpServer` so devrig can route an MCP client at
  IntelliJ's bundled tools alongside our own. Wiring:
  `IntelliJMcpServerProbe` (interface) +
  `IntelliJMcpServerProbeImpl` (real impl), registered only via
  `META-INF/mcpServer-integration.xml` under the optional plugin
  dependency.

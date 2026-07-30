# Architecture

This document is a concise architecture map. For authoritative details, see `AGENTS.md` (symlink to `CLAUDE.md`) and the source tree references listed below.

## Top-Level Components
- MCP server: Ktor-based HTTP transport + MCP protocol handling.
- Session management: `McpSessionManager` for session tracking and recovery.
- Tool registry: tool discovery and dispatch (`McpToolRegistry`).
- Execution pipeline: script compilation and execution with output collection.
- Prompt articles: served via the dedicated `steroid_fetch_resource` MCP tool (NOT via `resources/list` / `prompts/list` — the tool requires `project_name` for IDE-conditional rendering).
- Vision tools: screenshot/input tooling with artifact storage.
- OCR helper: external `ocr-tesseract` app invoked via process client.
- Kotlin compilation: bundled Kotlin Build Tools implementation jars (`kotlinc/` in the plugin dist), driven in-process via the Build Tools API in an isolated classloader.
- Storage: execution logs/artifacts (append-only, under `~/.mcp-steroid/runs/` by default).
- **devrig CLI** (`npx-kt/`): stateless stdio MCP server + `backend` /
  `project` CLI that discovers IntelliJ instances on the host and
  routes tool calls to them. Project / IDE naming is governed by the
  [`docs/devrig-naming.md`](devrig-naming.md) spec; on-demand routing
  rationale lives in
  [`docs/devrig-scanning-research.md`](devrig-scanning-research.md).

## Request Flow (exec_code)
1) HTTP request arrives at `/mcp` and is validated by `McpHttpTransport`.
2) Session is resolved; unknown sessions create a new session and return a notice header.
3) Request is dispatched via `McpServerCore` and `McpToolRegistry`.
4) Execution is coordinated by `ExecutionManager`, compiling scripts and running them with timeout.
5) Output is collected into `ToolCallResult` and returned via JSON-RPC response.

## Request Flow (vision screenshot/input)
1) Tool call hits the vision handler.
2) Screenshot artifacts are written under the execution folder (image + metadata).
3) The response includes the `window_id` and references the stored files.
4) Input events reference the screenshot execution and target window.

## Request Flow (OCR)
1) The plugin launches the bundled `ocr-tesseract` application.
2) The OCR app reads the image and writes JSON output to stdout.
3) `OcrProcessClient` parses the JSON and returns structured OCR results.

## Source Map
- Transport: `src/main/kotlin/com/jonnyzzz/intellij/mcp/mcp/McpHttpTransport.kt`
- Core routing: `src/main/kotlin/com/jonnyzzz/intellij/mcp/mcp/McpServerCore.kt`
- Sessions: `src/main/kotlin/com/jonnyzzz/intellij/mcp/mcp/McpSession.kt`
- Execution: `src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/*`
- Vision: `src/main/kotlin/com/jonnyzzz/intellij/mcp/server/Vision*`
- OCR: `src/main/kotlin/com/jonnyzzz/intellij/mcp/ocr/*` and `ocr-tesseract/`
- Kotlin compilation: `src/main/kotlin/com/jonnyzzz/intellij/mcp/koltinc/*` and the bundled BTA jars in `kotlinc/`

## Related Docs
- `README.md`: usage, HTTP flow, tool contracts
- `AGENTS.md`: contributor rules and deep implementation notes
- [`PHILOSOPHY.md`](PHILOSOPHY.md): the four design tenets (small
  MCP tool surface; prompts + direct IntelliJ APIs; devrig is
  stateless; `McpScriptContext` last-resort)
- [`devrig-naming.md`](devrig-naming.md): canonical contract for
  project and backend (IDE) names across the devrig CLI and the
  devrig stdio MCP server
- [`devrig-scanning-research.md`](devrig-scanning-research.md):
  on-demand `rebuildSnapshot()` decision record

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

/**
 * The `instructions` devrig returns from `initialize` (jonnyzzz/mcp-steroid#417).
 *
 * Why this text exists at all: agent harnesses increasingly *defer* MCP tools. Claude Code
 * indexes tool NAMES only and loads a schema on demand, so everything we say in a tool
 * description — including how to provision an IDE, which lives in `steroid_open_project`'s
 * `BACKEND_NAME_DESCRIPTION` — is unreachable until the agent has already guessed our tools
 * exist. A task-only prompt therefore never touches the IDE: it falls back to `find` + `cat`.
 * The `initialize` result is the one channel a deferring harness still puts in context
 * verbatim, so the capability statement belongs here.
 *
 * Keep it SHORT and navigational: it costs context on every session. State what the backend is,
 * name the tool prefix so the agent can search for it, and point at the two entry points
 * (`steroid_list_projects` to see what is reachable, `devrig backend` to provision one). Depth
 * stays in the tool descriptions and the `mcp-steroid://` article corpus.
 */
const val DEVRIG_MCP_SERVER_INSTRUCTIONS = """This server hands you a real JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, Rider, ...) as a
tool backend: the resolved project model, indexes, inspections, refactorings, run configurations,
the debugger and the IDE UI. Prefer it over grep/find/sed for anything about code SEMANTICS —
call hierarchies, subtypes, references, symbol resolution, rename/refactor, running or debugging.

Its tools are all named steroid_* (steroid_list_projects, steroid_open_project,
steroid_list_windows, steroid_execute_code, steroid_execute_feedback, steroid_take_screenshot,
steroid_input, steroid_fetch_resource). If your harness loads MCP tool schemas on demand, load
them before you decide the IDE is unavailable.

Start here:
- steroid_list_projects - which IDEs and projects are reachable right now.
- No IDE reachable? Provision one from the shell with the `devrig` binary that serves this MCP
  server — it is on your PATH: `devrig backend` lists them,
  `devrig backend download <id>` installs one, `devrig backend start <id>` runs it.
- steroid_open_project opens a project in a backend; steroid_execute_code runs Kotlin against
  the live IntelliJ API for everything the narrow tool surface does not cover.
- steroid_fetch_resource returns the mcp-steroid:// article corpus - the recipes for that API."""

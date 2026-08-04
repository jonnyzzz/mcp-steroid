/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle

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
 * name the tool prefix so the agent can search for it, and carry the clean-machine bootstrap facts
 * that are otherwise hidden behind deferred schemas and project-scoped articles. Depth stays in the
 * tool descriptions and the `mcp-steroid://` article corpus.
 */
val DEVRIG_MCP_SERVER_INSTRUCTIONS = """This server hands you a real JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, Rider, ...) as a
tool backend: the resolved project model, indexes, inspections, refactorings, run configurations,
the debugger and the IDE UI. Prefer it over grep/find/sed for anything about code SEMANTICS —
call hierarchies, subtypes, references, symbol resolution, rename/refactor, running or debugging.

Its tools are all named steroid_* (steroid_list_projects, steroid_open_project,
steroid_list_windows, steroid_execute_code, steroid_execute_feedback, steroid_take_screenshot,
steroid_input, steroid_fetch_resource). If your harness loads MCP tool schemas on demand, load
them before you decide the IDE is unavailable.

Start here:
- steroid_list_projects - which IDEs and projects are reachable right now.
- No IDE reachable? The `devrig` binary serving this MCP is on PATH as `devrig` (installed at
  `~/.mcp-steroid/bin/devrig`). `devrig backend download --json` lists product ids with their latest stable
  versions; pass `--version <version>` to pin another released version. For unattended Java/JVM
  work on 2026.2, install `devrig backend download idea-ultimate --version <version>`. The multi-GB
  download is cached and resumable; rerun it after a shell timeout.
- Then call steroid_open_project. It starts the sole installed backend automatically and waits until
  the IDE is reachable. IDEA Ultimate 2026.2 runs as an unattended Remote Development backend with
  MCP Steroid included; no frontend window is required.
- Poll steroid_list_projects until the path appears and use its opaque project_name. On a first
  Maven/Gradle open, project listing proves routing, not build-model import: fetch
  `${ExecuteCodeMavenPromptArticle().uri}` or `${ExecuteCodeGradlePromptArticle().uri}`, trigger and
  await external-system configuration exactly as the recipe shows, then run indexed semantic queries.
- steroid_execute_code runs Kotlin against the live IntelliJ API for everything the narrow tool
  surface does not cover.
- steroid_fetch_resource returns the mcp-steroid:// article corpus - the recipes for that API."""

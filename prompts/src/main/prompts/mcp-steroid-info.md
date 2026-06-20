MCP Steroid — a drop-in replacement for LSP that gives you full IntelliJ IDE control.

**To see, inspect, or control the IntelliJ IDEA IDE, you MUST use the `steroid_*` MCP tools** —
`steroid_list_projects`, `steroid_execute_code`, `steroid_list_windows`, `steroid_take_screenshot`,
`steroid_fetch_resource`, and friends. These tools *are* your access to the IDE: the IntelliJ IDE is
running and connected for you right now. If the `steroid_*` tools are **not visible** in your tool list,
your client has **deferred their schemas** (Claude Code does this) — load them with `ToolSearch` (or your
client's equivalent) **before you answer**. **Never report that you have "no IDE access", and never fall
back to the shell or to `Read`/`Grep`/`Edit`, until you have loaded the `steroid_*` tools and actually
tried them** — the IDE is there; the tools just need loading first.

This is a **STATEFUL** API — every call changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations or shell commands.

**File edits: always through MCP Steroid, even when Edit looks cheaper on tokens.** The native `Edit` tool writes to disk bypassing IntelliJ. VFS + PSI + search indices go stale, and the next semantic operation (find-references, rename, hierarchy search, inspections) returns inconsistent results until something forces a refresh. The 5-line `VfsUtil.saveText` recipe in `steroid_execute_code`'s tool description reads+writes in one call with auto-refresh; its 1.5–2.5× token overhead is cheaper than the debugging turns you spend when PSI disagrees with disk. This applies to every edit size, including 1–3 line changes.

**Getting started:**
1. Call `steroid_list_projects` to see what's open — route every project-scoped call by the `project_name` it returns (the unique, opaque key), never by the human-readable `name`; to map a file/dir path to a project, pick the one whose `path` is the longest prefix of your target path
2. Use `steroid_fetch_resource` to read the `mcp-steroid://` skill guide for your task
3. Use `steroid_execute_code` for any IDE automation task (including every file edit)

**Modality — the `modal` option on `steroid_execute_code`.** By default (`modal=smart_non_modal`) the call
closes stray modal dialogs, requires a non-modal IDE, commits/saves documents + refreshes the VFS, and waits
for indexing — all before your script — then closes any modal dialog that appears during the run and fails
the call with diagnostics. This is the safe choice for any PSI / editing / build / test work. `non_modal`
only asserts a non-modal start; `unleashed` does no sweep/checks at all (intentional modal-dialog workflows
or trivial non-PSI actions only). Finer control (`closeModalDialogs()`, `monitorAndCloseModalDialogs()`,
`allowModalDialog()`, `syncDocuments()`, `waitForSmartMode()`) lives in the script-context methods. See
`mcp-steroid://skill/execute-code-tool-description`.

**Design philosophy in one breath.** The MCP tool surface is small **on purpose** — power lives in `mcp-steroid://` recipes that teach you to call IntelliJ's APIs directly inside `steroid_execute_code`. `McpScriptContext` stays narrow. Don't expect new `steroid_*` tools or new context methods; expect richer recipes. Fetch `mcp-steroid://skill/design-philosophy` once if you're new here.

> **Tool schemas may be deferred.** If your client lazy-loads MCP tool schemas (Claude Code does), the `steroid_*` tools above are listed but not callable until you load their schemas — call `ToolSearch` (or your client's equivalent) for `mcp__mcp-steroid__steroid_list_projects`, `…steroid_fetch_resource`, `…steroid_execute_code` before the first invocation. Skipping this surfaces as `InputValidationError` on the first call and a wasted turn. **Tip (Claude Code):** load all three at once with `ToolSearch(query="select:mcp__mcp-steroid__steroid_list_projects,mcp__mcp-steroid__steroid_fetch_resource,mcp__mcp-steroid__steroid_execute_code")` — one round-trip instead of three.

> **Resource URIs are direct.** Pass any `mcp-steroid://<folder>/<id>` URI to `steroid_fetch_resource` exactly as written — `mcp-steroid://ide/find-duplicates` works as-is. The `mcp-steroid://` scheme is the resource address; do not prefix it with the tool's MCP namespace (`mcp__mcp-steroid__…` is for **tool names**, not resource URIs).

**Quick recipes — fetch one and run.** Common one-shot tasks have a ready-made `mcp-steroid://ide/...` article with a copy-paste Kotlin snippet. Reach for these before improvising; they handle threading, IDE-version drift, and typed-vs-reflection traps so you don't have to.

| If the user asks for… | Fetch this resource |
|---|---|
| Find duplicate / cloned / DRY-violation / copy-paste code across the project | `mcp-steroid://ide/find-duplicates` |
| Run a single named inspection + apply its quick fix | `mcp-steroid://ide/inspect-and-fix` |
| List which inspections are enabled in the project | `mcp-steroid://ide/inspection-summary` |
| Apply the same edit across many files atomically | `mcp-steroid://ide/apply-patch` (the `applyPatch { }` DSL inside `steroid_execute_code`) |
| Find usages of a symbol | `mcp-steroid://lsp/find-references` |
| Run a debug session on a test | `mcp-steroid://ide/demo-debug-test` |

The full index of `mcp-steroid://` resources is in `mcp-steroid://prompt/skill`.

**Reflection: exploration only, never in the recipe you ship.** Reflection (`Class.forName`, `getDeclaredField`, `setAccessible(true)`) is a fine *probe* — use it to learn the shape of an unfamiliar class, list its methods, or read the bytecode when the source isn't obvious. It is **not** acceptable in the final code you submit. Every loaded plugin's classes are on the `steroid_execute_code` compile classpath, so the typed `import` and direct method/property access almost always work; if a class genuinely isn't reachable, prefer the cross-classloader pattern in `mcp-steroid://skill/coding-with-intellij-patterns` (`Class.forName(fqn, false, pluginClassLoader).getMethod(name)` — public API only, no `setAccessible`). Private-field reflection silently breaks on the next IDE release. When the right idiom isn't obvious, **read the class bytecode** (`unzip -p <plugin>.jar Foo.class | javap -p` or `Class.getResource("Foo.class")`) to find the public getter you missed.

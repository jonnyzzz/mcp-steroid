MCP Steroid — Design philosophy

The four tenets that govern every MCP Steroid change. Read before proposing a new tool, a new McpScriptContext method, a wrapper helper, or any persistent state in the devrig CLI.

# Four tenets

If a change touches the MCP tool surface, the `McpScriptContext` runtime, the `devrig` CLI's persistence model, or wraps an IntelliJ API in a "helper" — these tenets apply.

## Tenet 1 — minimal MCP tool surface

Don't propose new `steroid_*` tools. The current set is intentional and intentionally small:

- `steroid_list_projects`
- `steroid_list_windows`
- `steroid_open_project`
- `steroid_execute_code`
- `steroid_execute_feedback`
- `steroid_take_screenshot`
- `steroid_input`
- `steroid_fetch_resource`

Improvements come from richer prompt resources (this file is one), sharper tool descriptions, and teaching you to call IntelliJ APIs directly inside `steroid_execute_code` — not from new tools.

A tool is added only when **all** of:

1. The need cannot be met by `steroid_execute_code` + an IntelliJ API call. **Document the specific IntelliJ API path you ruled out.**
2. It cannot be met by a richer `mcp-steroid://` recipe.
3. Three independent reviewers (`run-agent.sh codex` / `claude` / `gemini`) agree, after reading this file. **One reviewer disagreeing kills the proposal** — propose a recipe instead.

Anything short of that — write a recipe instead.

## Tenet 2 — power lives in prompts and direct IntelliJ APIs

Inside `steroid_execute_code`, call IntelliJ's APIs the way IntelliJ exposes them. The full plugin classpath is loaded; typed imports work for `FilenameIndex`, `JavaPsiFacade`, `ProjectTaskManager`, `XDebuggerUtil`, `VfsUtil`, `ReferencesSearch`, `MainPassesRunner`, `Observation.awaitConfiguration`, `smartReadAction { }`, `writeAction { }`, etc.

Don't:

- Wrap an IntelliJ API in an "agent-friendly" abstraction. The unwrapped API is what's evolving in IntelliJ; the wrapper drifts.
- Ask for a new `McpScriptContext` helper to save 5 lines of IntelliJ API. The 5 lines teach you a transferable skill.
- Use reflection (`Class.forName`, `setAccessible(true)`) in the final recipe — only as a probe. The full reflection policy lives in the MCP server's startup instructions (delivered to every agent at session init) and at `mcp-steroid://prompt/skill`.

This is what the strategy page means by "Give AI the whole IDE, not just the files." The **MCP tool surface** stays narrow; the **IntelliJ capability surface** stays full, exposed through `steroid_execute_code` plus recipes like the ones at `mcp-steroid://ide/...` and `mcp-steroid://skill/...`.

## Tenet 3 — devrig is stateless

**The `devrig` binary holds no state across calls.** Every CLI invocation is a fresh process; `devrig mcp` (the stdio MCP server) holds only in-memory caches that live for the duration of the session and are rebuilt from scratch on the next process start.

- **No persistent state on disk** is owned by devrig itself. On-disk artefacts (`~/.mcp-steroid/backends/`, `~/.mcp-steroid/markers/`, download caches) are inputs devrig *reads*, never things it serialises its own state into.
- **No cross-call coordination.** Two `devrig` processes against the same `~/.mcp-steroid` directory must behave identically to one process; see [`docs/devrig-naming.md`](https://github.com/jonnyzzz/mcp-steroid/blob/main/docs/devrig-naming.md).
- **In-memory caches are allowed** within one process — the routing-model snapshot, the marker decoder cache, the installer's per-call working set. They die with the process.
- **Background scanning is implementation-detail, not contract.** Today `devrig mcp` runs marker / port / per-IDE-stream scanners in the background; whether those stay or get replaced by on-demand rebuild is a tactical decision that does not change the caller-visible contract.

Adding state to devrig requires:

1. A written argument that the in-memory + on-call-rebuild model genuinely cannot cover the case. "More efficient" is not enough.
2. Three-reviewer consensus across `run-agent.sh codex` / `claude` / `gemini`. **One reviewer disagreeing kills the proposal.**
3. A migration story: devrig must be deletable + re-installable without losing functionality the user cares about.

## Tenet 4 — `McpScriptContext` methods are last-resort

**Don't add methods to `McpScriptContext` casually.** The current surface (see the `McpScriptContext` source for the exact list — `project`, `disposable`, `printJson(...)`, `progress(...)`, `findProjectFile(...)`, `projectScope()`, the inspection / highlighting helpers, etc.) exists because the IntelliJ API genuinely couldn't cover those cases at the time. They are not the extension point — the IntelliJ API is. The surface is already substantial; that's why "don't grow it" is a tenet, not a preference.

A new context method requires:

1. A written argument that the IntelliJ-native path is genuinely intractable (not just "less convenient").
2. Three-reviewer consensus across `run-agent.sh codex` / `claude` / `gemini`. **One reviewer disagreeing kills the proposal** — propose a recipe instead.
3. The new method teaches an idiom reusable across many tasks, not one specific scenario.

`applyPatch { }` is the cautionary example: the DSL earned its place on `McpScriptContext` for atomic multi-site edits, but eval data showed its exact-match resolution failed 64% of real calls — so it was removed (2026-07, #206) rather than kept as an attractive-but-unreliable surface. The tenet cuts both ways: don't grow the surface casually, and remove what the data shows agents cannot use reliably. A tolerance-matching successor is tracked in #208.

# In practice

When you're about to make a change in this repo, ask in order:

1. Can this be a richer prompt resource? → write the recipe.
2. Can this be solved by calling an IntelliJ API directly inside `steroid_execute_code`? → write the recipe with that snippet.
3. Does it need a new MCP tool or context method? → it almost certainly doesn't. If you still believe so, see the three-reviewer requirement above.

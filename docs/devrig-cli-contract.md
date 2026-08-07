# devrig CLI contract

Status: current since [PR #450](https://github.com/jonnyzzz/mcp-steroid/pull/450), which completed
the command normalization work tracked by issue #284.

This document is the durable contract for devrig's command-line grammar, help, human output, machine
output, direct MCP-tool commands, and agent discovery flows. Project/backend identifier semantics remain
in [`devrig-naming.md`](devrig-naming.md); managed Remote Development lifecycle details remain in
[`devrig-remote-development-backend-e2e.md`](devrig-remote-development-backend-e2e.md).

## One command model

Clikt is the only CLI parser. MCP tool schemas generate the direct-tool commands and their parameters;
there is no parallel hand-written parser for those commands. A schema change must therefore update the
direct CLI grammar, focused help, validation, and MCP invocation together.

The top-level command families are:

```text
devrig
├── list_projects                 aliases: projects, project
├── list_windows
├── execute_code
├── execute_feedback
├── take_screenshot
├── input
├── fetch_resource               alias: prompt
├── open_project
├── backend
│   ├── download
│   ├── start
│   ├── stop
│   └── provision
├── install
│   ├── claude
│   ├── codex
│   ├── gemini
│   ├── config
│   ├── devrig
│   └── plugin
├── mcp
├── tools
├── version
└── help <command> [<subcommand> ...]
```

`list_projects` is canonical. `projects` and the older singular `project` spelling are compatibility
aliases for the same generated leaf; they have identical behavior and output, and JSON still reports
canonical `command: "list_projects"`. New documentation and scripts use `list_projects`.

`devrig prompt <uri>` is the short, positional route to `fetch_resource`. The hidden `--uri` form remains
accepted for compatibility, but help and examples advertise the positional URI.

## Help and recovery

Every command and nested action has both routes:

```console
devrig <command> --help
devrig help <command>
devrig help backend download
devrig tools
```

Root help is an index and a usable first step: the generated command tree, devrig's own options, the
environment variables, and a pointer to the deeper routes — nothing else. The complete generated
"MCP tools as CLI" reference (every tool command's usage line, per-flag synopses, aliases, and the scoped
framework flags) is printed by `devrig tools`. That reference is written for coding agents; it used to
ride as the root-help epilog, where it buried the index. Focused help shows the actual schema-derived
parameters, required choices, defaults, numeric ranges, enum values, aliases, file-input alternatives,
and a runnable usage shape. When parameters are missing or invalid, devrig prints the relevant focused
help and names all missing values in one pass. For example, an incomplete `execute_code` call identifies
`--project_name`, `--task_id`, `--reason`, and the `--code` / `--code-file` choice rather than failing one
field at a time.

Unknown commands fail during parsing. Unsupported orchestration flags fail in the runtime guard before
dispatch or preflight. Neither path may contact a backend, read a file/stdin source, or create an `--out`
target before validation succeeds.

## Direct MCP-tool commands

The schema-generated commands call the same handlers exposed through `devrig mcp`:

| Command | Required input | Important optional input |
|---|---|---|
| `list_projects` | none | `--json` |
| `list_windows` | none | `--json` |
| `execute_code` | `--project_name`, `--task_id`, `--reason`, one of `--code` / `--code-file` | `--modal`, `--timeout`, `--out`, `--json` |
| `execute_feedback` | `--project_name`, `--task_id`, `--success_rating`, `--explanation` | `--execution_id`, `--code` / `--code-file`, `--json` |
| `take_screenshot` | `--project_name`, `--task_id`, `--reason` | `--window_id`, `--out`, `--json` |
| `input` | `--project_name`, `--task_id`, `--reason`, `--window_id`, `--sequence` | `--json` |
| `fetch_resource` / `prompt` | positional URI, `--project_name` | hidden compatibility `--uri`, `--json` |
| `open_project` | `--project_path`, `--task_id`, `--reason` | `--backend_name`, `--trust_project` / `--no-trust_project`, `--wait`, `--json` |

Use the exact focused help as the authority when the tool schema evolves. `task_id` and `reason` are
forwarded unchanged through `open_project`; `execute_feedback` forwards an optional `execution_id`, so a
CLI session can rate the exact execution it observed.

For a schema-declared file source, `--<name>-file=<path>` reads that parameter from a file and
`--<name>-file=-` reads stdin. File and stdin input must be non-empty, strict UTF-8, and no larger than
10 MiB. Human mode announces a stdin read on stderr so a waiting process is understandable; JSON stdout
remains clean.

## Human and machine output

The default presentation is for people: headings, tables, hints, and terminal color where supported.
Diagnostics and progress go to stderr. Stdout is reserved for the requested result, and `devrig mcp`
writes no presentation bytes to stdout before the JSON-RPC stdio server takes over.

Commands that advertise `--json` emit one ANSI-free JSON document. Every schema-generated MCP-tool
command uses this envelope:

```json
{
  "tool": { "name": "devrig", "version": "..." },
  "command": "list_projects",
  "isError": false,
  "data": { "content": [] }
}
```

Structured MCP content is unpacked below `data.content[].json`; it is not left as an escaped JSON string.
Tool failures preserve the envelope and set `isError`. Lifecycle and installation commands keep their
documented command-specific JSON data models, but still emit exactly one ANSI-free document on stdout.

When `--out=<path>` is supported, the file is written only after parsing and input validation succeed.
The normal result still reports what was written. Unsupported flags must fail before either backend calls
or output-file side effects.

## `open_project --wait`

`--wait` is a routing wait, not a claim that the IDE is fully configured. It polls until the requested
canonical path appears through the `list_projects` route, for at most 300 seconds. A successful result
contains:

- `project_name`: the fresh opaque routing key for subsequent project-scoped calls;
- `backend_name`: the backend that owns the route;
- `path`: the canonical project path.

This contract deliberately does not require `list_windows`. A frontendless Remote Development backend can
have no client window, while the project route and all semantic APIs are usable. When an attended frontend
exists, `list_windows` is still useful for modal dialogs and visible indexing/background-task state.

Maven/Gradle configuration is a separate readiness gate. After the route appears, trigger or await the
external-system import before relying on indexed semantic results. The complete sequence is:

1. establish backend/MCP reachability;
2. wait for the requested path and retain its `project_name`;
3. await Maven/Gradle configuration and required indexing;
4. execute the semantic action.

## Validation matrix

The contract is protected at four levels:

1. `:npx-kt:test` covers schema binding, aliases, help and missing-value recovery, enum/range validation,
   file/stdin limits, JSON envelopes, ANSI separation, side-effect ordering, and tool-argument forwarding.
2. `:npx-kt:integrationTest` covers packaged process behavior and stdout/stderr boundaries.
3. `CliDevrigToolsIntegrationTest` in `:test-integration` exercises `list_windows --json`,
   `open_project --wait`, `list_projects --json`, and screenshot `--out` against a real Docker IDE.
4. `DevrigCliCommandNormalizationTest` and `DevrigCliAgentUsabilityExperimentTest` in
   `:test-experiments` cover shell/agent command normalization plus live Claude and Codex task-first,
   help-first, outcome-only, and backend-lifecycle discovery. The separate frontendless Remote Development
   experiment proves that project routing does not depend on a window.

The live agent experiments validated both discovery directions: agents can start from an outcome and find
the right command, or start from root/focused help and reach a successful action. They recovered from
missing values, used the canonical aliases and positional prompt URI, called generated tool commands and
lifecycle commands, and recovered from an empty `execute_code` response by adding an explicit `println`.
Those sessions deliberately had no MCP registration; assertions over raw native-shell events prove the
packaged CLI performed each IDE action rather than taking a direct MCP-tool shortcut.

## Known follow-ups

- `list_windows` human mode pretty-prints the structured payload but still lacks a purpose-built,
  colorful windows/tasks renderer.

Record new CLI-contract gaps in `TODO.md` and add the smallest realistic regression at the corresponding
level above. Do not create a second parser or hand-maintained copy of a tool schema.

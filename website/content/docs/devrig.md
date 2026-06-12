---
title: "Devrig CLI"
description: "A standalone CLI that registers MCP Steroid with your AI Agent, bridges it to a running IDE, and can download and start a managed IntelliJ backend"
weight: 15
group: "Getting Started"
---

> **Independent open-source project.** MCP Steroid and Devrig are independent
> open-source projects. They are **not made by, endorsed by, or affiliated
> with JetBrains s.r.o.** *IntelliJ IDEA, PyCharm, GoLand, WebStorm, Rider,
> CLion, and JetBrains are trademarks of JetBrains s.r.o.*

## What is Devrig?

**Devrig** is a small standalone command-line tool that connects your AI Agent
to a JetBrains IDE running MCP Steroid — with no manual MCP configuration.

It does three jobs:

1. **Registers MCP Steroid with your coding agent.** `devrig install <agent>`
   adds devrig as an `mcp-steroid` stdio MCP server in Claude, Codex, or Gemini.
2. **Bridges your agent to every running IDE at once.** When your agent launches
   it as a stdio MCP server (`devrig mcp`), devrig discovers *all* the
   IntelliJ-based IDEs running on your machine — across projects — and routes the
   agent's MCP Steroid calls to any of them through a single connection. One
   bridge, every IDE.
3. **Provisions an IDE.** `devrig backend download|start|stop` downloads and runs
   a managed IntelliJ backend under devrig's home directory, so an agent can spin
   up an IDE with no manual setup.

### One bridge, every IDE

A single `devrig mcp` process connects your AI Agent to **all** the IntelliJ-family
IDEs running on your machine at once — each open on a different project — and can
download and start more on demand:

<figure style="margin:1.5rem 0;text-align:center;">
<img src="/devrig-bridge.svg" alt="One devrig bridge connects your AI Agent to all running IDEs at once — and can start more" style="width:100%;max-width:720px;height:auto;border-radius:12px;">
<figcaption style="color:#909090;font-size:0.85rem;margin-top:0.4rem;">One <code>devrig</code> process bridges your agent to every IntelliJ-family IDE running on the machine — and can start more.</figcaption>
</figure>

Devrig is a Java application and requires **Java 25** to run. It does not bundle
a JVM: `java` must be on the `PATH`, or `JAVA_HOME` / `DEVRIG_JAVA_HOME` must
point at a Java 25 home.

## Install

Register devrig as the `mcp-steroid` stdio MCP server in your coding agent:

```bash
devrig install claude
devrig install codex
devrig install gemini
```

The agent must be one of `claude`, `codex`, or `gemini`. After a successful
install, devrig prints the agent it registered, the `JAVA_HOME` it recorded, and
the exact stdio command (`devrig mcp`) the agent will run.

## Commands

Run `devrig --help` (or `devrig -h`) for the authoritative usage, and
`devrig --version` (or `devrig -v`) for the version.

### `devrig mcp`

Runs devrig as an MCP stdio server. This is the command your coding agent
launches after `devrig install` — you normally don't run it by hand. While
running, it discovers IDEs and bridges the agent's MCP Steroid calls to them.

> The legacy spelling `devrig mpc` is still accepted as a hidden alias, so
> older agent registrations keep working. Use `devrig mcp` for new setups.

### `devrig install claude|codex|gemini`

Registers this devrig binary as the `mcp-steroid` stdio MCP server in the
selected agent.

### `devrig backend [--json]`

Lists discovered backends (with versions) and the projects each one has open.
`--json` emits a single machine-readable object on stdout (pipe through `jq`);
the default is human-readable text.

### `devrig project [--json]`

Lists open projects across all discovered backends. `--json` emits a single
machine-readable object on stdout; the default is human-readable text.

### `devrig backend download [<id>] [--version <v>] [--json]`

With no `id`, lists the IDEs available for download. With an `id`, downloads and
installs a managed backend under devrig's home directory. The `id` accepts
`<product>`, `<product>:<version>`, or `<product>-<version>` — for example
`idea-community`, `idea-community:2026.1`, or `idea-community-2026.1`.

Known product ids: `idea-ultimate`, `idea-community`, `pycharm-pro`,
`pycharm-community`, `goland`, `webstorm`, `rider`, `clion`, `android-studio`.

### `devrig backend start [<id>] [--version <v>] [--json]`

With no `id`, lists installed backends. With an `id`, starts an installed managed
backend in detached mode and prints its pid, log, and config paths. A
product-only `id` prefers the highest locally installed version.

### `devrig backend stop [<id>] [--version <v>] [--json]`

With no `id`, lists currently running backends. With an `id`, stops a managed
backend by its pid file. A product-only `id` prefers the highest locally
installed version.

### `devrig backend provision [<id>] [--json]`

With no `id`, lists port-discovered IDEs that can be provisioned. With an `id`
(for example `port-63342`), prints manual MCP Steroid plugin install instructions
for that IDE.

## Options and environment

Options that apply to every mode:

| Flag | Effect |
|---|---|
| `--debug` | Enable verbose stderr logging (DEBUG). |
| `--json` | Emit JSON output where supported (`backend`, `project`, and the `backend download/start/stop` subcommands). |
| `--help`, `-h` | Print help and exit. |
| `--version`, `-v` | Print the devrig version and exit. |

Environment variables:

| Variable | Effect |
|---|---|
| `DEVRIG_JAVA_HOME` | JDK/JRE home used to launch devrig (devrig needs Java 25). Overrides `JAVA_HOME` for the devrig process only. |
| `DEVRIG_JVM_OPTS` | Extra JVM options for the devrig launch — for example `-Xmx512m`. |

## Example: an agent provisions an IDE

This is the typical end-to-end flow where an agent gets a working IDE with no
manual setup:

```bash
# 1. Register devrig with your agent (once)
devrig install claude

# 2. Download a managed IntelliJ IDEA Community backend
devrig backend download idea-community

# 3. Start it in detached mode — prints pid, log, and config paths
devrig backend start idea-community

# 4. Confirm it's discoverable and see its open projects
devrig backend
```

Once the backend is running, your agent's MCP Steroid tools (for example
`steroid_list_projects`) are routed to it through devrig's `mcp` stdio server.
Stop the backend when you're done:

```bash
devrig backend stop idea-community
```

## Next Steps

- [Getting Started](/docs/getting-started/) — install the MCP Steroid plugin and connect your AI Agent
- [Connect your AI Agents](/docs/settings-connection-info/) — server URL and ready-to-paste CLI commands
- [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues) — report bugs or request features

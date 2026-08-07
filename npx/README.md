# devrig npm launcher

This package is now only a thin launcher stub for the Kotlin `devrig` CLI.
It does not contain a TypeScript MCP proxy implementation.

Set `DEVRIG_KOTLIN_LAUNCHER` to the Kotlin devrig executable and run:

```bash
npx devrig --help
```

## What devrig does

devrig is a stateless stdio MCP server + CLI that discovers running
IntelliJ instances on the host and routes MCP tool calls to them.
The command grammar, aliases, schema-generated direct-tool help, recovery behavior,
and human/JSON output contracts are specified in
[`docs/devrig-cli-contract.md`](../docs/devrig-cli-contract.md). Project/backend
identifiers and routing are specified separately in
[`docs/devrig-naming.md`](../docs/devrig-naming.md), with the rationale for
on-demand rather than background scanning in
[`docs/devrig-scanning-research.md`](../docs/devrig-scanning-research.md).

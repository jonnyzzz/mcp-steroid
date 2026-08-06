# Testing Strategy

This document summarizes the testing approach. Module guides own the exact commands and operational rules.

## Principles
- Tests must assert real behavior, not just output formatting.
- Integration tests should verify actual MCP tool calls.
- Avoid test-only branches in production code.
- Never run `:test-integration` and `:test-experiments` Docker tests concurrently.

## Test Layers
- Unit tests: core protocol, session management, execution helpers, and devrig schema/help/runtime contracts.
- Process integration tests: packaged devrig behavior, stdout/stderr separation, and stdio MCP transport.
- Stable Docker integration tests: real IDE routing and direct CLI tool calls.
- Experimental Docker tests: Claude/Codex discovery and long-running frontendless backend scenarios.
- OCR tests: run the bundled `ocr-tesseract` helper app against test images.

## Key Coverage
- `ScriptExecutionAvailabilityTest` catches broken script engine quickly.
- `CliDevrigToolsIntegrationTest` validates `list_windows --json`, `open_project --wait`,
  `list_projects --json`, and screenshot `--out` against a real IDE.
- `DevrigCliCommandNormalizationTest` validates safe Claude/Codex shell-command recognition and rejection.
- `DevrigCliAgentUsabilityExperimentTest` validates task-first, help-first, outcome-only, and lifecycle
  discovery with both live agents.
- `DevrigRemoteDevelopmentKeycloakTypeHierarchyTest` proves routing and semantic work without a frontend;
  project routing and Maven/Gradle readiness are separate gates.
- Session handling tests cover unknown-session recovery.

The full CLI behavior and layer ownership are specified in
[`devrig-cli-contract.md`](devrig-cli-contract.md).

## Running Tests
Use the narrowest owning module and test class. See root `CLAUDE.md`, `test-integration/AGENTS.md`, and
`test-experiments/CLAUDE.md`; never run unscoped root `./gradlew test`.

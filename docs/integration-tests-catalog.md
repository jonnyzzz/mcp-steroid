# Integration Tests Catalog

## test-integration (Docker IntelliJ tests)

### MCP Steroid API Validation
| Test | What it validates | Status |
|------|------------------|--------|
| MavenTestExecutionTest | MavenRunConfigurationType + SMTRunner in Docker | FAIL (dialog_killer kills Maven progress) |
| GradleTestExecutionTest | GradleRunConfiguration + setRunAsTest + SMTRunner | PASS |
| MavenCompileTest | ProjectTaskManager.build() on Maven project | PASS |
| GradleCompileTest | ProjectTaskManager.build() on Gradle project | PASS |
| MavenInstallTest | MavenRunner.run() with install goal | Has dialogKiller |
| DockerCheckTest | File.exists("/var/run/docker.sock") in exec_code | Simple, should pass |
| FileDiscoveryTest | FilenameIndex in exec_code | Simple, should pass |

### Agent Behavior Validation
| Test | What it validates | Status |
|------|------------------|--------|
| MavenRunnerAdoptionTest | Agent uses exec_code for tests (not Bash) | Created, untested |
| ResourceReadingTest | Agent reads mcp-steroid:// resources | Created, untested |

### devrig direct CLI validation
| Test | What it validates | Status |
|------|------------------|--------|
| CliDevrigToolsIntegrationTest | Packaged direct CLI: full `list_windows --json` envelope, `open_project --wait` route verified through `list_projects`, and real screenshot `--out` PNG | PASS (3/3) |

### Known Issues
- **dialog_killer too aggressive**: Kills Maven runner's `JobProviderWithOwnerContext` progress dialog, cancelling the Maven execution. Needs whitelist for build runner progress tasks.
- **"Resolving SDKs" false positive**: `UnknownSdkTracker` fires during build, causing `Build errors: true` when compilation actually succeeded. Partially fixed by `mcpResolveUnknownSdks()` step.

## ij-plugin (CLI integration tests)

### Agent Model Validation
| Test | Agent | Model | Status |
|------|-------|-------|--------|
| CliClaudeIntegrationTest | Claude | claude-opus-4-6 | 3/7 pass (kotlinc missing) |
| CliCodexIntegrationTest | Codex | gpt-5.4-xhigh | 2/7 pass (stream disconnect) |
| CliGeminiIntegrationTest | Gemini | gemini-3.1-pro-preview | 4/7 pass (kotlinc missing) |

### MCP Tool Tests
| Test | What it validates | Status |
|------|------------------|--------|
| FetchResourceToolTest | steroid_fetch_resource returns resource content | Created |

## test-experiments

### devrig CLI and frontendless agent validation
| Test | What it validates | Status |
|------|------------------|--------|
| DevrigCliCommandNormalizationTest | 16 safe raw/Codex transport normalization and rejection cases | PASS |
| DevrigCliAgentUsabilityExperimentTest | Claude + Codex task-first, help-to-missing-values-to-action, outcome-only, and lifecycle-help routes without MCP registration | PASS |
| DevrigRemoteDevelopmentKeycloakTypeHierarchyTest | Frontendless backend discovery, project routing, Maven readiness, and semantic hierarchy result | PASS |

### DPAIA arena

17 scenarios × 3 agents (claude, codex, gemini) × 2 modes (mcp, none).
See `docs/arena-3pass-results.md` for full comparison table.

## Update (2026-04-17): ModalityStateMonitor Fix Validated

The `JobProvider` skip in `ModalityStateMonitor` works correctly:
- Log confirms: "Skipping JobProvider modal entity (coroutine progress, not UI dialog)"
- Maven execution starts successfully (no longer killed by dialog_killer)
- New failure: exec_code timeout (5 min) — Maven test execution takes >5 min in Docker
- The Maven test project needs a simpler test (current Calculator tests may be slow due to Maven cold start + Spring context)

## Update (2026-04-17): Maven Test — Async Fix Applied, Project Import Issue Remains

ModalityStateMonitor fix validated: "Skipping JobProvider modal entity" logged correctly.
ProgramRunnerUtil async launch applied (no more invokeAndWait blocking).

Remaining issue: SMTRunnerEventsListener.onTestingStarted never fires in 8 min.
Maven run configuration is created but tests don't execute. Likely cause:
test-project-maven is not properly imported as a Maven project in IntelliJ
before the test runs. The MavenRunConfigurationType.createRunnerAndConfigurationSettings
may need the Maven project to be fully imported (MavenProjectsManager.forceUpdate).

The dialog_killer fix (JobProvider skip) is CORRECT and DONE.
The GradleTestExecutionTest PASSES — Gradle approach is fully validated.

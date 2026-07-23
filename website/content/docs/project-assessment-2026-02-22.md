---
title: "Project Assessment — 2026-02-22"
description: "Comprehensive development assessment covering codebase architecture, commit velocity, test coverage, and executive vision for MCP Steroid"
weight: 35
group: "Vision"
---

## Project Identity

| Field | Value |
|-------|-------|
| **Product** | MCP Steroid — IntelliJ Platform plugin |
| **Current Version** | 0.88.0 |
| **What It Does** | Exposes full IntelliJ IDE APIs to AI agents (Claude, Codex, Gemini) via Model Context Protocol (MCP), enabling agents to write, compile, debug, refactor, and test code through the IDE rather than raw file editing |
| **Creator** | Eugene Petrenko (jonnyzzz) |
| **Website** | [devrig.dev](https://devrig.dev/) |
| **Target Platform** | IntelliJ IDEA 2025.3+ (GoLand, WebStorm expansion underway) |

---

## Development Velocity & Effort

| Metric | Value |
|--------|-------|
| **Project age** | 75 days (Dec 10, 2025 — Feb 22, 2026) |
| **Total commits** | 1,306 |
| **Active development days** | 52 of 75 (69% utilization) |
| **Avg commits per active day** | ~25 |
| **Peak velocity** | 100 commits in 3 days (Feb 20-22) |
| **Contributors** | 1 human developer + automated bots |
| **Releases** | 2 tagged (0.87.0, 0.88.0) + 1 snapshot |
| **Total lines inserted** | 391,197 |
| **Active roadmap** | 1,935 lines across TODO files |

Development pattern: solo developer operating at extremely high velocity with sustained daily commitment. The 25 commits/day average with atomic, descriptive messages indicates a disciplined, professional workflow.

---

## Codebase Size & Structure

### Modules (8 Gradle subprojects + buildSrc)

| Module | Purpose | Source Size |
|--------|---------|-------------|
| **ij-plugin** | Core IntelliJ plugin (MCP server, execution engine, vision, review) | 1.9 MB |
| **test-integration** | Docker-based AI agent integration tests | 3.1 MB |
| **test-helper** | Shared test infrastructure (Docker, processes, agents) | 288 KB |
| **npx** | NPX proxy for multi-IDE server aggregation | 112 KB |
| **agent-output-filter** | NDJSON parsers for Claude/Codex/Gemini output | 72 KB |
| **buildSrc** | Gradle build-time code generation (prompts to Kotlin) | 132 KB |
| **ocr-common / ocr-tesseract** | OCR models and Tesseract integration | 20 KB |
| **ai-agents** | Agent configuration models | 4 KB |

### Code Metrics

| Metric | Count |
|--------|-------|
| Production Kotlin files (src/main) | 528 |
| Production Kotlin LOC | 27,505 |
| Test Kotlin files | 1,806 |
| Test Kotlin LOC | 110,482 |
| Test-to-production ratio | **4:1** |
| Gradle build files (.kts) | 11 |
| Documentation files (.md) | 2,524 |
| AI test scenarios | 10 prompt files |
| Avg file size (production) | ~52 LOC |

---

## Architecture Assessment

### Core Design: 9/10

The architecture follows a layered pipeline with clean separation:

```
Agent (Claude/Codex/Gemini)
  | MCP JSON-RPC over HTTP/SSE
SteroidsMcpServer (Ktor)
  | Tool dispatch
ExecuteCodeToolHandler -> ExecutionManager
  | Two-phase execution
CodeEvalManager (external kotlinc compile) -> ScriptExecutor (coroutine run)
  | Results
ToolCallResult -> Agent
```

### Novel Technical Decisions

**Two-phase compile-then-execute.** Separates Kotlin compilation (external process with daemon recovery) from execution (coroutine-based with timeout). Agents get compilation errors before runtime begins.

**Modal dialog race detection.** Uses Kotlin `select{}` to race script execution against IDE dialog appearance. If a dialog pops up, execution cancels and a screenshot is returned to the agent.

**External Kotlin compiler isolation.** Runs `kotlinc` as a separate process so agent scripts cannot starve the IDE's own Kotlin daemon. Includes automatic daemon recovery on "Service is dying" errors.

**Transport-agnostic MCP core.** `McpServerCore` is pure JSON-RPC with zero HTTP dependencies. Transport (currently Ktor HTTP/SSE) is pluggable for future stdio or gRPC transports.

**Human review workflow.** Gate between agent code and execution with diff generation, timeout-based approval, and configurable trust levels.

**Append-only execution storage.** Immutable audit trail of every script, compilation output, and execution result under `.idea/mcp-steroid/`.

**Build-time prompt compilation.** Markdown prompt files compiled to obfuscated Kotlin classes with content validation tests auto-generated.

---

## Code Quality Patterns

**Coroutine discipline:**
- Zero `runBlocking()` in production code
- `SupervisorJob` for storage I/O isolation
- `withContext(Dispatchers.EDT)` for UI access
- `select{}` for races (modal dialog detection)

**IntelliJ Platform best practices:**
- Proper `readAction{}` / `writeAction{}` for threading
- `service<T>()` for dependency lookup (no singletons)
- `@Service(Service.Level.PROJECT)` annotations
- Disposable lifecycle for cleanup
- `ProcessCanceledException` always rethrown

**Error handling:**
- Multi-layer with smart recovery (Kotlin daemon, incomplete code detection)
- Fast-fail semantics (compilation errors reported before execution)
- Modal dialog detection with screenshot capture
- `SupervisorJob` ensures storage writes complete even on cancellation

---

## Testing Sophistication

### Test Strategy: 8.5/10

| Layer | Coverage |
|-------|----------|
| **Unit tests** | MCP protocol, script execution, output parsing, prompt parsing |
| **Integration tests** | Full MCP handshake, tool call workflows, session management |
| **Docker AI agent tests** | Real Claude/Codex/Gemini agents in isolated containers with IntelliJ IDE |
| **Arena tests** | Multi-project benchmarks (Spring Boot, Petclinic, microservices) with A/B comparison |
| **AI scenario tests** | 10 manual prompt-based validation scenarios |
| **Generated tests** | Auto-generated content validation for compiled prompts |

The Docker-based AI agent integration tests launch a full IntelliJ IDE in a Docker container with VNC display, connect real AI agents, and verify end-to-end MCP workflows. The arena tests run curated project benchmarks comparing agent performance with and without MCP Steroid.

---

## Commit Quality & Themes

### Commit Discipline

- Atomic commits with descriptive messages
- Conventional-commit-like prefixes (`feat()`, `fix()`, `refactor()`, `test()`, `docs()`)
- Task tracking references (T1-T7, R3-R14)
- Linear history with no merge commits

### Development Themes

| Theme | Share | Description |
|-------|-------|-------------|
| Docker/Integration Testing | ~30% | Container infrastructure, agent sessions, arena tests |
| Core Features | ~20% | Execution engine, MCP tools, vision, review workflow |
| Refactoring | ~15% | Process runner, settings, code organization |
| DPAIA Arena Runs | ~15% | Benchmark experiment data |
| Documentation/Website | ~10% | Hugo site, guides, release notes |
| Release Engineering | ~5% | Version bumps, build matrix, smoke tests |
| Bug Fixes | ~5% | Dialog handling, layout, output parsing |

---

## Quality Scorecard

| Dimension | Score | Notes |
|-----------|-------|-------|
| **Code Organization** | 9/10 | Excellent modularity, clean separation of concerns |
| **Error Handling** | 9/10 | Multi-layer with smart recovery |
| **Test Coverage** | 8.5/10 | Unit + Docker integration strong |
| **Architectural Sophistication** | 9/10 | Two-phase execution, external compiler, modal racing, append-only storage |
| **Coroutine Patterns** | 9.5/10 | Textbook IntelliJ + kotlinx.coroutines |
| **Documentation** | 8/10 | CLAUDE.md excellent, TODO tracking detailed |
| **IntelliJ Platform Integration** | 9/10 | Service model, read/write actions, disposable lifecycle all correct |
| **Extensibility** | 9/10 | McpRegistrar extension point makes adding tools trivial |

**Overall: 8.8/10** — Production-grade, well-architected MCP server plugin with several novel approaches to IDE integration and error resilience.

---

## Competitive Positioning & Value

### What Makes This Unique

MCP Steroid occupies a category-defining position: it is the bridge between AI coding agents and professional IDE capabilities. While agents typically work at the file-editing level (text in, text out), MCP Steroid gives them access to:

- **Code intelligence** — find usages, go to definition, symbol search
- **Refactoring** — rename, extract method, move
- **Compilation & build** — real Kotlin/Java compilation with error reporting
- **Debugging** — breakpoints, step-over, variable inspection
- **Code analysis** — inspections, warnings, quick-fixes
- **Visual IDE** — screenshots, component trees, OCR, input simulation
- **Human oversight** — code review before execution

### Estimated Development Value

| Factor | Assessment |
|--------|-----------|
| **Developer effort** | ~52 full days of intensive solo development |
| **Equivalent team-months** | 2-3 months of a senior Kotlin/IntelliJ platform developer |
| **Domain expertise required** | Deep IntelliJ Platform SDK + MCP protocol + coroutines + Docker + multi-agent testing |
| **Hand-written code** | ~28K production + ~110K test (138K total) |
| **Infrastructure** | Multi-module Gradle build, Docker test harness, Hugo website, NPX proxy, build-time codegen |
| **Replacement cost** | At senior IntelliJ plugin developer rates, 3-6 months FTE |

---

## Executive Vision

**MCP Steroid transforms AI coding agents from text editors into IDE-native developers.**

Today's AI agents (Claude, Codex, Gemini) work by reading and writing files. They cannot compile, debug, refactor, or run code analysis — the core activities of professional software development. MCP Steroid bridges this gap by exposing the full IntelliJ IDEA runtime to agents via the Model Context Protocol.

**Key differentiators:**

- **Only product** providing visual IDE access (screenshots + input dispatch) to AI agents
- **Transport-agnostic MCP server** running inside the IDE process with full API access
- **Multi-agent support** — works with Claude Code, OpenAI Codex, Google Gemini CLI
- **Human-in-the-loop review** — configurable approval gates before code execution
- **Battle-tested** — Docker-based integration tests with real agents on real projects

**Market position:** First-mover in the "AI-IDE bridge" category. As AI agents become the primary interface for software development, the IDE becomes the execution environment they need but cannot access. MCP Steroid is that access layer.

**Current state:** v0.88.0, actively developed at high velocity, comprehensive test coverage, production-ready for IntelliJ IDEA 2025.3+, with expansion to GoLand and WebStorm underway.

**Growth vectors:** Multi-IDE support (already started), NPX proxy for multi-instance aggregation, arena benchmarking framework for agent quality measurement, enterprise deployment via custom plugin repository.

---

## Support the Project

MCP Steroid is built and maintained by a solo developer. Continued development, testing infrastructure (Docker-based agent tests are compute-intensive), and multi-IDE expansion all require sustained funding.

**How you can help:**

- **Sponsor on GitHub** — [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
- **Submit real-world scenarios** — share your repositories and workflows so we can benchmark and improve. See [Support the Project](/docs/need-your-experiments-and-support/) for the submission template
- **Share usage logs** — the `.idea/mcp-steroid` folder in your project contains tool call logs that help us fine-tune prompts and skills
- **Report issues** — [github.com/jonnyzzz/mcp-steroid/issues](https://github.com/jonnyzzz/mcp-steroid/issues)
- **Join the community** — [Discord server](https://discord.gg/e9qgQ7NeTC) or message Eugene on [LinkedIn](https://linkedin.com/in/jonnyzzz)

Engineering leaders and sponsors interested in pilot evaluations or benchmark expansion are welcome to reach out directly.

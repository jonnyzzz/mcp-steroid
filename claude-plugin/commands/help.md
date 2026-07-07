---
description: See what the devrig IDE bridge can do, with copy-paste example prompts.
disable-model-invocation: true
---

The user ran `/devrig:help`. Show them what the **devrig** bridge can do and give
copy-paste example prompts. This is **read-only** — do not run anything unless the
user asks.

Present the content below, lightly adapted, in your own concise formatting. Do NOT
paraphrase away the example prompts — the user should be able to copy them verbatim.
Do not call any tool just to render this; only act if the user picks an example.

---

**devrig gives Claude the whole JetBrains IDE, not just the files.** With a JetBrains
IDE open (IntelliJ IDEA, PyCharm, WebStorm, Rider, GoLand, …) and the MCP Steroid
plugin running, Claude can drive it — running code, tests, refactorings, inspections,
the debugger, and PSI-accurate navigation in the *actual* IDE, not a re-implementation.

Try one of these — just paste it as your next message:

**Run & test**
- `run the tests in the open IDE`
- `run the failing test and show me why it fails`
- `build the project and report any errors`

**Inspect & find**
- `find duplicates in this file`
- `show the compilation errors in the project`
- `run inspections on the current file and apply the safe quick-fixes`

**Refactor & navigate**
- `rename this symbol across the project`
- `find all usages of this class`
- `where is this method defined?`

**Debug**
- `set a breakpoint on line N and run the debugger`
- `show me the variables at the current breakpoint`

Related commands:
- `/devrig:status` — is devrig installed and connected? (read-only)
- `/devrig:setup` — pre-download or repair the devrig bridge

If a JetBrains IDE isn't open yet, open one with the MCP Steroid plugin installed and
the tools connect automatically — no restart needed.

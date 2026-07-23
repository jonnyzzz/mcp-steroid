---
title: "Strategy"
description: "How MCP Steroid helps your AI agent change code with fewer cleanup loops"
weight: 30
group: "Vision"
aliases:
  - /strategy/
---

## Reliable code changes for AI agents

MCP Steroid makes your AI agent change code with fewer cleanup loops. File-only workflows break on real tasks because the
agent cannot run inspections, execute refactorings, launch debugger flows, or use live IDE context and actions. The larger
the repository, the more research the agent must do -- and the more tokens it burns -- without IDE-grade understanding.

MCP Steroid closes that gap by giving AI agents the same semantic actions JetBrains IDEs give humans -- typed refactors,
inspections, debugger, test runs -- so the agent finishes in fewer attempts and humans spend less time verifying its output.

## Strategic thesis

devrig is an AI-agent-first product: the CLI you install and run. MCP Steroid is how it reaches your IDE -- today delivered
as a JetBrains IDE plugin, which is where existing users already work. The long-term product direction is the same surface
in a self-contained runtime so it serves AI agents anywhere they execute, not only when a developer's IDE is open.

On tasks that depend on IDE capabilities, AI agents with devrig should complete more work with fewer interventions,
lower token usage, and less rework on the human side than the same AI agents without it.

## Three-phase product arc

1. **Phase 1 -- Plugin distribution:** ship the surface as a JetBrains IDE plugin (current)
2. **Phase 2 -- Fine-tune:** evals, benchmarks, prompt optimization
3. **Phase 3 -- Scale:** self-contained runtime, packaging, SaaS, B2B distribution

### Phase 1: Plugin distribution (current)

Today MCP Steroid runs as a plugin inside JetBrains IDEs. A developer connects their AI agent 
(Claude Code, Codex, Gemini, or any MCP client) to a running IDE instance -- IntelliJ IDEA, PyCharm, Android Studio, Rider, and others -- where their project is already open.

### Phase 2: Fine-tune -- evals, benchmarks, learn, and iterate

We run the same task twice -- same model, same repo -- once with the IDE through MCP Steroid and once as a plain shell
agent, and score every run on machine-checkable evidence rather than on what the agent claims about itself. See the
[experiment findings](/docs/experiment-findings/) for the verified results, including the runs where the IDE path did not win.

We are collecting scenarios and execution logs from real MCP Steroid sessions (share your `.idea/mcp-steroid` folder with us).

The collected data is analyzed to identify sharp edges in the current implementation and to improve prompts, skills,
and documentation. AI agents help us craft the better product for AI agents. This is an iterative process; we have
completed roughly seven optimization rounds so far, primarily on the MCP Steroid project itself.

This validation loop is described in [Learning Methodology](/docs/learning-methodology/). See also [IntelliJ as a Skill Factory](/docs/skill-factory/) for how skills turn one-off API explorations into reusable AI agent capabilities.

### Phase 3: Scale -- self-contained runtime, SaaS, B2B

The long-term target is a self-contained runtime, available both as SaaS and as an end-user product, that runs the IDE for
AI agents without a developer's desktop session. That runtime still gives the IDE a real display -- the normal GUI on
macOS/Windows, or a virtual one via Xvfb on Linux and CI; a true headless launch (`-Djava.awt.headless=true`) is
unsupported (see [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177) and [Running devrig in CI](/docs/running-on-ci/)).

## Easy experimentation

MCP Steroid provides an easy way to experiment with new tasks, prompts, and skills locally. Create a new skill, ask your AI agent to use `steroid_execute_code`, and give it an example code snippet using IntelliJ API to solve your goal:

```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope

// Example: find all TODO comments in the project
val todoItems = readAction {
    val searchHelper = PsiSearchHelper.getInstance(project)
    val result = mutableListOf<String>()
    searchHelper.processCommentsContainingIdentifier("TODO", GlobalSearchScope.projectScope(project)) { comment ->
        result.add("${comment.containingFile.virtualFile.path}: ${comment.text.trim()}")
        true
    }
    result
}
todoItems.forEach { println(it) }
```

The [Debugging IDE with MCP Steroid](/docs/how-to-debug-ide/) guide was written entirely by AI agents using this approach -- a real skill created through experimentation with full IDE access.

## How you can help

- **Developers:** submit reproducible scenarios via [Need Your Experiments and Support](/docs/need-your-experiments-and-support/) and engage in the community
- **Engineering leaders:** request pilot evaluations on your repositories -- we are eager to learn alongside you
- **Sponsors and investors:** support benchmark expansion and productization

## Creator

MCP Steroid is built by [Eugene Petrenko](https://linkedin.com/in/jonnyzzz), with 21 years of JetBrains ecosystem experience.

## Contact

- [LinkedIn](https://linkedin.com/in/jonnyzzz)
- [GitHub](https://github.com/jonnyzzz/mcp-steroid)
- [GitHub Sponsors](https://github.com/sponsors/jonnyzzz)

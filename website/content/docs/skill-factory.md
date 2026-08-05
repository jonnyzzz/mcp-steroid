---
title: "IntelliJ as a Skill Factory"
description: "Build reusable agent skills from IntelliJ APIs without plugin development"
weight: 41
group: "Vision"
aliases:
  - /skill-factory/
---

Every time an agent needs an IntelliJ API it hasn't seen before, it burns tokens figuring out PSI trees and threading rules. It tries something, gets a compilation error, tries again, eventually gets it right. Then the next session starts the cycle over.

Skills break this cycle. Each solved problem becomes a reusable Kotlin snippet that any agent can call without re-discovering the API surface.

## The Two-Phase Workflow

### Phase 1: Research

The agent experiments with IntelliJ APIs via `steroid_execute_code`. It reads the 60+ built-in MCP resources, tries things, fails, iterates -- maybe 4-8 retries. This phase is messy and token-heavy. That's fine. The agent is learning.

For complex tasks, point the agent at IntelliJ Community sources or your third-party plugin sources for deeper research.

### Phase 2: Encapsulation

Once the agent figures out how to solve the problem, the working approach gets wrapped into a reusable skill -- a Kotlin snippet stored as a markdown document. Now any agent can call it without re-learning the API.

That's the skill factory. Skills accumulate. The agent that struggled for 8 retries to find deprecated methods with zero usages? Next time it takes one call.

## Why Skills, Not Plugin Code

Writing a skill is **one markdown file with a Kotlin code snippet**. Writing the same capability as traditional plugin code means a Gradle project, extension points, build infrastructure, and a full development cycle. Skills skip all of that -- the agent handles compilation, threading, and iteration for you.

This also works with sub-agent architectures. A parent agent delegates a specific task to a sub-agent that reads only the relevant skill documentation from MCP resources. The sub-agent iterates until it works, and the parent's context stays clean.

## Example: Find All TODO Comments

```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope

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

This is a complete, working skill. An agent sends it through `steroid_execute_code` and gets back every TODO comment with its file path. No plugin development needed.

## Enterprise: Your Own Skills

Even behind an enterprise firewall with proprietary IntelliJ plugins and internal APIs, you can create skills that wrap your own extensions. Your agent doesn't need to know the internals of your company's custom inspection plugin -- it just calls the skill.

Open-source skills for public IntelliJ APIs, private skills for internal tooling. A team builds up a skill library for their specific codebase and workflows, and every agent on the team benefits.

## Creating a Skill

1. Pick an IDE capability you use manually -- navigate to declaration, find usages of a deprecated API, list failing tests with stack traces
2. Point your agent at it with `steroid_execute_code` and let it iterate
3. When it works, save the Kotlin snippet as a markdown file
4. Open a pull request or keep it in your private skill library

You don't need to be an IntelliJ platform expert. The agent does the API exploration. You need to know what IDE capability you want to expose -- the rest is iteration.

## What's Next

The immediate focus is skill coverage -- more documented API patterns, more worked examples, more pre-built skills. The goal is to shrink the research phase for common tasks until it's nearly zero.

Running in Docker and CI already works today: use the normal GUI on macOS/Windows, Xvfb for an attended Linux IDE, or the supported frontendless IDEA Ultimate 2026.2 Remote Development backend (see [Running devrig in CI](/docs/running-on-ci/)). Remote Development product mode takes precedence over the raw AWT-headless flag; only plain non-backend headless mode is unsupported ([#177](https://github.com/jonnyzzz/mcp-steroid/issues/177)). Longer term: event-driven skills (reacting to commits, test failures, inspection results).

---

*Based on the blog post [IntelliJ as a Skill Factory](https://jonnyzzz.com/blog/2026/04/08/mcp-steroid-skill-factory/) by Eugene Petrenko.*

---
title: "What your AI agent can do"
description: "The IDE actions devrig and MCP Steroid give your coding agent, who it helps, and the agents and IDEs it works with"
weight: 12
group: "Getting Started"
---

Once [devrig](/docs/devrig/) connects your agent to a JetBrains IDE through MCP Steroid,
the agent stops guessing through file edits and calls the IDE's real semantic actions.

## What your AI Agent can do

<ul class="features-list">
    <li><strong>Refactor safely</strong>: rename a symbol across the whole project in one operation, extract methods, move classes</li>
    <li><strong>Debug</strong>: set breakpoints, step through code, inspect variables &mdash; all programmatically</li>
    <li><strong>Run inspections</strong>: surface real errors before committing, not just syntax issues</li>
    <li><strong>Execute tests</strong>: run and analyze test results without leaving the AI Agent flow</li>
    <li><strong>See the IDE</strong>: screenshot capture, UI interaction, modal dialog handling</li>
    <li><strong>Run without an open IDE</strong>: with <code>devrig</code>, the agent connects to a running IDE &mdash; or downloads and starts one itself</li>
    <li><strong>Integrate third-party plugins</strong>: call APIs from IntelliJ plugins loaded in the IDE &mdash; internal tooling, language plugins, custom inspections &mdash; without a custom MCP server</li>
    <li><strong>Go beyond the built-in MCP server</strong>: run IntelliJ API through <code>steroid_execute_code</code> &mdash; not just a fixed catalogue of file/search tools</li>
    <li><strong><a href="/docs/skill-factory/">Create skills from the running IDE</a></strong>: pass an IntelliJ API snippet yourself, or let your AI Agent learn from the running IDE and write the Skill for you</li>
</ul>

## Who it helps

<div class="hero-cards hero-cards-three">
    <div class="hero-card">
        <h3>When you ship features</h3>
        <p>Your AI Agent drives a real JetBrains IDE on your code &mdash; safe renames, extract/move refactors, inspections, the debugger, and test runs &mdash; using the same tools you would.</p>
    </div>
    <div class="hero-card">
        <h3>When you review &amp; verify</h3>
        <p>Your AI Agent runs the same inspections, build, and tests you use to check your own work, inside the IDE, before opening a review.</p>
    </div>
    <div class="hero-card">
        <h3>When you run a team or platform</h3>
        <p>Point your AI Agents at a large codebase: <code>devrig</code> provisions a managed IDE backend so agents get the same semantic tools. We also run Proof-of-Concept engagements tuned to your repos.</p>
    </div>
</div>

## Works with your agents and IDEs

Use it with MCP-capable coding agents &mdash; including Claude, Codex, Gemini, Cursor, and OpenCode &mdash;
and with IntelliJ-family IDEs such as IntelliJ IDEA, PyCharm, GoLand, WebStorm, Rider, and Android Studio.

<div class="agents-list">
    <span class="agent-badge">Claude</span>
    <span class="agent-badge">GPT</span>
    <span class="agent-badge">Gemini</span>
    <span class="agent-badge">Codex CLI</span>
    <span class="agent-badge">Cursor</span>
    <span class="agent-badge">OpenCode</span>
</div>

## Next steps

- [Install devrig](/docs/devrig/) and register your agent
- [Getting Started](/docs/getting-started/) with the MCP Steroid plugin
- [Build custom skills](/docs/skill-factory/) from the running IDE
- [Watch demos](/demos/) of the agent working inside the IDE

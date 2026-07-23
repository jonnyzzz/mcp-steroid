---
title: "Reinforced Learning Approach"
description: "How MCP Steroid improves agent reliability with reproducible experiments"
weight: 40
group: "Vision"
aliases:
  - /learning-methodology/
---

## Why this exists

MCP Steroid targets fast-changing AI Agents on the fast-changing IntelliJ-based IDE platform. Static documentation alone
is not enough. We need a repeatable loop that measures what works, what fails, and what should change next.

We treat the MCP server itself as an always-improving product that learns from usage. Every prompt, skill description, and tool schema is 
refined through data-driven iteration -- a user manual designed specifically for AI Agents, our primary audience.

## The operating loop

Every agent call is recorded in the `.idea/mcp-steroid` folder. Each invocation includes the caller's stated _reason_
for the call, and agents periodically send feedback with a text message and a score.

This telemetry drives continuous improvement. We analyze call patterns, failure modes, and feedback signals
to refine prompts, tool schemas, and skill descriptions. The result is an MCP server that handles increasingly
sophisticated tasks across the full surface of IntelliJ-platform IDEs -- including third-party plugins and extensions,
both private and public.

We are looking for your support:
- Share your `.idea/mcp-steroid` logs from real plugin usage
- Submit complete project and task scenarios for benchmarking

Submissions are accepted through [Need Your Experiments and Support](/docs/need-your-experiments-and-support/).

See [IntelliJ as a Skill Factory](/docs/skill-factory/) for how this learning process feeds into reusable agent skills.

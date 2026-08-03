---
title: "Support the Project"
description: "Share reproducible scenarios to help improve MCP Steroid on real repositories"
weight: 20
group: "Getting Started"
aliases:
  - /need-your-experiments-and-support/
---

## Why community scenarios matter

MCP Steroid improves by learning from real-world usage. The more diverse the repositories, 
languages, and workflows we test against, the more reliable the product becomes for every user.

If your repository works in an IntelliJ-based IDE, it is in scope. Support the project and share your scenarios with us.

## Usage logs sharing

Beyond full scenario submissions, you can help by sharing your tool call logs.
The `~/.mcp-steroid/runs` folder contains a log of every stored tool call across
your projects. Each run records its project identity. This data helps us fine-tune prompts, skills,
and documentation to make agents more effective.

For details on how we process this data, see [Learning Methodology](/docs/learning-methodology/).

## Project scenarios sharing 

Please provide:

1. **Repository pointer** -- public repo, temporary private access, or archive
2. **Commit SHA** -- exact revision to reproduce from
3. **Agent client/model/version** -- the exact agent setup used
4. **Baseline setup** -- toolchain and prompt flow without MCP Steroid, ideally a Docker container.
5. **Task prompt** -- the exact prompt given to the agent
6. **Review prompt** -- how the output was evaluated
7. **Acceptance criteria** -- binary checks and required artifacts
8. **Environment constraints** -- OS, IDE build, branch policy, time limits
9. **Data-handling constraints** -- public, anonymized, or private with restrictions

Submit via [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues).

### How we process submissions

1. Open the repository in an IntelliJ-based IDE
2. Connect MCP Steroid to a supported agent
3. Run baseline (without MCP Steroid) and treatment (with MCP Steroid) using orchestrated sub-agents
4. Capture run artifacts and compare completion rate, manual interventions, regressions, token cost, and wall-clock time
5. Convert stable findings into documentation, prompts, and benchmark tasks

Methodology details: [Learning Methodology](/docs/learning-methodology/).

## What you get back

- Run artifact package with full logs
- Baseline vs. treatment pass/fail report
- Clear list of limitations encountered (if any)
- Follow-up action items tailored to your workflow

## How you can support

- **Developers**: submit scenarios and issue reports with minimal reproductions
- **Engineering leaders:** request pilot evaluations on your repositories -- we are eager to learn alongside you
- **Sponsors and investors:** support benchmark expansion and productization

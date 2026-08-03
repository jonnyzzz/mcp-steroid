# Contributing to MCP Steroid

We welcome contributions to MCP Steroid! Whether you are fixing a bug, adding a feature,
improving tests, or enhancing documentation, your help makes the project better for everyone.

## Getting Started

1. **Check existing issues** -- browse [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues)
   for open tasks, bug reports, and feature requests before starting work.
2. **Open an issue first** -- for non-trivial changes, open an issue describing what you plan to do.
   This avoids duplicate work and lets us give early feedback on the approach.
3. **Fork and branch** -- fork the repository and create a feature branch from `main`.

## What We Are Looking For

- **Bug fixes** with a failing test that demonstrates the issue
- **New tests** that cover untested scenarios or edge cases
- **Feature implementations** linked to an existing issue
- **Documentation improvements** that clarify usage or architecture
- **Scenario submissions** -- share reproducible test scenarios from real repositories
  (see [Support the Project](https://devrig.dev/docs/need-your-experiments-and-support/))

## Pull Request Guidelines

1. **Describe your changes** -- every PR must include a clear description of what it does and why.
   Reference the related issue using `#<number>` (e.g., "Fixes #12").
2. **Include tests** -- bug fixes must include a failing test that passes after the fix.
   New features should have test coverage. We value tests that reflect reality over tests that
   pass trivially.
3. **Keep PRs focused** -- one logical change per PR. Avoid mixing refactoring with feature work.
4. **Follow project conventions** -- read [CLAUDE.md](CLAUDE.md) for coding standards, banned
   patterns, and architectural guidelines.
5. **Build and test locally** -- run `./gradlew build` and `./gradlew test` before submitting.

## Development Setup

- **JDK 25** or later (matches IDEA 2026.1's bundled JBR; build output targets **Java 21 bytecode** so the plugin also loads in JBR-21 IDEs like Android Studio)
- **Gradle 8.11.1** (wrapper included)
- **IntelliJ IDEA 2026.1+** recommended for development
- **Docker** required for integration tests

Key Gradle tasks:

| Task | Description |
|------|-------------|
| `build` | Compile and run checks |
| `test` | Run unit tests |
| `buildPlugin` | Build the plugin ZIP |
| `deployPlugin` | Hot-reload plugin into running IDE |

## Reporting Issues

When reporting a bug, please include:

- Steps to reproduce the issue
- Expected vs. actual behavior
- IDE version, OS, and MCP Steroid version
- Relevant log snippets (from `~/.mcp-steroid/runs/` if applicable)

Feature requests should describe the use case and motivation, not just the desired solution.

## Community

- **GitHub Issues**: https://github.com/jonnyzzz/mcp-steroid/issues
- **Discord**: https://discord.gg/e9qgQ7NeTC

---

## Contributor License Agreement

*Version 1.0, effective 2026-04-10*

This Contributor License Agreement ("Agreement") covers all present and future
Contributions You make to MCP Steroid. You need to accept it only once; it applies to
every Contribution You submit after acceptance.

### 1. Definitions

"Contribution" means any original work of authorship intentionally submitted by You for
inclusion in MCP Steroid. This includes patches, code, documentation, tests, scenarios,
configuration, and other materials intentionally submitted for incorporation through
project-managed channels (pull requests, issue trackers, code review comments, or other
written or electronic communication). General discussion, bug reports, feature requests,
design feedback, and support questions are not Contributions unless You clearly state that
accompanying material is submitted for incorporation. Communications that are conspicuously
marked or otherwise designated in writing as "Not a Contribution" are excluded.

"You" (or "Your") means the individual contributor accepting this Agreement in a personal
capacity. If a company, employer, or other legal entity owns or may own rights in a
Contribution, that Contribution may be submitted only under a separate corporate
contributor agreement accepted by the Project Owner (see Section 6(d)).

"Project Owner" means Eugene Petrenko (mcp@jonnyzzz.com), or a successor steward of the
MCP Steroid project that receives this Agreement and the rights granted under it by written
transfer. If stewardship of the project changes, the transfer will be announced publicly
before new Contributions are accepted under this Agreement.

### 2. Acceptance

By submitting a pull request to this repository, You indicate that You have read and agree
to this Agreement. Electronic acceptance of this Agreement is intended to have the same
legal effect as a handwritten signature to the fullest extent permitted by applicable law.
The Project Owner may require confirmation of acceptance through a CLA verification
workflow before merging a pull request. If a pull request contains commits from multiple
authors, each author must independently accept this Agreement.

### 3. Pre-Acceptance License and Copyright Assignment

From the time You submit a Contribution, You grant the Project Owner a limited,
non-exclusive, worldwide, royalty-free license to review, reproduce, run, test, internally
modify, and prepare internal evaluation copies of that Contribution solely as reasonably
necessary to evaluate, discuss, and decide whether to accept it.

If the Project Owner accepts the Contribution for inclusion in MCP Steroid -- whether as
submitted or in modified form, and whether by merge, squash, cherry-pick, direct commit,
or other incorporation into the repository or released materials -- You assign to the
Project Owner, to the maximum extent permitted by applicable law, all right, title, and
interest that You own in the copyright of the portion of the Contribution that is actually
incorporated into MCP Steroid, whether directly or in modified form.

Where such assignment is not effective under applicable law, You instead grant the Project
Owner a perpetual, worldwide, irrevocable, royalty-free, transferable, sublicensable
license to use, reproduce, modify, prepare derivative works of, publicly display, publicly
perform, distribute, sublicense, enforce, and relicense the Contribution and any derivative
works thereof, in any form and for any purpose.

Where assignment is effective, the Project Owner grants You back a perpetual, worldwide,
non-exclusive, no-charge, royalty-free license to use Your Contribution in any way, so You
may continue to use Your own work without restriction.

At the Project Owner's reasonable request and expense, You will execute and deliver further
documents reasonably necessary to confirm, record, or perfect the assignment or fallback
license described in this Section.

### 4. Patent License

You grant the Project Owner and recipients of software distributed by the Project Owner a
perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable (except as stated
in this section) patent license to make, have made, use, offer to sell, sell, import, and
otherwise transfer Your Contribution, where such license applies only to patent claims
licensable by You that are necessarily infringed by Your Contribution alone or by
combination of Your Contribution with the project to which it was submitted. If any entity
institutes patent litigation against You or any other entity (including a cross-claim or
counterclaim in a lawsuit) alleging that Your Contribution or the work to which it was
submitted constitutes direct or contributory patent infringement, then any patent licenses
granted to that entity under this Agreement for that Contribution or work terminate as of
the date such litigation is filed.

### 5. Attribution

Unless You request otherwise before acceptance, the Project Owner may credit You as a
contributor in contributor lists, release notes, documentation, and the project website.
To opt out of project-maintained public acknowledgements, or to request a preferred display
name, notify the Project Owner before the Contribution is accepted for inclusion -- in a
pull request comment, or by email to mcp@jonnyzzz.com. Each contributor, including
co-authors, may make their own attribution request. The Project Owner will apply that
preference to future release notes, contributor lists, website acknowledgements, and other
maintained materials under the Project Owner's control, but cannot alter public Git history,
commit metadata, discussion records, or third-party mirrors already visible on the hosting
platform.

### 6. Representations

You represent that:

(a) You are the original author of the Contribution, or You have sufficient rights to
    submit the Contribution under the terms of this Agreement.

(b) You are legally entitled to grant the rights in this Agreement and You have disclosed
    any third-party license, patent, trademark, or other restriction of which You are
    personally aware that applies to any part of the Contribution.

(c) If Your Contribution includes material subject to a third-party license, You have
    clearly identified that material and its license terms in the Contribution.

(d) If Your employer or another legal entity owns or may own rights in the Contribution,
    You may submit it only if You have written authorization from that entity that is
    sufficient to allow this Agreement, or if an authorized signer for that entity has
    executed a separate corporate contributor agreement acceptable to the Project Owner.
    Contact mcp@jonnyzzz.com for the corporate agreement process.

(e) To Your knowledge, submitting the Contribution does not breach any confidentiality
    obligation, employment duty, or other agreement binding on You.

You agree to notify the Project Owner promptly if You become aware of any facts or
circumstances that would make the representations in this Agreement inaccurate in any
respect.

### 7. Project License

Accepted Contributions may be distributed as part of MCP Steroid under the Apache
License 2.0. Because Section 3 assigns copyright in accepted Contributions, or grants the
fallback license described there where assignment is ineffective, the Project Owner may also
distribute, sublicense, or relicense accepted Contributions under other open-source or
proprietary terms.

### 8. No Warranty, No Support

You are not expected to provide support for Your Contributions unless You choose to do so.
Unless required by applicable law or agreed to in writing, You provide Contributions on an
"AS IS" basis, without warranties or conditions of any kind, express or implied, including
title, non-infringement, merchantability, or fitness for a particular purpose.

### 9. Moral Rights

To the maximum extent permitted by applicable law, You waive and agree not to assert any
moral rights You may have in Your Contribution against the Project Owner or recipients of
software distributed by the Project Owner.

### 10. No Obligation

The Project Owner is under no obligation to accept, merge, or use Your Contribution.
Submission of a Contribution does not create any obligation or expectation of inclusion.

### 11. General

If any provision of this Agreement is held unenforceable, the remaining provisions will
remain in effect to the fullest extent permitted by applicable law.

---

By submitting a Contribution to this repository, You acknowledge that You have read and
agree to the terms of this Contributor License Agreement.

*This project is maintained by Eugene Petrenko (mcp@jonnyzzz.com).*

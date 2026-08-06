/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * The exit codes of `devrig install <agent> --check` — a contract between two processes, so it lives where
 * both can see it: devrig returns these, and the IDE plugin's settings page reads them to decide what a
 * row says (`ij-plugin` `onboarding/AgentRegistration.kt`).
 *
 * The bare all-agents mode (`devrig install --check`, no agent) reuses them as the AGGREGATE answer —
 * [INSTALL_CHECK_DRIFT_EXIT_CODE] when install would change (or could not verify) anything for any agent,
 * [INSTALL_CHECK_DISABLED_EXIT_CODE] when the only finding is a switched-off registration — while the
 * per-agent answers ride on stdout (`InstallCheckAgentLine.kt`), because one exit code cannot carry three.
 *
 * Additive only. A plugin talking to an older devrig must degrade, never misreport: a devrig that predates
 * [INSTALL_CHECK_DISABLED_EXIT_CODE] simply never returns it.
 */

/** Install would change something: no entry, a stale command, duplicates, a non-canonical name. */
const val INSTALL_CHECK_DRIFT_EXIT_CODE = 1

/**
 * The registration is canonical but **switched off** in the agent's own config.
 *
 * Its own code because it needs its own word in front of a user: nothing is missing or stale, it is turned
 * off — and no agent's `mcp list` says so, so this is the only place the fact surfaces.
 */
const val INSTALL_CHECK_DISABLED_EXIT_CODE = 2

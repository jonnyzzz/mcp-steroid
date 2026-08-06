/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * The exit codes of `devrig install <agent> --check`, the single-agent dry-run doctor
 * ([runInstallCheckCommand] in `InstallCommand.kt`, its one producer) — for a user or script asking
 * "would re-running install change anything?" without changing anything. The IDE settings page does NOT
 * read these: its agent rows are display-only (one copyable `install <agent>` command each), so the codes
 * live here next to the command, not in `:devrig-common`.
 *
 * Additive only. A caller talking to an older devrig must degrade, never misreport: a devrig that predates
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

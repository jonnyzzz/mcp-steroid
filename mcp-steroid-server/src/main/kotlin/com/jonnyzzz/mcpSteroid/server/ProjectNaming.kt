/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Shared project-name disambiguation used by BOTH the in-IDE plugin and devrig so the two compute the
 * SAME unique name for the same project (issue #92: two same-named projects — e.g. a main checkout and a
 * git worktree — open in one IDE must stay individually addressable).
 *
 * The plugin owns within-IDE uniqueness: it exposes [uniqueProjectName] as each open project's
 * `project_name` and resolves an incoming `project_name` by RE-COMPUTING this over all open projects
 * (never cached — a name may shift if the project model changes, which is fine). devrig owns
 * world-uniqueness (it salts with the IDE pid for its agent-facing name) and does not rely on the IDE's
 * name being globally unique; it forwards the IDE's recomputed `project_name` so the IDE re-derives and
 * matches it within the already-selected backend. When both salt with the same (home, pid) the strings
 * are identical, but that alignment is a convenience, not a requirement.
 */

/**
 * Canonicalizes a project home for hashing. `toRealPath()` resolves symlinks but THROWS when the
 * directory no longer exists — a single vanished project (e.g. a test project deleted while its snapshot
 * is still cached) must not break naming for every other project. Fall back to the lexically-normalized
 * absolute path in that case.
 */
fun canonicalProjectHome(projectHome: String): Path {
    val path = Path.of(projectHome)
    return try {
        path.toRealPath()
    } catch (e: java.io.IOException) {
        path.toAbsolutePath().normalize()
    }
}

/**
 * The 8-char base62 suffix over the salted SHA-256 of (real project home, [idePid]). base62 has no
 * `-`/`_`, so the suffix never contains or ends with `-`; the whole 256-bit digest feeds the result.
 */
fun projectHash(realProjectHome: Path, idePid: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(realProjectHome.toString().encodeToByteArray())
    digest.update(0.toByte())
    digest.update(idePid.toString().encodeToByteArray())
    return base62FixedWidth(digest.digest(), 8)
}

/**
 * The disambiguated project name: `<rawName>-<projectHash(home, pid)>`. Recompute on every use; never
 * cache. [rawName] is the IntelliJ `Project.name`; [projectHome] is its base path; [idePid] is the IDE
 * process pid (plugin: its own pid; devrig: the discovered IDE's pid).
 */
fun uniqueProjectName(rawName: String, projectHome: String, idePid: Long): String =
    "$rawName-${projectHash(canonicalProjectHome(projectHome), idePid)}"

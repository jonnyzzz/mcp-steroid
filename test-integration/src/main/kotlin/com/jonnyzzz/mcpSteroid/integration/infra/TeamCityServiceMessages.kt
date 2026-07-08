/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File

/**
 * Emits TeamCity service messages for integrating Docker-based IDE tests with the
 * TC build agent. See https://www.jetbrains.com/help/teamcity/service-messages.html.
 *
 * The [publishRunDirArtifact] helper turns each test-scoped run directory into a
 * TC build artifact so humans can download the full session (session-info.txt, agent
 * NDJSON/decoded logs, screenshots, IDE logs, video) from the TC UI after the build.
 *
 * Emission is unconditional — running the message outside TC is harmless (TC agents
 * recognise these messages, local runs just see a log line). We deliberately avoid
 * gating on `TEAMCITY_VERSION` so output is identical everywhere, which makes local
 * debugging of the generated spec trivial.
 */
object TeamCityServiceMessages {

    /**
     * Escapes special characters per the TC service-messages spec. Single quotes,
     * pipes and square brackets must be prefixed with `|`; newlines become `|n` / `|r`.
     */
    fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\'' -> append("|'")
                '|' -> append("||")
                '[' -> append("|[")
                ']' -> append("|]")
                '\n' -> append("|n")
                '\r' -> append("|r")
                else -> append(ch)
            }
        }
    }

    /**
     * Publish [runDir] as a set of TC build artifacts.
     *
     * Behaviour depends on whether [TeamCityArtifactPostProcess] ran and
     * produced the `<runDir>/publish/` tree (only happens on TC, gated by
     * `TEAMCITY_VERSION`):
     *
     *  * **With** `publish/` (TC mode): publish the trimmed tree only —
     *    the 1080p re-encoded video as a standalone `<runName>/video/`
     *    and everything else as a single `<runName>.zip` drawn from
     *    `publish/bundle/` (no video inside the zip, no screenshots at
     *    all). Slim, TC-friendly, ~95% smaller than the raw runDir.
     *
     *  * **Without** `publish/` (local dev, or post-process skipped):
     *    keep the legacy three-message behaviour — standalone video,
     *    standalone screenshots, and a full-runDir zip with everything
     *    duplicated inside.
     *
     * Uses the recursive-glob form `<pattern>/<star><star> => <dest>` from
     * the "Artifact paths" syntax documented at
     * <https://www.jetbrains.com/help/teamcity/configuring-general-settings.html#Build+Options>
     * (the literal two-star-slash is avoided in KDoc because the Kotlin
     * comment parser treats `<star><star>/` as end-of-comment). A plain
     * `<dir> => <zip>` is interpreted as a literal path and TC logs
     * `Artifacts path '…' not found` on an empty dir — the glob makes
     * TC resolve matching files at publish time.
     *
     * Emission site matters: this is called from a lifetime cleanup action
     * (see intelliJ-factory.kt), AFTER the post-process has built `publish/`
     * and the container has stopped — so TC sees the final tree.
     */
    fun publishRunDirArtifact(runDir: File) {
        val runName = runDir.name
        val base = runDir.absolutePath
        val publishDir = File(runDir, TeamCityArtifactPostProcess.PUBLISH_SUBDIR)
        if (TeamCityArtifactPostProcess.isTeamCity() && publishDir.isDirectory) {
            val pBase = publishDir.absolutePath
            // Compressed video (h264 High, 1080p) standalone for browser preview.
            println("##teamcity[publishArtifacts '${escape("$pBase/video/** => $runName/video/")}']")
            // Bundle = everything except video / screenshots. Drives the zip
            // with no duplicated video bytes.
            println("##teamcity[publishArtifacts '${escape("$pBase/bundle/** => $runName.zip")}']")
            return
        }
        // Local-dev fallback: publish the full, uncompressed run-dir.
        // Video — standalone, one file per run under <runName>/video/
        println("##teamcity[publishArtifacts '${escape("$base/video/** => $runName/video/")}']")
        // Screenshots — standalone, one folder per run under <runName>/screenshot/
        println("##teamcity[publishArtifacts '${escape("$base/screenshot/** => $runName/screenshot/")}']")
        // Everything zipped for bulk download; the video + screenshots are
        // included in the zip too (duplicates the standalone copies) so the
        // zip stays a self-contained offline record of the whole session.
        println("##teamcity[publishArtifacts '${escape("$base/** => $runName.zip")}']")
        // Agent-produced patch, to be used for evaluation
        println("##teamcity[publishArtifacts '${escape("$base/agent-result.patch => $runName.patch")}']")
    }
}

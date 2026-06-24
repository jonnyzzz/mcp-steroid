/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/**
 * Builds a trimmed, TC-ready artifact tree at `<runDir>/publish/` inside the
 * still-running IDE container, just before teardown. Only active on TC
 * (gated by [isTeamCity]) — local runs keep the full run-dir so developers
 * can inspect raw video / screenshots / IDE state directly.
 *
 * Why inside the container:
 *  - the only place with `ffmpeg`, and the host TC agent does not have it
 *  - the source files (`recording.mp4`, IDE logs) are already on the
 *    bind-mounted `/mcp-run-dir`, so the container can read + rewrite
 *    them in place and the host sees the result after the exec returns
 *
 * Output layout under `/mcp-run-dir/publish/`:
 *
 *   video/recording.mp4   1080p h264 High, CRF 23 — typically 10–25×
 *                         smaller than the capture (h264 Constrained
 *                         Baseline, 4K, 5+ Mbps with no B-frames)
 *   bundle/intellij/...   Full IDE logs / config / mcp-steroid state
 *                         (plugins folder is NOT here — it lives at
 *                         /home/agent/ide-plugins, outside the mount)
 *   bundle/<root-files>   Everything at the runDir root (session-info.txt,
 *                         agent-*-raw.ndjson, agent-*-decoded.txt, …)
 *
 * The split into `video/` + `bundle/` lets [TeamCityServiceMessages]
 * publish the compressed video as a standalone artifact (browser-preview)
 * while the zip captures everything else without duplicating the video.
 * Screenshots are NOT copied — they are redundant with the video and, at
 * hundreds of PNGs per run, are what pushes the per-build zip over 1 GB.
 */
object TeamCityArtifactPostProcess {

    /** Subdirectory inside runDir holding the TC-ready output tree. */
    const val PUBLISH_SUBDIR: String = "publish"

    fun isTeamCity(): Boolean = !System.getenv("TEAMCITY_VERSION").isNullOrBlank()

    /**
     * Build the TC artifact tree at `<runDir>/publish/` inside the container.
     *
     * [containerMountedPath] is the container-side path of the bind-mounted
     * runDir (default `/mcp-run-dir`). The function is a no-op outside TC.
     *
     * Safe to call from a lifetime cleanup action — any failure is logged but
     * does not propagate, so it cannot mask the real test outcome. We keep a
     * 10-minute timeout because the ffmpeg re-encode on a 20-minute 4K capture
     * can take a few minutes on slower CI agents.
     */
    fun buildPublishTree(
        driver: ContainerDriver,
        containerMountedPath: String = "/mcp-run-dir",
    ) {
        if (!isTeamCity()) return

        val publishDir = "$containerMountedPath/$PUBLISH_SUBDIR"
        val videoOut = "$publishDir/video"
        val bundleOut = "$publishDir/bundle"
        val srcVideo = "$containerMountedPath/video/recording.mp4"
        val dstVideo = "$videoOut/recording.mp4"

        // The rsync invocation copies the entire runDir to publish/bundle/,
        // excluding video/, screenshot/, publish/ (the target), and the
        // ffmpeg internal stash. `--link-dest` would hard-link identical
        // files if we cared about throughput, but the bundle is small
        // (~20–50 MB of logs/NDJSON) so a plain copy is fine.
        val script = buildString {
            appendLine("set -eu")
            appendLine("mkdir -p '$videoOut' '$bundleOut'")
            // Copy everything under runDir into bundle/, excluding video,
            // screenshots, the publish dir itself, and the ffmpeg working
            // stash. Use `find … -print0 | xargs -0 cp` to stay robust to
            // awkward file names in IDE logs.
            appendLine("cd '$containerMountedPath'")
            appendLine("find . -mindepth 1 -maxdepth 1 \\")
            appendLine("  -not -name video -not -name screenshot -not -name '${PUBLISH_SUBDIR}' \\")
            appendLine("  -print0 | xargs -0 -I{} cp -a {} '$bundleOut/'")
            // Re-encode video → 1080p h264 High CRF 23. Fall back to a plain
            // copy if ffmpeg fails (so the TC artifact always has *some*
            // video, even an ugly-sized one). If the source is missing
            // (ffmpeg failed at startup, test died before video recording
            // began), skip silently.
            appendLine("if [ -s '$srcVideo' ]; then")
            appendLine("  if ! ffmpeg -nostdin -y -loglevel error \\")
            appendLine("       -i '$srcVideo' \\")
            appendLine("       -vf scale=1920:1080 \\")
            appendLine("       -c:v libx264 -preset medium -crf 23 -profile:v high \\")
            appendLine("       '$dstVideo'; then")
            appendLine("    echo '[TC-POSTPROCESS] ffmpeg re-encode failed, falling back to raw copy' >&2")
            appendLine("    cp -a '$srcVideo' '$dstVideo'")
            appendLine("  fi")
            appendLine("fi")
        }

        try {
            driver.startProcessInContainer {
                this
                    .args("bash", "-c", script)
                    .timeoutSeconds(600)
                    .description("TC artifact post-process: build $publishDir")
            }.assertExitCode(0) { "TC artifact post-process failed: $stderr" }
        } catch (t: Throwable) {
            System.err.println("[TC-POSTPROCESS] failed: ${t.message}")
            t.printStackTrace(System.err)
        }
    }
}

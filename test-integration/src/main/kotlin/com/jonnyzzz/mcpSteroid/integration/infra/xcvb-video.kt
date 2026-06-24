/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlin.concurrent.thread

class XcvbVideoDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val windows: XcvbWindowDriver,
    private val xcvb: XcvbDriver,
    private val videoDirInContainer: String,
    private val runIdNameForWebUI: String,
) {
    companion object {
        val VIDEO_STREAMING_PORT = ContainerPort(8765)
    }

    val videoGuestPath = "$videoDirInContainer/recording.mp4"
    val videoFile get() = driver.mapGuestPathToHostPath(videoGuestPath)

    fun startVideoService() {
        startVideoRecording()
        startVideoRsync()

        startVideoStreamingServer()
        startLiveVideoPreview()
    }

    /**
     * Container-local path for the video recording, NOT on the mounted volume.
     *
     * Docker Desktop virtiofs does not flush file data to the host while
     * the writing process (ffmpeg) keeps the file open. Screenshots work
     * because scrot opens-writes-closes each file, but ffmpeg writes
     * continuously. Writing to a non-mounted path avoids the issue;
     * we copy the file out during cleanup after ffmpeg exits.
     */
    private val videoInternalDir = "/tmp/xcvb-video"
    private val videoInternalPath = "$videoInternalDir/recording.mp4"

    private fun startVideoRecording() {
        driver.mkdirs(videoInternalDir)

        val size = windows.getDisplayArea()
        require(size.x == 0 && size.y == 0) { "Incorrect screen size: $size" }

        val frameRate = 24
        val keyframeIntervalSeconds = 1
        val gopSize = frameRate * keyframeIntervalSeconds

        /**
         * Build FFmpeg command line for live MP4 recording optimized for early playback.
         *
         * Keeping keyframes at 1-second cadence prevents 20-50s startup delays where
         * fragmented MP4 playback waits for the next keyframe-based fragment.
         *
         * Note: x264 defaults to keyint=250 (25s at 10fps). We must override keyint
         * explicitly via x264 params so fragmented MP4 can emit 1-second fragments.
         */

        val args = listOf(
            "ffmpeg", "-nostdin", "-y",
            "-f", "x11grab", "-video_size", "${size.width}x${size.height}",
            "-framerate", frameRate.toString(), "-i", xcvb.DISPLAY,
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-crf", "28",
            "-pix_fmt", "yuv420p",
            "-r", frameRate.toString(),
            "-g", gopSize.toString(),
            "-keyint_min", gopSize.toString(),
            "-x264-params", "keyint=$gopSize:min-keyint=$gopSize:scenecut=0:rc-lookahead=0",
            "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            "-frag_duration", "1000000",
            "-flush_packets", "1",
            "-fflags", "+flush_packets",
            videoInternalPath,
        )

        val hostVideoFile = runCatching { driver.mapGuestPathToHostPath(videoGuestPath) }.getOrNull()
        println("[xcvb] Starting video recording (container-local: $videoInternalPath, host: $hostVideoFile)...")
        val proc = driver.runInContainerDetached(args)

        lifetime.registerCleanupAction {
            stopVideoRecordingAndCopyOut(proc)
        }
    }

    private fun rsyncCommand(): String = "  rsync --inplace $videoInternalPath $videoGuestPath 2>/dev/null"

    /**
     * Periodically rsync the growing video file from the container-local path
     * to the mounted volume so the host has a reasonably current copy at all
     * times. Because the file uses fragmented MP4 (frag_keyframe+empty_moov),
     * each synced copy is a valid (if truncated) MP4.
     */
    private fun startVideoRsync() {
        driver.mkdirs(videoDirInContainer)

        println("[xcvb] Starting periodic video rsync to $videoGuestPath...")
        val rsyncScript = buildString {
            appendLine("while true; do")
            appendLine(rsyncCommand())
            appendLine("  sleep 1")
            appendLine("done")
        }
        val proc = driver.runInContainerDetached(
            listOf("bash", "-c", rsyncScript),
        )

        lifetime.registerCleanupAction {
            proc.kill("TERM")
        }
    }


    private fun stopVideoRecordingAndCopyOut(proc: RunningContainerProcess) {
        // Send SIGINT so ffmpeg writes the final trailer
        proc.kill("INT")

        // Wait for ffmpeg to exit (up to 10 seconds)
        try {
            waitFor(10_000, "ffmpeg should exit") {
                !proc.isRunning()
            }
        } catch (_: Throwable) {
            println("[xcvb] ffmpeg did not exit after SIGINT, sending SIGKILL")
            proc.kill("KILL")
            Thread.sleep(500)
        }

        // Copy the finalized video from the container-local path to the mounted volume.
        // Using cp (open-write-close) instead of letting ffmpeg write directly to the
        // mount avoids the virtiofs stale-data issue.
        val rsyncAndSyncScript = buildString {
            appendLine(rsyncCommand())
            appendLine("sync")
        }
        driver.startProcessInContainer {
            this
                .args("bash", "-c", rsyncAndSyncScript)
                .timeoutSeconds(30)
                .quietly()
                .description("rsync video and sync")
        }.assertExitCode(0) { "Failed to copy video" }

        runCatching {
            val hostVideoFile = driver.mapGuestPathToHostPath(videoGuestPath)
            println("Check out screen recording at $hostVideoFile")
        }
    }

    /**
     * Start the Node.js HTTP server that streams the growing MP4 file.
     * The server is baked into the Docker image at /usr/local/bin/video-server.js.
     *
     * The server reads from the container-local video path (not the mounted
     * volume) so it sees ffmpeg's writes in real-time.
     */
    fun startVideoStreamingServer() {
        val hostPort = driver.mapGuestPortToHostPort(VIDEO_STREAMING_PORT)
        println("[xcvb] Starting video streaming server at http://localhost:$hostPort/")
        val proc = driver.runInContainerDetached(
            listOf(
                "node",
                "/usr/local/bin/video-server.js",
                videoInternalPath,
                VIDEO_STREAMING_PORT.containerPort.toString(),
                runIdNameForWebUI,
                "/usr/share/images/mcp-steroid-wallpaper.jpg",
            ),
        )

        lifetime.registerCleanupAction {
            proc.kill()
        }
    }

    /**
     * Wait for the video streaming server to become ready, log its URL,
     * and open the dashboard in the default browser on macOS.
     *
     * The server always starts (inside Docker) and its address is always
     * logged. Only the browser `open` command is macOS-specific.
     */
    fun startLiveVideoPreview() {
        val hostPort = driver.mapGuestPortToHostPort(VIDEO_STREAMING_PORT)
        val dashboardUrl = "http://localhost:$hostPort/"

        val thread = thread(start = true) {
            runCatching {
                waitFor(35_000L, "Video streaming server ready") {
                    val process =
                        ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "${dashboardUrl}status")
                            .start()
                    process.waitFor()
                    process.inputStream.bufferedReader().readText().trim() == "200"
                }

                println("[VIDEO] Dashboard ready: $dashboardUrl")
                println("[VIDEO] Stream URL:     ${dashboardUrl}video.mp4")

                // Open browser on macOS only
                if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
                    println("[VIDEO] Opening dashboard in browser...")
                    ProcessBuilder("open", dashboardUrl).start()
                }
            }
        }

        lifetime.registerCleanupAction {
            thread.interrupt()
        }
    }
}

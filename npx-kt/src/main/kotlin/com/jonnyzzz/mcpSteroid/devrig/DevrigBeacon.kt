/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class DevrigBeacon(
    private val homePaths: HomePaths,
    lifetime: CloseableStack,
) {
    private val log = logger<DevrigBeacon>()

    private val job = SupervisorJob().also { lifetime.registerCleanupAction { it.cancel() } }
    private val scope = CoroutineScope(CoroutineName("DevrigBeacon") + Dispatchers.IO + job)

    private val posthog by lazy {
        // Opt-out for unit tests (set in :npx-kt:test) so they never reach PostHog over the network.
        if (System.getProperty("devrig.beacon.disabled") == "true") return@lazy null
        try {
            val config = PostHogConfig
                .builder("phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW")
                .host("https://us.i.posthog.com")
                .flushIntervalSeconds(15)
                .flushAt(10)
                .maxQueueSize(50)
                .maxBatchSize(5)
                .preloadFeatureFlags(false)
                .remoteConfig(false)
                .sendFeatureFlagEvent(false)
                .build()

            val hog = PostHog.with(config)

            lifetime.registerCleanupAction {
                runCatching { hog.flush() }
                runCatching { hog.close() }
            }

            hog
        } catch (e: Exception) {
            log.debug("PostHog init failed", e)
            null
        }
    }

    fun captureStarted(cliMode: DevrigCommand?) {
        val mode = when (cliMode) {
            is DevrigCommand.MCP -> "mcp"
            is DevrigCommand.DevrigCommandBackend,
            is DevrigCommand.DevrigCommandBackendDownload,
            is DevrigCommand.DevrigCommandBackendStart,
            is DevrigCommand.DevrigCommandBackendStop,
            is DevrigCommand.DevrigCommandBackendProvision -> "backend"
            is DevrigCommand.DevrigCommandProject -> "project"
            is DevrigCommand.DevrigCommandInstall -> "install"
            is DevrigCommand.DevrigCommandInstallDevrig -> "install"
            is DevrigCommand.DevrigCommandHelp -> null
            is DevrigCommand.DevrigCommandVersion -> null
            is DevrigCommand.DevrigCommandParseError -> null
            null -> null
        } ?: return

        capture("started", mapOf("mode" to mode))
    }

    /**
     * Capture a status score event (0-100) — the devrig mirror of the ij-plugin's `ide_status_score`.
     * [context] records where the score came from (e.g. "feedback").
     */
    fun captureScore(score: Int, context: String, properties: Map<String, Any> = emptyMap()) {
        require(score in 0..100) { "Score must be between 0 and 100, got: $score" }
        val enriched = LinkedHashMap<String, Any>()
        enriched.putAll(properties)
        enriched["score"] = score
        enriched["context"] = context
        capture("status_score", enriched)
    }

    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        try {
            val ph = posthog ?: return

            val distinctId = distinctId()

            val enrichedProperties = LinkedHashMap<String, Any>()
            enrichedProperties.putAll(properties)

            enrichedProperties["devrig_version"] = DevrigVersionMetadata.getDevrigVersion()
            enrichedProperties["devrig_host"] = distinctId

            val opts = PostHogCaptureOptions.builder()
            enrichedProperties.forEach { (key, value) -> opts.property(key, value) }
            opts.appendFeatureFlags(false)
            opts.timestamp(LocalDateTime.now().toInstant(ZoneOffset.UTC))

            scope.launch {
                try {
                    // Mirror the ij-plugin's "ide_" namespace: every devrig analytics event is "devrig_<event>".
                    ph.capture(distinctId, "devrig_$event", opts.build())
                    ph.flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.debug("Beacon send failed. ${e.message}", e)
                }
            }
        } catch (e: Throwable) {
            log.debug("Beacon capture failed. ${e.message}", e)
        }
    }

    fun runHeartbeat() {
        scope.launch {
            while (isActive) {
                yield()
                delay(30.minutes)
                capture("heartbeat")
            }
        }
    }

    private fun distinctId(): String {
        val userIdFile = homePaths.home.resolve(".devrig-user-id")
        runCatching {
            Files.createDirectories(userIdFile.parent)
        }

        val existing = try {
            // A missing file is the normal first-run / managed case — handled silently by generating a
            // fresh id below. Only a genuine read error (permissions, corruption) is worth logging.
            if (Files.exists(userIdFile)) userIdFile.readText().trim() else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.debug("Failed to read .devrig-user-id: ${e.message}", e)
            ""
        }

        if (existing.isNotEmpty()) return existing
        val generated = UUID.randomUUID().toString()
        runCatching {
            Files.writeString(userIdFile, "$generated\n")
        }

        return generated
    }
}

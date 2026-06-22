/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * One IntelliJ-family IDE detected by an active probe of the local
 * machine's TCP ports — not via the `.mcp-steroid` JSON marker.
 *
 * `productFullName` is the unrestricted `name` field returned by
 * `/api/about`; `productName` is the short product slug
 * (`IDEA`, `PyCharm`, `GoLand`, …). `buildNumber` is absent on snapshot
 * builds.
 */
data class DiscoveredIdeByPort(
    val port: Int,
    val baseUrl: String,
    val productName: String?,
    val productFullName: String?,
    val edition: String?,
    val baselineVersion: Int?,
    val buildNumber: String?,
)

/**
 * Port-scan discovery seam consumed by [com.jonnyzzz.mcpSteroid.devrig.BackendInventory]. Extracted so
 * the inventory depends on the capability ("give me the IDEs answering on local HTTP ports"), not on the
 * concrete [IntelliJPortDiscovery] which needs a live [HttpClient] — tests inject a trivial fake instead.
 */
fun interface PortDiscovery {
    suspend fun stateSnapshot(): Set<DiscoveredIdeByPort>
}

/**
 * Active discovery of IntelliJ-family IDEs running on `127.0.0.1` by
 * probing common HTTP-server ports.
 *
 * Complements [IdePidDiscoveryService] (which discovers `mcp-steroid`-aware
 * IDEs via their managed PID marker). Port-based discovery finds *any*
 * JetBrains IDE — even ones without the
 * `mcp-steroid` plugin installed.
 *
 * Scope of the scan: by default 63342..63361 (the IntelliJ Platform's
 * Netty built-in server picks the first free port starting at 63342
 * with up to 19 fallback ports) and 64342..64361 (the bundled MCP
 * Server plugin uses the same 20-port fallback scheme on top of
 * `DEFAULT_MCP_PORT = 64342`).
 *
 * **Parallelism is bounded** via `Dispatchers.IO.limitedParallelism([parallelism])`. Probes run on
 * IO's daemon worker threads, and each probe is independently capped by [probeTimeout]
 * ([withTimeoutOrNull]), so a single slow TCP connect cannot stall the stdio MCP server or marker
 * discovery.
 */
class IntelliJPortDiscovery(
    private val httpClient: HttpClient,
    private val portRanges: List<IntRange> = DEFAULT_PORT_RANGES,
    private val probeTimeout: Duration = 1500.milliseconds,
    private val parallelism: Int = 8,
) : PortDiscovery {
    private val log = LoggerFactory.getLogger(IntelliJPortDiscovery::class.java)
    /**
     * One scan pass. Probes every port in [portRanges] concurrently on
     * the dedicated dispatcher and returns every IDE that answered.
     * Exposed for tests + for forced refresh after a known event
     * (e.g. an IDE just started).
     *
     * Each probe is independent: a port that times out (or otherwise
     * fails to answer within [probeTimeout]) contributes nothing and
     * cannot discard the results of the ports that *did* answer. Probes
     * publish into a shared concurrent set as they finish — there is no
     * `awaitAll` join that a single slow port could fail. So the snapshot
     * always carries every IDE detected within the per-probe timeout
     * window.
     */
    //TODO: same IDE can be returned multiple times.
    override suspend fun stateSnapshot() : Set<DiscoveredIdeByPort> {
        val ports = portRanges.flatMap { it.toList() }.toSortedSet()
        val results = ConcurrentHashMap.newKeySet<DiscoveredIdeByPort>()
        coroutineScope {
            withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
                ports.forEach { port ->
                    launch {
                        probePort(port)?.let { results.add(it) }
                    }
                }
            }
        }
        return results.toSet()
    }

    /**
     * Probe a single port. Returns `null` for every non-IDE response
     * (connection refused, non-2xx, non-JSON body, response that
     * doesn't look like an IDE's `/api/about`, or any other failure).
     * Never propagates an exception — a port that isn't an IDE is a
     * normal outcome, not an error.
     */
    private suspend fun probePort(port: Int): DiscoveredIdeByPort? = try {
        // withTimeoutOrNull, not withTimeout: a probe that exceeds
        // probeTimeout is a non-answering port, not an error. Returning
        // null keeps the TimeoutCancellationException from escaping and
        // cancelling sibling probes (the whole scan would otherwise be
        // lost the moment one port hangs).
        withTimeoutOrNull(probeTimeout) {
            val response = httpClient.get("http://127.0.0.1:$port/api/about") {
                accept(ContentType.Application.Json)
            }
            if (!response.status.isSuccess()) return@withTimeoutOrNull null
            val body = response.bodyAsText()
            val about = try {
                aboutJson.decodeFromString(AboutResponse.serializer(), body)
            } catch (e: Exception) {
                log.debug("Port {} did not return /api/about JSON: {}", port, e.message)
                return@withTimeoutOrNull null
            }
            // Accept only responses that carry at least one IDE-identifying
            // field — a stray JSON 200 from a different service shouldn't
            // get classified as an IntelliJ.
            if (about.productName == null && about.name == null) return@withTimeoutOrNull null
            DiscoveredIdeByPort(
                port = port,
                baseUrl = "http://127.0.0.1:$port",
                productName = about.productName,
                productFullName = about.name,
                edition = about.edition,
                baselineVersion = about.baselineVersion,
                buildNumber = about.buildNumber,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Default scan ranges. Tracks both built-in HTTP server's
         * fallback range and the bundled MCP Server plugin's fallback
         * range — both pick the first free port in a 20-port window
         * above their default.
         */
        val DEFAULT_PORT_RANGES: List<IntRange> = listOf(
            63342..63361,
            64342..64361,
        )
    }
}

@Serializable
data class AboutResponse(
    val name: String? = null,
    val productName: String? = null,
    val edition: String? = null,
    val baselineVersion: Int? = null,
    val buildNumber: String? = null,
)

val aboutJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.LicenseTier
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AvailableBackendDownload(
    val product: IdeProduct,
    val version: String?,
    val releaseDate: String? = null,
    val versionLookupError: String? = null,
    /** Build of the latest stable release, e.g. `261.24374.151`. */
    val build: String? = null,
    /** True/false when the build is in/out of the bundled plugin's range; null when unknown. */
    val compatible: Boolean? = null,
)

data class AvailableBackendRelease(
    val version: String,
    val releaseDate: String? = null,
    val build: String? = null,
)

interface AvailableBackendVersionResolver {
    suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease
}

class ReleaseServiceAvailableBackendVersionResolver(
    private val os: HostOs = resolveHostOs(),
) : AvailableBackendVersionResolver {
    override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease = withContext(Dispatchers.IO) {
        val archive = resolveBackendArchive(product = product, os = os, version = null)
        AvailableBackendRelease(
            version = archive.version,
            releaseDate = archive.releaseDate,
            build = archive.build,
        )
    }
}

fun runBackendDownloadListCommand(
    out: PrintStream,
    json: Boolean,
    versionResolver: AvailableBackendVersionResolver = ReleaseServiceAvailableBackendVersionResolver(),
    pluginBuildRange: PluginBuildRange? = bundledPluginBuildRange,
    availableDownloads: suspend () -> List<AvailableBackendDownload> = {
        collectAvailableBackendDownloads(versionResolver = versionResolver, pluginBuildRange = pluginBuildRange)
    },
) {
    if (!json) {
        out.flush()
    }
    val rows = runBlocking(Dispatchers.IO) {
        availableDownloads()
    }
    if (json) {
        renderBackendDownloadListJson(rows, out)
    } else {
        renderBackendDownloadListRowsText(rows, out)
    }
}

suspend fun collectAvailableBackendDownloads(
    products: List<IdeProduct> = orderedKnownBackendProducts(),
    versionResolver: AvailableBackendVersionResolver,
    pluginBuildRange: PluginBuildRange? = bundledPluginBuildRange,
    totalBudget: Duration = 15.seconds,
): List<AvailableBackendDownload> = coroutineScope {
    products.map { product ->
        async {
            val release = tryResolveLatestStableVersion(product, versionResolver, totalBudget)
            val resolved = release.getOrNull()
            val build = resolved?.build
            AvailableBackendDownload(
                product = product,
                version = resolved?.version,
                releaseDate = resolved?.releaseDate,
                build = build,
                compatible = if (build != null && pluginBuildRange != null) pluginBuildRange.accepts(build) else null,
                versionLookupError = release.exceptionOrNull()?.shortMessage(),
            )
        }
    }.awaitAll()
}

private suspend fun tryResolveLatestStableVersion(
    product: IdeProduct,
    versionResolver: AvailableBackendVersionResolver,
    timeout: Duration,
): Result<AvailableBackendRelease> {
    val version = try {
        withTimeoutOrNull(timeout) {
            versionResolver.resolveLatestStableRelease(product)
        } ?: return Result.failure(IllegalStateException("timed out after ${timeout.inWholeSeconds}s"))
    } catch (e: Exception) {
        return Result.failure(e)
    }
    return Result.success(version)
}

private fun orderedKnownBackendProducts(): List<IdeProduct> {
    val originalIndex = IdeProduct.knownProducts.withIndex().associate { it.value to it.index }
    return IdeProduct.knownProducts.sortedWith(
        compareBy<IdeProduct> { licenseTierSortKey(it.licenseTier) }
            .thenBy { originalIndex.getValue(it) },
    )
}

private fun licenseTierSortKey(tier: LicenseTier): Int = when (tier) {
    LicenseTier.Free -> 0
    LicenseTier.FreeForNonCommercial -> 1
    LicenseTier.Paid -> 2
}

fun renderBackendDownloadListText(rows: List<AvailableBackendDownload>, out: PrintStream) {
    renderBackendDownloadListRowsText(rows, out)
}

fun renderBackendDownloadListBanner(out: PrintStream) {
    out.println("Available IDEs (defaults to latest stable):")
    out.println()
}

fun renderBackendDownloadListRowsText(rows: List<AvailableBackendDownload>, out: PrintStream) {
    renderBackendDownloadListRowsText(rows, out, afterRunLine = null)
}

fun renderBackendDownloadListRowsText(
    rows: List<AvailableBackendDownload>,
    out: PrintStream,
    afterRunLine: String?,
) {
    renderBackendDownloadListBanner(out)
    if (rows.isNotEmpty()) {
        val indexWidth = rows.size.toString().length + 2
        val idWidth = rows.maxOf { it.product.id.length }
        val displayWidth = rows.maxOf { it.product.displayName.codePointWidth() }
        val versionWidth = rows.maxOfOrNull { it.versionText().length } ?: 0
        for ((index, row) in rows.withIndex()) {
            val indexLabel = "[${index + 1}]".padEnd(indexWidth)
            val id = row.product.id.padEnd(idWidth)
            val name = row.product.displayName.padEndCodePoints(displayWidth)
            val licenseSuffix = row.product.licenseTier.licenseSymbol
                .takeIf { it.isNotEmpty() }
                ?.let { "  $it" }
                .orEmpty()
            val compatSuffix = if (row.compatible == false) "  — incompatible with the bundled plugin" else ""
            out.println("  $indexLabel $id  $name  ${row.versionText().padEnd(versionWidth)}$licenseSuffix$compatSuffix")
        }
    }
    out.println()
    renderLicenseLegend(rows, out)
    out.println("Run:  devrig backend download <id> [--version <v>]")
    afterRunLine?.let { out.println(it) }
    out.println()
}

fun renderBackendDownloadListJson(rows: List<AvailableBackendDownload>, out: PrintStream) {
    val payload = buildJsonObject {
        putToolJson()
        put("available", availableBackendDownloadsJson(rows))
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}

fun availableBackendDownloadsJson(rows: List<AvailableBackendDownload>) = buildJsonArray {
    for (row in rows) {
        add(buildJsonObject {
            put("id", row.product.id)
            put("code", row.product.code)
            put("displayName", row.product.displayName)
            put("licenseTier", row.product.licenseTier.cliValue)
            put("licenseSymbol", row.product.licenseTier.licenseSymbol)
            put("licenseNote", row.product.licenseTier.licenseNote)
            if (row.version == null) put("version", JsonNull) else put("version", row.version)
            if (row.build == null) put("build", JsonNull) else put("build", row.build)
            if (row.releaseDate == null) put("releaseDate", JsonNull) else put("releaseDate", row.releaseDate)
            // Mark incompatible lines only — no compatible=true noise on the (vast majority) usable IDEs.
            if (row.compatible == false) put("incompatible", true)
            row.versionLookupError?.let { put("versionLookupError", it) }
        })
    }
}

private fun renderLicenseLegend(rows: List<AvailableBackendDownload>, out: PrintStream) {
    val tiers = listOf(LicenseTier.Paid, LicenseTier.FreeForNonCommercial)
        .filter { tier -> rows.any { it.product.licenseTier == tier } }
    if (tiers.isEmpty()) return
    for (tier in tiers) {
        out.println("  ${tier.licenseSymbol.padEnd(2)} ${tier.licenseNote}")
    }
    out.println()
}

private fun AvailableBackendDownload.versionText(): String =
    version ?: "(version lookup failed: ${versionLookupError ?: "unknown"})"

val LicenseTier.cliValue: String
    get() = when (this) {
        LicenseTier.Free -> "free"
        LicenseTier.FreeForNonCommercial -> "free-for-non-commercial"
        LicenseTier.Paid -> "paid"
    }

val LicenseTier.licenseSymbol: String
    get() = when (this) {
        LicenseTier.Free -> ""
        LicenseTier.FreeForNonCommercial -> "**"
        LicenseTier.Paid -> "*"
    }

val LicenseTier.licenseNote: String
    get() = when (this) {
        LicenseTier.Free -> ""
        LicenseTier.FreeForNonCommercial -> "Free for non-commercial use; JetBrains license required for commercial use."
        LicenseTier.Paid -> "Includes the Community feature set; free in Community mode, a paid license unlocks the full edition."
    }

fun Throwable.shortMessage(): String {
    val raw = message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "failed"
    return raw.take(140)
}

val backendPrettyJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun kotlinx.serialization.json.JsonObjectBuilder.putToolJson() {
    put("tool", buildJsonObject {
        put("name", "devrig")
        put("version", DevrigVersionMetadata.getDevrigVersion())
    })
}

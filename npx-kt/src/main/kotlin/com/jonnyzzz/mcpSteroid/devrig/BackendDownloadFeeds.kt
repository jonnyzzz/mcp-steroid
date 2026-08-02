/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeArchiveResolution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveArchive
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs

/**
 * The release feed a product's archive comes from. The three feeds differ in how much of the build
 * number they publish, which is what [IdeArchiveResolution.buildIsBaseline] and [ideBuildMatches]
 * exist for: only [JETBRAINS_PRODUCTS_API] states the full build.
 */
enum class BackendDownloadFeed {
    /** `developer.android.com/studio/preview` — marketing version only, so the build is the baseline. */
    ANDROID_STUDIO_PREVIEW,

    /** `api.github.com/repos/JetBrains/intellij-community/releases` — no build number, baseline only. */
    GITHUB_COMMUNITY,

    /** `data.services.jetbrains.com/products` — publishes the exact build (`262.8665.337`). */
    JETBRAINS_PRODUCTS_API,
}

/** True when [feed] publishes only the platform baseline instead of the full build number. */
val BackendDownloadFeed.publishesBaselineOnly: Boolean
    get() = this != BackendDownloadFeed.JETBRAINS_PRODUCTS_API

/**
 * The feed [product] resolves from — the single source of truth for the dispatch, so the download
 * path and `devrig backend download` (the list) can never drift apart.
 *
 * Android Studio's stable channel lags the platform (253) and the products API stops at 253 for the
 * Community editions, so both are served from elsewhere; see [ANDROID_STUDIO_PREVIEW_PAGE] and
 * [INTELLIJ_COMMUNITY_RELEASES_API].
 */
fun backendDownloadFeed(product: IdeProduct): BackendDownloadFeed = when {
    product === IdeProduct.AndroidStudio -> BackendDownloadFeed.ANDROID_STUDIO_PREVIEW
    isGithubCommunityProduct(product) -> BackendDownloadFeed.GITHUB_COMMUNITY
    else -> BackendDownloadFeed.JETBRAINS_PRODUCTS_API
}

/**
 * Resolves the archive devrig downloads for [product] from whichever feed serves it.
 * Blocking; call on [kotlinx.coroutines.Dispatchers.IO].
 */
fun resolveBackendArchive(
    product: IdeProduct,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
): IdeArchiveResolution = when (backendDownloadFeed(product)) {
    BackendDownloadFeed.ANDROID_STUDIO_PREVIEW ->
        resolveAndroidStudioCanaryArchive(os = os, architecture = architecture, version = version)

    BackendDownloadFeed.GITHUB_COMMUNITY ->
        resolveGithubCommunityArchive(product = product, os = os, architecture = architecture, version = version)

    BackendDownloadFeed.JETBRAINS_PRODUCTS_API ->
        resolveArchive(product = product, channel = IdeChannel.STABLE, os = os, architecture = architecture, version = version)
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny keyed blob cache. Two implementations:
 *  - [Cache.onDisk] — each key is one file on disk under a root directory (the root is passed in from
 *    Gradle and lives OUTSIDE the `build/` folder so it survives `clean` and is shared across runs).
 *  - [Cache.inMemory] — keeps everything in a [ConcurrentHashMap]; used by tests so nothing touches disk.
 *
 * The primitive is [get]/[put] over raw bytes; the typed [getOrCompute] (Kotlin serialization) and the
 * validator-aware [downloadWithEtag] are built on top.
 */
interface Cache {
    /** Cached bytes for [key], or null if absent. */
    fun get(key: String): ByteArray?

    /** Store [value] for [key] (overwrites). */
    fun put(key: String, value: ByteArray)

    companion object {
        /** On-disk cache rooted at [root] — one file per key (`<root>/<sha256(key)>`). */
        fun onDisk(root: Path): Cache = OnDiskCache(root)

        /** In-memory cache backed by a [ConcurrentHashMap] — for tests. */
        fun inMemory(): Cache = InMemoryCache()
    }
}

@PublishedApi
internal val cacheJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** Bytes get-or-compute: return the cached bytes for [key], else run [compute] and store the result. */
fun Cache.getOrComputeBytes(key: String, compute: () -> ByteArray): ByteArray =
    get(key) ?: compute().also { put(key, it) }

/**
 * Typed get-or-compute: return the cached [T] for [key] (decoded from its JSON blob via Kotlin
 * serialization), else run [compute], store its JSON, and return it. The value type must be `@Serializable`.
 */
inline fun <reified T> Cache.getOrCompute(key: String, compute: () -> T): T {
    get(key)?.let { return cacheJson.decodeFromString<T>(it.decodeToString()) }
    return compute().also { put(key, cacheJson.encodeToString(it).encodeToByteArray()) }
}

/**
 * Cache-relevant metadata for a URL, captured from a cheap header probe ([HttpFetcher.head]).
 * The [cacheKey] folds every validator the server exposes — `Content-Length`, `Last-Modified` and
 * `ETag` — so a content change on any of them flips the key and forces a fresh download.
 */
data class UrlKey(
    val url: String,
    val size: Long,
    val lastModified: String?,
    val etag: String?,
    /** Final URL after following redirects (e.g. Corretto's `latest` alias -> the versioned resource). */
    val resolvedUrl: String = url,
) {
    private val lastName: String get() = url.substringAfterLast('/').ifEmpty { "blob" }.replace("?", "_")

    /** Stable, fixed-length cache key derived from the URL and its validators. */
    val cacheKey: String get() = sha256Hex("v1|$url|$size|$lastModified|$etag".encodeToByteArray()) + "-" + lastName
}

/** Minimal HTTP seam so [downloadWithEtag] is testable with a fake (no network in unit tests). */
interface HttpFetcher {
    /** Probe [url]'s validators (`ETag` / `Last-Modified` / `Content-Length`) without downloading the body. */
    fun head(url: String): UrlKey

    /** Download the full body of [url]. */
    fun getBytes(url: String): ByteArray
}

/**
 * Download [url], reusing the cached copy when the server's validators are unchanged. Built on
 * [getOrComputeBytes]: the validators ([UrlKey.cacheKey] — ETag, Last-Modified, size) are folded into
 * the cache key, so an unchanged ETag yields the same key (cache hit, NO re-download) and a changed
 * ETag yields a new key (cache miss, re-download; the old entry simply ages out).
 *
 * We REQUIRE an `ETag`: every JDK/IDE artifact host we fetch from (Corretto CDN, Azul, JetBrains
 * cache-redirector) serves one, so a missing ETag signals a misconfigured URL or an unexpected host
 * where silent caching would risk serving stale binaries — fail fast instead.
 */
fun Cache.downloadWithEtag(url: String, http: HttpFetcher = KtorHttpFetcher): ByteArray {
    val head = http.head(url)
    requireNotNull(head.etag) {
        "$url exposes no ETag; refusing to cache (would risk serving a stale binary). " +
                "Validators seen: size=${head.size}, lastModified=${head.lastModified}"
    }
    return getOrComputeBytes(head.cacheKey) { http.getBytes(url) }
}

/**
 * Download [url] and cache it content-addressed by a KNOWN-good [sha256] (e.g. a checksum published by
 * the vendor's metadata API). This is the right tool when the host exposes no usable HEAD `ETag` but
 * does publish a trusted hash — the hash is both the cache key (byte-identical content -> same key ->
 * cache hit) and the integrity check. The bytes are verified against [sha256] on download and again on
 * every return (see below), so a corrupted/tampered cache file is caught even on a cache hit.
 */
fun Cache.downloadVerifyingSha256(url: String, sha256: String, http: HttpFetcher = KtorHttpFetcher): ByteArray {
    val bytes = getOrComputeBytes("sha256:${sha256.lowercase()}") {
        http.getBytes(url).also { verifySha256(url, it, sha256) }
    }
    // Re-check on every return, including cache hits: the on-disk cache is a long-lived shared directory
    // this code does not exclusively own, so re-hashing (cheap vs the download) catches a corrupted or
    // tampered cache file.
    verifySha256(url, bytes, sha256)
    return bytes
}

private fun verifySha256(url: String, bytes: ByteArray, expected: String) {
    val actual = sha256Hex(bytes)
    require(actual.equals(expected, ignoreCase = true)) {
        "sha256 mismatch for $url: expected $expected but bytes hash to $actual"
    }
}

private class InMemoryCache : Cache {
    private val map = ConcurrentHashMap<String, ByteArray>()
    override fun get(key: String): ByteArray? = map[key]?.copyOf()
    override fun put(key: String, value: ByteArray) {
        map[key] = value.copyOf()
    }
}

private class OnDiskCache(private val root: Path) : Cache {
    override fun get(key: String): ByteArray? {
        val file = fileFor(key)
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    override fun put(key: String, value: ByteArray) {
        Files.createDirectories(root)
        val file = fileFor(key)
        // Atomic write: a concurrent reader never sees a half-written cache file.
        val tmp = root.resolve(".tmp.${ProcessHandle.current().pid()}.${file.fileName}")
        Files.write(tmp, value)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            System.err.println("[installer-gen] atomic cache move unsupported (${e.message}); using a plain move")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // Hash the key so arbitrary keys (URLs with slashes, etc.) map to a safe, fixed-length file name.
    private fun fileFor(key: String): Path = root.resolve(sha256Hex(key.encodeToByteArray()))
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Default [HttpFetcher] over the Ktor CIO client (the repo's HTTP stack). [close] it when done. */
object KtorHttpFetcher : HttpFetcher, AutoCloseable {
    private val clientLazy = lazy {
        HttpClient(CIO) {
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 600_000 // JDK archives are large
            }
        }
    }
    private val client get() = clientLazy.value

    /** Release the CIO connection/thread pools. No-op if no request was ever made. */
    override fun close() {
        if (clientLazy.isInitialized()) clientLazy.value.close()
    }

    override fun head(url: String): UrlKey = runBlocking {
        val resp = client.head(url)
        require(resp.status.isSuccess()) { "HEAD $url failed: HTTP ${resp.status}" }
        UrlKey(
            url = url,
            size = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L,
            lastModified = resp.headers[HttpHeaders.LastModified],
            etag = resp.headers[HttpHeaders.ETag],
            resolvedUrl = resp.call.request.url.toString(),
        )
    }

    override fun getBytes(url: String): ByteArray = runBlocking {
        val resp = client.get(url)
        require(resp.status.isSuccess()) { "GET $url failed: HTTP ${resp.status}" }
        resp.readRawBytes()
    }
}

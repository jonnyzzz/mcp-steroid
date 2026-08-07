/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

/**
 * Parses MCP stdio framing: Content-Length headers or NDJSON.
 *
 * Content-Length framing: "Content-Length: N\r\n\r\n<body>"
 * NDJSON fallback: "<json>\n" — only when buffer starts like JSON ({/[).
 */
data class FrameResult(
    val consumed: Int,
    val payloadText: String,
    val mode: String  // "framed" | "ndjson"
)

class FramingBuffer {
    private var data = ByteArray(0)
    var outputMode: String? = null  // detected from first inbound frame

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        val newData = ByteArray(data.size + length)
        data.copyInto(newData)
        bytes.copyInto(newData, destinationOffset = data.size, endIndex = length)
        data = newData
    }

    fun readNextFrame(): FrameResult? {
        val frame = tryParseNextFrame(data) ?: return null
        data = data.copyOfRange(frame.consumed, data.size)
        return frame
    }

    /**
     * Consumes a leading byte run that can never begin a frame — a line that is neither
     * JSON nor a frame header, or a completed header block with no usable
     * `Content-Length` — and returns it (trimmed) so the caller can report it.
     *
     * Returns null when the buffer is empty, holds a frame ([readNextFrame] takes those),
     * or could still grow into one. A non-null result always consumed at least one byte,
     * so a caller may loop on it. Blank text means the discarded run was only line breaks.
     *
     * jonnyzzz/mcp-steroid#461: without this, the first stray line on stdin sat at the head
     * of the buffer forever and every frame queued behind it was silently never parsed.
     */
    fun readNextUnparsableChunk(): String? {
        val length = unparsableChunkLength(data) ?: return null
        val text = data.copyOfRange(0, length).toString(Charsets.UTF_8).trim()
        data = data.copyOfRange(length, data.size)
        return text
    }

    /**
     * Consumes everything still buffered and returns it verbatim — the residue at EOF,
     * i.e. an unterminated line or a frame the peer never finished sending. Untrimmed on
     * purpose: this text is diagnostic evidence.
     */
    fun drain(): String {
        val text = data.toString(Charsets.UTF_8)
        data = ByteArray(0)
        return text
    }

    fun isEmpty(): Boolean = data.isEmpty()
}

private fun decodeContentLength(headersText: String): Int? {
    for (line in headersText.split(Regex("\r?\n"))) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim().lowercase()
        if (key != "content-length") continue
        return line.substring(idx + 1).trim().toIntOrNull()?.takeIf { it >= 0 }
    }
    return null
}

private fun startsLikeJsonPayload(data: ByteArray): Boolean {
    val prefix = data.take(minOf(data.size, 64))
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trimStart()
    return prefix.startsWith("{") || prefix.startsWith("[")
}

private fun ByteArray.indexOf(target: ByteArray, fromIndex: Int = 0): Int {
    outer@ for (i in fromIndex..this.size - target.size) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return i
    }
    return -1
}

/**
 * A completed header block: [headerEnd] is where the headers stop, [consumed] where the
 * body begins (i.e. past the blank-line delimiter).
 */
private class HeaderBlock(val headerEnd: Int, val consumed: Int)

/**
 * Locates the blank line that ends a header block, or null while none is buffered.
 * CRLFCRLF wins over LFLF at the same precedence [tryParseNextFrame] uses, so both
 * agree on where a frame's headers stop.
 */
private fun findHeaderBlock(data: ByteArray): HeaderBlock? {
    val crlf = data.indexOf("\r\n\r\n".toByteArray())
    if (crlf >= 0) return HeaderBlock(headerEnd = crlf, consumed = crlf + 4)
    val lf = data.indexOf("\n\n".toByteArray())
    if (lf >= 0) return HeaderBlock(headerEnd = lf, consumed = lf + 2)
    return null
}

/**
 * True when [line] can be the FIRST line of a Content-Length header block.
 *
 * LSP-style framing defines exactly two header fields, and only `Content-Length` makes a
 * frame decodable — so the check stays deliberately narrow: a line that isn't one of them
 * is garbage to be reported now, not a header to keep waiting on. That is what lets an
 * interleaved wrapper log line ("2026-08-07 INFO: started") be answered with -32700 and
 * skipped instead of jamming the stream (jonnyzzz/mcp-steroid#461).
 *
 * Only the first line is judged, so exotic headers deeper in a block still pass through
 * (`decodeContentLength` skips what it doesn't know). A client that leads with one loses
 * no frames either — its header line draws a spurious parse error and the frame behind it
 * parses normally.
 */
private fun couldBeFrameHeaderLine(line: String): Boolean {
    val colon = line.indexOf(':')
    if (colon <= 0) return false
    return when (line.substring(0, colon).trim().lowercase()) {
        "content-length", "content-type" -> true
        else -> false
    }
}

/**
 * Byte count of the leading run that can never begin a valid frame, or null when the
 * buffer is empty, starts a frame, or may still grow into one.
 *
 * The complement of [tryParseNextFrame]'s grammar: a frame starts either with a JSON
 * payload (NDJSON) or with a header block carrying a usable `Content-Length`. Anything
 * else is unparsable the moment its line — or its header block — is complete. Never
 * claims bytes [tryParseNextFrame] would accept, and always reports at least one byte
 * when it reports anything.
 */
private fun unparsableChunkLength(data: ByteArray): Int? {
    if (data.isEmpty()) return null
    // An NDJSON frame, complete or still arriving — readNextFrame owns it either way.
    if (startsLikeJsonPayload(data)) return null

    val headerBlock = findHeaderBlock(data)
    if (headerBlock != null) {
        val headersText = data.copyOfRange(0, headerBlock.headerEnd).toString(Charsets.UTF_8)
        // A usable Content-Length makes this a real frame, pending only on its body.
        if (decodeContentLength(headersText) != null) return null
        return headerBlock.consumed
    }

    // No blank line yet, so the header block is either still arriving or was never one.
    // A first line that cannot be a header line proves it never will be.
    val newlineIdx = data.indexOf(byteArrayOf(0x0a))
    if (newlineIdx < 0) return null  // the line itself is still arriving
    val firstLine = data.copyOfRange(0, newlineIdx).toString(Charsets.UTF_8).trimEnd('\r')
    if (couldBeFrameHeaderLine(firstLine)) return null
    return newlineIdx + 1
}

private fun tryParseNextFrame(data: ByteArray): FrameResult? {
    val headerSep = "\r\n\r\n".toByteArray()
    val altHeaderSep = "\n\n".toByteArray()

    var headerEnd = data.indexOf(headerSep)
    var delimiterLength = 4

    if (headerEnd < 0) {
        headerEnd = data.indexOf(altHeaderSep)
        delimiterLength = 2
    }

    if (headerEnd >= 0) {
        val headersText = data.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
        val bodyLength = decodeContentLength(headersText)
        if (bodyLength != null) {
            val total = headerEnd + delimiterLength + bodyLength
            if (data.size < total) return null
            val payloadText = data.copyOfRange(headerEnd + delimiterLength, total).toString(Charsets.UTF_8)
            return FrameResult(consumed = total, payloadText = payloadText, mode = "framed")
        }
    }

    // NDJSON fallback — only when buffer starts like JSON
    if (!startsLikeJsonPayload(data)) return null

    val newlineIdx = data.indexOf(byteArrayOf(0x0a))
    if (newlineIdx < 0) return null

    val payloadText = data.copyOfRange(0, newlineIdx).toString(Charsets.UTF_8).trim()
    return FrameResult(consumed = newlineIdx + 1, payloadText = payloadText, mode = "ndjson")
}

fun encodeFramedMessage(payload: String): String {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    return "Content-Length: ${bytes.size}\r\n\r\n$payload"
}

fun encodeNdjsonMessage(payload: String): String = "$payload\n"

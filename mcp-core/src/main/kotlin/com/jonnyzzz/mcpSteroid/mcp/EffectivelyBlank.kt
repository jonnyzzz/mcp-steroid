/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

/**
 * True when the string carries no usable payload: empty, whitespace, or U+FEFF byte-order marks in any
 * mix. `isBlank()` alone misses the BOM — `Character.isWhitespace('\uFEFF')` is false, yet a PowerShell
 * redirect or a Notepad save of an "empty" file produces exactly a BOM-only payload (#460).
 *
 * This is the ONE definition of "blank" for parameter validation across transports — the devrig CLI
 * parse gates, the CLI file/stdin content readers, and the MCP tool-boundary guards all delegate here,
 * so no spelling of the same payload can be accepted by one layer and refused by another.
 */
fun String.isEffectivelyBlank(): Boolean = all { it.isWhitespace() || it == '\uFEFF' }

/**
 * Strips the leading run of U+FEFF byte-order marks — the encoding artifact a Notepad save or
 * PowerShell redirect prepends. Deliberately leading-only: a U+FEFF elsewhere may be intentional
 * string-literal content, and removing it would corrupt the payload. The pair to
 * [isEffectivelyBlank]: every site that ships a payload strips with this, so all spellings of the
 * same source deliver identical bytes.
 */
fun String.trimLeadingBoms(): String = trimStart('\uFEFF')

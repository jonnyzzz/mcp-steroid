/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.math.abs

internal object KotlinVersionCompatibility {
    private val strictVersionRegex = Regex("""^\s*(\d+)\.(\d+)(?:\.(\d+))?\s*$""")
    private val embeddedVersionRegex = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    fun parseStrictVersion(text: String): KotlinVersion? {
        val match = strictVersionRegex.matchEntire(text) ?: return null
        return parseCapturedVersion(match)
    }

    fun parseVersionFromText(text: String): KotlinVersion? {
        val match = embeddedVersionRegex.find(text) ?: return null
        return parseCapturedVersion(match)
    }

    fun isCompatible(ideKotlin: KotlinVersion, bundledKotlin: KotlinVersion): Boolean {
        if (ideKotlin.major != bundledKotlin.major) return false
        return abs(ideKotlin.minor - bundledKotlin.minor) <= 1
    }

    private fun parseCapturedVersion(match: MatchResult): KotlinVersion {
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues.getOrElse(3) { "" }.ifBlank { "0" }.toInt()
        return KotlinVersion(major, minor, patch)
    }
}

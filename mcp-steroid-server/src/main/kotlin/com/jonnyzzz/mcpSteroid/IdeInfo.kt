package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

@Serializable
data class IdeInfo(
    val name: String,
    val version: String,
    val build: String
) {
    /**
     * Shared marker display name — the `"<name> <version>"` rule used by both the in-IDE self-describe and
     * devrig's discovery. Trims; drops the version when blank or already a suffix of the name.
     */
    val displayName: String
        get() {
            val trimmedName = name.trim()
            val trimmedVersion = version.trim()
            if (trimmedVersion.isEmpty()) return trimmedName
            if (trimmedName == trimmedVersion || trimmedName.endsWith(" $trimmedVersion")) return trimmedName
            return "$trimmedName $trimmedVersion".trim()
        }

}


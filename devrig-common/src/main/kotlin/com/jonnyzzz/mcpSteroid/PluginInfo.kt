package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String
)

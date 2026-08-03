/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

const val EXECUTION_BACKEND_KIND_ARGUMENT = "_execution_backend_kind"
const val EXECUTION_BACKEND_NAME_ARGUMENT = "_execution_backend_name"

/** Storage-only provenance added by a routing backend and omitted from persisted tool arguments. */
data class ExecutionBackendProvenance(
    val kind: Char,
    val name: String,
) {
    init {
        require(kind in 'a'..'z' || kind in 'A'..'Z' || kind in '0'..'9') {
            "Execution backend kind must be one ASCII letter or digit: $kind"
        }
        require(name.isNotBlank()) { "Execution backend name must not be blank" }
    }
}

fun ToolCallContext.executionBackendProvenance(): ExecutionBackendProvenance? =
    params.trustedArguments.executionBackendProvenance()

fun JsonObject.executionBackendProvenance(): ExecutionBackendProvenance? {
    val kindValue = this[EXECUTION_BACKEND_KIND_ARGUMENT]?.jsonPrimitive?.contentOrNull
    val name = this[EXECUTION_BACKEND_NAME_ARGUMENT]?.jsonPrimitive?.contentOrNull
    if (kindValue == null && name == null) return null

    require(kindValue?.length == 1) { "$EXECUTION_BACKEND_KIND_ARGUMENT must contain exactly one character" }
    require(!name.isNullOrBlank()) { "$EXECUTION_BACKEND_NAME_ARGUMENT must not be blank" }
    return ExecutionBackendProvenance(kind = kindValue.single(), name = name)
}

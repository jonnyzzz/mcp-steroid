/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

/**
 * Result from running a process.
 */
interface ProcessResult {
    val exitCode: Int?
    val stdout: String
    val stderr: String
}

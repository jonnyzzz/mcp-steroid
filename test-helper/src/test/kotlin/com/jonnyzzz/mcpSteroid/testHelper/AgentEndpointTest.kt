/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure tests for the agent base-URL rewrite: a containerized agent reaches a host-side gateway via
 * `host.docker.internal`, so a loopback base URL must be rewritten (port + path preserved). No real
 * host or external endpoint is named.
 */
class AgentEndpointTest {

    @Test
    fun `rewrites loopback host to the docker host alias, preserving port and path`() {
        assertEquals(
            "http://host.docker.internal:8088/v1/messages",
            rewriteLoopbackToDockerHost("http://127.0.0.1:8088/v1/messages"),
        )
        assertEquals(
            "https://host.docker.internal:8080/v1",
            rewriteLoopbackToDockerHost("https://localhost:8080/v1"),
        )
        assertEquals(
            "http://host.docker.internal:9000",
            rewriteLoopbackToDockerHost("http://0.0.0.0:9000"),
        )
    }

    @Test
    fun `leaves a non-loopback host untouched`() {
        assertEquals("https://api.anthropic.com", rewriteLoopbackToDockerHost("https://api.anthropic.com"))
        assertEquals(
            "https://gw.internal.example:443/v1",
            rewriteLoopbackToDockerHost("https://gw.internal.example:443/v1"),
        )
    }

    @Test
    fun `resolve returns null when none of the env vars is set`() {
        assertNull(resolveContainerAgentBaseUrl("MCP_STEROID_DEFINITELY_UNSET_BASE_URL_XYZ"))
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.docker.DOCKER_HOST_ALIAS

/**
 * Optional per-vendor API base URL for the containerized agents.
 *
 * The agent CLIs (Claude Code, Codex, …) honor the standard `<VENDOR>_BASE_URL` environment variables to
 * route their LLM traffic through an OpenAI/Anthropic-compatible endpoint instead of the public API. When
 * the operator sets such a var (e.g. to a local gateway/proxy that holds the credentials), we forward it
 * into the agent container — but rewrite a loopback host to `host.docker.internal`, because a gateway
 * listening on `127.0.0.1`/`localhost`/`0.0.0.0` on the host is only reachable from inside the container
 * through that alias (added via `--add-host=host.docker.internal:host-gateway` by the docker-start helper).
 *
 * When no such var is set this returns null and agents talk to the vendor API directly with their key,
 * exactly as before (e.g. on CI).
 */
fun resolveContainerAgentBaseUrl(vararg envNames: String): String? {
    val raw = envNames.firstNotNullOfOrNull { name -> System.getenv(name)?.takeIf { it.isNotBlank() } } ?: return null
    return rewriteLoopbackToDockerHost(raw)
}

/** Replace a loopback host in [url] with the [DOCKER_HOST_ALIAS] (port and path preserved). Pure. */
internal fun rewriteLoopbackToDockerHost(url: String): String =
    url
        .replace("://127.0.0.1", "://$DOCKER_HOST_ALIAS")
        .replace("://localhost", "://$DOCKER_HOST_ALIAS")
        .replace("://0.0.0.0", "://$DOCKER_HOST_ALIAS")

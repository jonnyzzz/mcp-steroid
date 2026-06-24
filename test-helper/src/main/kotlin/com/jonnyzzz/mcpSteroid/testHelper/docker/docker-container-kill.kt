/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.process.startProcess

fun ContainerDriver.killContainer() {
    newRunOnHost()
        .command("docker", "kill", containerId)
        .description("kill container $containerIdForLog")
        .timeoutSeconds(10)
        .quietly()
        .startProcess()
        .awaitForProcessFinish()

    log("Container $containerIdForLog removed")
}


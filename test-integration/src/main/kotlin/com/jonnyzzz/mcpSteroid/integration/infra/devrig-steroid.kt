/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/** Holds the devrig MCP stdio command used by AI agents in Docker tests. */
class DevrigSteroidDriver(
    val devrigCommand: StdioMcpCommand,
) {

    companion object {
        /** Installs the launcher inside [container] after [McpSteroidDriver.waitForMcpReady]. */
        fun deploy(
            container: ContainerDriver,
            mcpSteroid: McpSteroidDriver,
        ): DevrigSteroidDriver {
            val devrigZipFile = IdeTestFolders.devrigPackageZip
            val launcherPath = "/home/agent/devrig"
            println("[DevrigSteroidDriver] Deploying devrig MCP stdio (mcp=${mcpSteroid.guestMcpUrl}, zip=${devrigZipFile.name})...")
            container.copyToContainer(devrigZipFile, "/tmp/devrig.zip")
            container.startProcessInContainer {
                args(
                    "bash",
                    "-lc",
                    $$"""
                    set -eu
                    rm -rf /home/agent/devrig-cli "$$launcherPath"
                    mkdir -p /home/agent/devrig-cli
                    unzip -q /tmp/devrig.zip -d /home/agent/devrig-cli
                    app_dir="$(find /home/agent/devrig-cli -mindepth 1 -maxdepth 1 -type d -name 'devrig-*' | head -1)"
                    test -n "$app_dir"
                    mv "$app_dir" /home/agent/devrig-cli/app
                    chmod +x /home/agent/devrig-cli/app/bin/devrig
                    cat > "$$launcherPath" <<'EOF'
                    #!/usr/bin/env bash
                    set -eu
                    # devrig needs Java 25 (class-file v69); the IDE image pins JAVA_HOME to java-21 for
                    # project SDKs. Resolve the container's Java 25 here (container-specific) and hand it to
                    # devrig via DEVRIG_JAVA_HOME — the devrig launcher itself applies it to JAVA_HOME (that
                    # generic knob now lives in the product start script, not this harness). Pick the first
                    # temurin-25 (jdk preferred, then jre) that actually has bin/java — covers amd64 (CI) +
                    # arm64 (local); FAIL LOUDLY if none, rather than silently launching on java-21.
                    if [ -z "${DEVRIG_JAVA_HOME:-}" ]; then
                      for cand in /usr/lib/jvm/temurin-25-jdk-* /usr/lib/jvm/temurin-25-jre-* /usr/lib/jvm/temurin-25-*; do
                        if [ -x "$cand/bin/java" ]; then DEVRIG_JAVA_HOME="$cand"; break; fi
                      done
                    fi
                    if [ -z "${DEVRIG_JAVA_HOME:-}" ] || [ ! -x "${DEVRIG_JAVA_HOME}/bin/java" ]; then
                      echo "devrig launcher: no Java 25 found (set DEVRIG_JAVA_HOME). devrig is class-file v69 and needs Java 25." >&2
                      exit 1
                    fi
                    export DEVRIG_JAVA_HOME
                    export PATH="$DEVRIG_JAVA_HOME/bin:/home/agent/devrig-cli/app/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}"
                    # Enable devrig's JDWP debug agent for the long-lived `mpc` server. The devrig start
                    # script (bin/devrig) reads DEVRIG_DEBUG and picks a FREE port from 23900-23999, seeded
                    # by PID — so multiple concurrent devrig processes never clash (the old fixed-port
                    # approach crashed with "Address already in use"). quiet=y keeps the JDWP "Listening ..."
                    # line OFF stdout (`devrig mpc` speaks JSON-RPC there); suspend=n so devrig never waits.
                    # The range is published by the container; the chosen port is on devrig's stderr/log.
                    if [ "${1:-}" = "mpc" ]; then
                      export DEVRIG_DEBUG=1
                    fi
                    exec /home/agent/devrig-cli/app/bin/devrig "$@"
                    EOF
                    chmod +x "$$launcherPath"
                    "$$launcherPath" --version
                    """.trimIndent()
                )
                    .timeoutSeconds(120)
                    .description("install devrig MCP stdio launcher")
            }.awaitForProcessFinish()
                .assertExitCode(0) { "install devrig MCP stdio launcher" }

            // devrig's `mpc` JVM picks a FREE JDWP port from 23900-23999 (DEVRIG_DEBUG, set in the
            // launcher above) and announces it on its own stderr/log (quiet=y keeps it off stdout). The
            // whole range is Docker-published, so attach a "Remote JVM Debug" (module npx-kt) to the
            // host-mapped port for the in-container port devrig reports.
            println("[DEVRIG-DEBUG] devrig mpc JDWP: a free port in 23900-23999 (announced on devrig stderr); range is host-mapped (suspend=n)")

            println("[DevrigSteroidDriver] devrig MCP stdio ready")
            return DevrigSteroidDriver(StdioMcpCommand(command = launcherPath, args = listOf("mpc")))
        }
    }
}

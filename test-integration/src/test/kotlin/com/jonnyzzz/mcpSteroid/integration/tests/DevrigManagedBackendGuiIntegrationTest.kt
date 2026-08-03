/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.docker.readFromContainer
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class DevrigManagedBackendGuiIntegrationTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    fun `devrig downloads starts and stops IDEA Community inside a GUI container`() = runWithCloseableStack { lifetime ->
        val container = DevrigContainer.create(lifetime, DevrigContainerOpts(
            consoleTitle = "managed-backend",
        ))

        container.console.writeHeader("Managed-backend integration test")

        val download = container.execAndAssertWithConsoleStream(
            description = "download IDEA Community managed backend",
            timeoutSeconds = 35 * 60L,
            // devrig's downloads dir (~/.mcp-steroid/downloads) is a shared RW host cache mount
            // (DevrigContainer) — the IDE archive is fetched once and reused across runs.
            script = """
                set -euo pipefail
                "${container.devrig}" --debug backend download idea-community
            """.trimIndent(),
        )
        assertTrue(download.stdout.contains("id: idea-community-"), download.stdout)

        val id = container.execAndAssert(
            description = "resolve managed backend id",
            script = $$"""
                set -euo pipefail
                basename "$(find /home/agent/.mcp-steroid/backends -mindepth 1 -maxdepth 1 -type d -name 'idea-community-*' | sort | head -1)"
            """.trimIndent(),
        ).stdout.trim()
        assertTrue(Regex("""idea-community-\d+\.\d+.*""").matches(id), id)

        // Structural checks (file/dir existence, backend.json shape) stay in shell; the script's only
        // stdout is the resolved .vmoptions path, which we then read into the JVM and assert on in Kotlin.
        val vmOptionsPath = container.execAndAssert(
            description = "assert managed backend structural files + resolve vmoptions path",
            script = $$"""
                set -euo pipefail
                backend_dir="/home/agent/.mcp-steroid/backends/$$id"
                bundle="$(find "$backend_dir" -mindepth 1 -maxdepth 1 -type d | head -1)"
                test -n "$bundle"
                test -f "$backend_dir/backend.json"
                jq -e --arg id "$$id" '.id == $id and .productKey == "idea-community" and (.launcherPath | length > 0)' "$backend_dir/backend.json" >/dev/null
                test -f "$bundle/product-info.json" -o -f "$bundle/Contents/Resources/product-info.json"
                test -d "/home/agent/.mcp-steroid/caches/$$id/plugins/mcp-steroid/lib"
                test -f "/home/agent/.mcp-steroid/caches/$$id/plugins/mcp-steroid/EULA"
                vmoptions="$backend_dir/$(basename "$bundle").vmoptions"
                test -f "$vmoptions"
                echo "$vmoptions"
            """.trimIndent(),
        ).stdout.trim()

        // Assert the .vmoptions CONTENT in Kotlin — clearer failures than a shell `grep` whose only
        // signal is a non-zero exit code with no indication of which line was missing.
        val vmOptions = container.scope.readFromContainer(vmOptionsPath)
        val vmOptionLines = vmOptions.lines().map { it.trim() }
        val cacheBase = "/home/agent/.mcp-steroid/caches/$id"
        val requiredVmOptions = listOf(
            "-Didea.config.path=$cacheBase/config",
            "-Didea.system.path=$cacheBase/system",
            "-Didea.log.path=$cacheBase/logs",
            "-Didea.plugins.path=$cacheBase/plugins",
            "-Dmcp.steroid.idea.description.enabled=false",
            "-Dmcp.steroid.dialog.killer.enabled=true",
            "-Djb.consents.confirmation.enabled=false",
        )
        requiredVmOptions.forEach { expected ->
            assertTrue(expected in vmOptionLines) {
                "managed backend vmoptions missing line: $expected\n--- $vmOptionsPath ---\n$vmOptions"
            }
        }
        assertFalse(vmOptionLines.any { it.startsWith("-Dmcp.steroid.storage.path=") }) {
            "managed backends must use shared ~/.mcp-steroid/runs storage, not override it\n" +
                "--- $vmOptionsPath ---\n$vmOptions"
        }
        // A managed backend behaves like a normal install for updates/analytics — devrig production must
        // NOT inject the disabling flags (commit 4f36412e "let managed backends report analytics and check
        // for updates"). Assert their ABSENCE so the product decision can't silently regress.
        listOf("-Dmcp.steroid.updates.enabled=false", "-Dmcp.steroid.analytics.enabled=false").forEach { forbidden ->
            assertFalse(forbidden in vmOptionLines) {
                "managed backend vmoptions must NOT disable updates/analytics in production: found $forbidden\n" +
                    "--- $vmOptionsPath ---\n$vmOptions"
            }
        }

        val start = container.execAndAssertWithConsoleStream(
            description = "start IDEA Community managed backend",
            timeoutSeconds = 5 * 60L,
            script = """
                set -euo pipefail
                "${container.devrig}" backend start idea-community
            """.trimIndent(),
        )
        val pid = Regex("""pid: (\d+)""").find(start.stdout)?.groupValues?.get(1)
            ?: error("No pid in backend start output: ${start.stdout}")

        container.execAndAssert(
            description = "assert managed backend pid and startup config",
            script = $$"""
                set -euo pipefail
                test -f "/home/agent/.mcp-steroid/state/$$id.pid"
                test "$(cat "/home/agent/.mcp-steroid/state/$$id.pid")" = "$$pid"
                kill -0 "$$pid"
                grep -F -- "experimental.ui.onboarding.proposed.version" "/home/agent/.mcp-steroid/caches/$$id/config/options/other.xml"
                grep -F -- "switched.from.classic.to.islands" "/home/agent/.mcp-steroid/caches/$$id/config/early-access-registry.txt"
                grep -F -- 'option name="wasShown" value="true"' "/home/agent/.mcp-steroid/caches/$$id/config/options/AIOnboardingPromoWindowAdvisor.xml"
                grep -F -- 'entry key="euacommunity_accepted_version" value="999.999"' "/home/agent/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml"
                grep -F -- 'entry key="euacommunity_accepted_version" value="999.999"' "/home/agent/.java/.userPrefs/jetbrains/_!(!!cg\"p!(}!}@\"j!(k!|w\"w!'8!b!\"p!':!e@==/prefs.xml"
                grep -F -- 'rsch.send.usage.stat:1.1:0:' "/home/agent/.config/JetBrains/consentOptions/accepted"
            """.trimIndent(),
        )

//        container.waitForIntelliJBuiltInHttpServer(timeoutSeconds = 180)

        container.console.writeStep("Waiting for MCP Steroid pid marker (up to 180s)...")
        container.execAndAssert(
            description = "wait for MCP Steroid pid marker",
            timeoutSeconds = 180,
            script = $$"""
                set -euo pipefail
                marker="/home/agent/.mcp-steroid/markers/$$pid.mcp-steroid"
                deadline=$((SECONDS + 180))
                found=0
                while [ "$SECONDS" -lt "$deadline" ]; do
                  if [ -f "$marker" ] && jq -e --argjson pid "$$pid" '.pid == $pid and (.mcpSteroidServer.mcpUrl | startswith("http://")) and .ide.name and .plugin.id == "com.jonnyzzz.mcp-steroid"' "$marker" >/dev/null; then
                    cat "$marker"
                    found=1
                    break
                  fi
                  sleep 2
                done
                if [ "$found" = "1" ]; then
                  :
                else
                echo "MCP Steroid marker did not appear at $marker" >&2
                find /home/agent/.mcp-steroid/markers -maxdepth 1 -name '*.mcp-steroid' -print -exec cat {} \; >&2 || true
                exit 1
                fi
            """.trimIndent(),
        )
        container.console.writeSuccess("MCP Steroid marker present")

        container.execAndAssertWithConsoleStream(
            description = "assert proxy discovers managed backend marker",
            timeoutSeconds = 120,
            script = $$"""
                set -euo pipefail
                "$${container.devrig}" backend --json > /tmp/backend.json
                cat /tmp/backend.json
                jq -e --argjson pid "$$pid" '.mcpSteroidBackends[] | select(.pid == $pid)' /tmp/backend.json
            """.trimIndent(),
        )

        container.execAndAssert(
            description = "assert managed backend cache paths",
            script = $$"""
                set -euo pipefail
                test -d "/home/agent/.mcp-steroid/caches/$$id/config"
                test -d "/home/agent/.mcp-steroid/caches/$$id/system"
                test -d "/home/agent/.mcp-steroid/caches/$$id/logs"
                test -d "/home/agent/.mcp-steroid/caches/$$id/plugins"
                test ! -e "/home/agent/.mcp-steroid/caches/$$id/execution-storage"
                test "$(find "/home/agent/.mcp-steroid/caches/$$id/logs" -type f | wc -l)" -gt 0
            """.trimIndent(),
        )

        container.execAndAssertWithConsoleStream(
            description = "stop IDEA Community managed backend",
            timeoutSeconds = 120,
            script = $$"""
                set -euo pipefail
                "$${container.devrig}" backend stop idea-community
                test ! -f "/home/agent/.mcp-steroid/state/$$id.pid"
                deadline=$((SECONDS + 30))
                while kill -0 $$pid 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
                  sleep 1
                done
                if kill -0 $$pid 2>/dev/null; then
                  ps -fp $$pid >&2 || true
                  exit 1
                fi
            """.trimIndent(),
        )
        container.console.writeSuccess("Managed backend lifecycle verified")
    }
}

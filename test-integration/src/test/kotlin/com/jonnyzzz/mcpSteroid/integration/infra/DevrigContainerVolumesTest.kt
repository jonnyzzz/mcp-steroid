/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevrigContainerVolumesTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dependency caches are not mounted by default`() {
        val volumes = devrigContainerVolumes(
            runDir = tempDir.toFile(),
            opts = DevrigContainerOpts(consoleTitle = "test"),
        )

        assertEquals(
            setOf("/mcp-run-dir", DEVRIG_GUEST_LOGS_DIR, DEVRIG_GUEST_DOWNLOADS_DIR),
            volumes.mapTo(mutableSetOf()) { it.guest },
        )
        assertNoDevrigHomeMount(volumes)
    }

    @Test
    fun `dependency cache opt in adds only Maven and Gradle caches`() {
        val defaultVolumes = devrigContainerVolumes(
            runDir = tempDir.resolve("default").toFile(),
            opts = DevrigContainerOpts(consoleTitle = "default"),
        )
        val cachedVolumes = devrigContainerVolumes(
            runDir = tempDir.resolve("cached").toFile(),
            opts = DevrigContainerOpts(
                consoleTitle = "cached",
                mountDependencyCaches = true,
            ),
        )

        val defaultGuests = defaultVolumes.mapTo(mutableSetOf()) { it.guest }
        val cachedByGuest = cachedVolumes.associateBy { it.guest }
        assertEquals(
            setOf("/home/agent/.m2", "/home/agent/.gradle"),
            cachedByGuest.keys - defaultGuests,
        )
        assertEquals(
            IdeTestFolders.dependencyCacheDir.resolve("m2").canonicalFile,
            cachedByGuest.getValue("/home/agent/.m2").host.canonicalFile,
        )
        assertEquals(
            IdeTestFolders.dependencyCacheDir.resolve("gradle").canonicalFile,
            cachedByGuest.getValue("/home/agent/.gradle").host.canonicalFile,
        )
        assertEquals("rw", cachedByGuest.getValue("/home/agent/.m2").mode)
        assertEquals("rw", cachedByGuest.getValue("/home/agent/.gradle").mode)
        assertNoDevrigHomeMount(cachedVolumes)
    }

    private fun assertNoDevrigHomeMount(volumes: List<ContainerVolume>) {
        assertFalse(
            volumes.any { it.guest.trimEnd('/') == DEVRIG_GUEST_HOME },
            "The whole devrig home must stay container-local so backend markers and configuration start clean",
        )
    }
}

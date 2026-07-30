/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DevrigUpdateCheckerTest {
    @Test
    fun `checkForUpdates fetches`() = runTest {
        val info = fetchVersionInfo()

        assertNotNull(info, "It should have base version")
    }

    @Test
    fun `generated metadata carries the version and the snapshot flag`() {
        val build = DevrigVersionMetadata.getBuildVersion()
        assertEquals(DevrigVersionMetadata.getDevrigVersion(), build.value)
        assertEquals(build.value.contains("SNAPSHOT"), build.isSnapshotBuild)
    }

    @Test
    fun `update gate notifies only when promoted is newer than current`() {
        // the production gate checkForUpdates calls
        fun updateAvailable(promotedBase: String, current: String) = DevrigVersion.isUpdateAvailable(
            current = DevrigVersion.parse(current),
            promoted = DevrigVersion.parse(promotedBase),
        )

        assertTrue(updateAvailable("0.102", "0.101-5d18a187"))
        assertFalse(updateAvailable("0.101", "0.101-5d18a187"))
        assertFalse(updateAvailable("0.101", "0.101.441-jb-abcdef1"))

        // a local snapshot build is never nagged, whatever gets promoted
        assertFalse(updateAvailable("999.0", "0.101.19999-SNAPSHOT-5d18a187"))
    }
}

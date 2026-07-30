/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevrigVersionTest {
  @Test
  fun snapshotBuildIsNewerThanAnyNonSnapshot() {
    val snapshot = DevrigVersion.parse("0.101.19999-SNAPSHOT-5d18a187")
    assertTrue(snapshot.isSnapshotBuild)
    assertTrue(snapshot > DevrigVersion.parse("0.101"))
    assertTrue(snapshot > DevrigVersion.parse("0.101.442-jb-abcdef1"))
    // even a numerically larger promoted version never beats a snapshot build
    assertTrue(snapshot > DevrigVersion.parse("999.0"))
    assertTrue(DevrigVersion.parse("999.0") < snapshot)
  }

  @Test
  fun twoSnapshotBuildsCompareByVersion() {
    val older = DevrigVersion.parse("0.100.19999-SNAPSHOT-aaaaaaa")
    val newer = DevrigVersion.parse("0.101.19999-SNAPSHOT-bbbbbbb")
    assertTrue(newer > older)
    assertTrue(older < newer)
  }

  @Test
  fun nonSnapshotVersionsCompareByVersion() {
    assertTrue(DevrigVersion.parse("0.102") > DevrigVersion.parse("0.101"))
    assertTrue(DevrigVersion.parse("0.100") > DevrigVersion.parse("0.95.0"))
    assertEquals(0, DevrigVersion.parse("0.101").compareTo(DevrigVersion.parse("0.101")))
  }

  @Test
  fun parseDetectsTheSnapshotMarker() {
    assertTrue(DevrigVersion.parse("0.101.19999-SNAPSHOT-5d18a187").isSnapshotBuild)
    assertFalse(DevrigVersion.parse("0.101").isSnapshotBuild)
    assertFalse(DevrigVersion.parse("0.101.441-jb-abcdef1").isSnapshotBuild)
    assertFalse(DevrigVersion.parse("0.101-5d18a187").isSnapshotBuild)
    assertEquals("0.101-5d18a187", DevrigVersion.parse("0.101-5d18a187").value)
  }

  @Test
  fun updateNotificationGate() {
    // the production gate both update checkers call
    fun updateAvailable(promotedBase: String, current: String) = DevrigVersion.isUpdateAvailable(
      current = DevrigVersion.parse(current),
      promoted = DevrigVersion.parse(promotedBase),
    )

    // release build <base>-<hash>: same promoted base is not an update, a newer base is
    assertFalse(updateAvailable("0.101", "0.101-5d18a187"))
    assertTrue(updateAvailable("0.102", "0.101-5d18a187"))

    // the git hash is build metadata, not precedence: a hash starting with a/b must not
    // read as the ALPHA/BETA marker and demote the build below its own promoted base
    assertFalse(updateAvailable("0.86.0", "0.86.0-a1b2c3d"))
    assertFalse(updateAvailable("0.86.0", "0.86.0-b1c2d3e"))
    assertEquals(0, DevrigVersion.parse("0.86.0-a1b2c3d").compareTo(DevrigVersion.parse("0.86.0-f9e8d7c")))

    // CI build <base>.<counter>-(gh|jb)-<hash>: same-base promoted is not an update
    assertFalse(updateAvailable("0.101", "0.101.441-jb-abcdef1"))
    assertTrue(updateAvailable("0.102", "0.101.441-jb-abcdef1"))

    // local snapshot build: never notified, whatever gets promoted
    assertFalse(updateAvailable("0.102", "0.101.19999-SNAPSHOT-5d18a187"))
    assertFalse(updateAvailable("999.0", "0.101.19999-SNAPSHOT-5d18a187"))
  }
}

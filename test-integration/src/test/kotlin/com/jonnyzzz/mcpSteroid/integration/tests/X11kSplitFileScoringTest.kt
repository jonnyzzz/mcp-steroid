/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD unit tests for [scoreSplitFile] — the evidence-based scorer of the x11k "split the god file"
 * A/B experiment (`x11k__split_file`). The agent splits `X11State.kt` (11,567 lines at the pinned
 * x11k commit cfdf1f7d) into cohesive files; the scorer never trusts a bare "done" claim:
 *  - `REMAINING_LINES` (from `wc -l`, both legs run it identically) must be ≤ the threshold,
 *  - at least N distinct `NEW_FILE:` paths (`.kt`, different from the original file),
 *  - `BUILD_AFTER_SPLIT: SUCCESS`,
 *  - a residue check both legs run identically: `RESIDUE: <SentinelClass>=<grep -c count>` must be
 *    exactly 1 for every sentinel — proving the moved declarations still exist exactly once
 *    (not deleted, not left duplicated in both the old and the new file).
 */
class X11kSplitFileScoringTest {

    private val sentinels = setOf("XSyncCounter", "XKeyboardMapping", "XDrawingCommand")

    private fun score(output: String) = scoreSplitFile(
        output = output,
        originalFileName = "X11State.kt",
        maxRemainingLines = 10_000,
        minNewFiles = 3,
        sentinels = sentinels,
    )

    @Test
    fun `full success run is safe`() {
        val output = """
            I used IntelliJ's Move refactoring for each declaration group.
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/X11SyncModel.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/X11KeyboardModel.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/X11DrawingModel.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/X11WindowModel.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        val s = score(output)
        assertTrue(s.splitDone)
        assertEquals(9650, s.remainingLines)
        assertEquals(4, s.newFiles.size)
        assertEquals(true, s.buildGreen)
        assertTrue(s.residueClean)
        assertTrue(s.safe)
    }

    @Test
    fun `file barely shrunk is not safe`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 11200
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/C.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        assertFalse(score(output).safe)
    }

    @Test
    fun `duplicated residue declaration is not safe`() {
        // A copy-paste split that left XDrawingCommand in BOTH the old and the new file.
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/C.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=2
        """.trimIndent()
        val s = score(output)
        assertFalse(s.residueClean)
        assertFalse(s.safe)
    }

    @Test
    fun `missing residue markers is not safe`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/C.kt
            BUILD_AFTER_SPLIT: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertFalse(s.residueClean)
        assertFalse(s.safe)
    }

    @Test
    fun `build failure is not safe`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/C.kt
            BUILD_AFTER_SPLIT: FAILURE
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        val s = score(output)
        assertEquals(false, s.buildGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `too few new files is not safe`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        assertFalse(score(output).safe)
    }

    @Test
    fun `duplicate and original-file NEW_FILE entries are not counted`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 9650
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/X11State.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        val s = score(output)
        assertEquals(1, s.newFiles.size)
        assertFalse(s.safe)
    }

    @Test
    fun `original file removed entirely counts as remaining zero`() {
        val output = """
            SPLIT_DONE: yes
            REMAINING_LINES: 0
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/A.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/B.kt
            NEW_FILE: src/main/kotlin/org/jonnyzzz/xserver/C.kt
            BUILD_AFTER_SPLIT: SUCCESS
            RESIDUE: XSyncCounter=1
            RESIDUE: XKeyboardMapping=1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        val s = score(output)
        assertEquals(0, s.remainingLines)
        assertTrue(s.safe)
    }

    @Test
    fun `markdown-wrapped markers still parse`() {
        val output = """
            **SPLIT_DONE**: yes
            **REMAINING_LINES**: 9650
            - **NEW_FILE**: `src/main/kotlin/org/jonnyzzz/xserver/A.kt`
            - **NEW_FILE**: `src/main/kotlin/org/jonnyzzz/xserver/B.kt`
            - **NEW_FILE**: `src/main/kotlin/org/jonnyzzz/xserver/C.kt`
            **BUILD_AFTER_SPLIT**: SUCCESS
            RESIDUE: `XSyncCounter=1`
            RESIDUE: XKeyboardMapping = 1
            RESIDUE: XDrawingCommand=1
        """.trimIndent()
        val s = score(output)
        assertEquals(3, s.newFiles.size)
        assertTrue(s.residueClean)
        assertTrue(s.safe)
    }

    @Test
    fun `no markers at all is not safe`() {
        val s = score("I refactored the file successfully, everything is great now.")
        assertFalse(s.splitDone)
        assertNull(s.remainingLines)
        assertEquals(0, s.newFiles.size)
        assertFalse(s.safe)
    }
}

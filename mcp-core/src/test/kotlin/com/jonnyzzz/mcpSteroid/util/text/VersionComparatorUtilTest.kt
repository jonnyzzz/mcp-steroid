/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.text

import com.jonnyzzz.mcpSteroid.util.text.VersionComparatorUtil.VersionTokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests beyond the ported [VersionComparatorTest]: the null-tolerant helper API,
 * the [VersionComparatorUtil.COMPARATOR] field, token classification, the
 * leading-zero rules of the digit comparison, and this repo's real version lines.
 */
class VersionComparatorUtilTest {
  @Test
  fun maxAndMin() {
    assertEquals("1.1", VersionComparatorUtil.max("1.1", "1.0.1"))
    assertEquals("1.0.1", VersionComparatorUtil.min("1.1", "1.0.1"))

    assertEquals("1.0", VersionComparatorUtil.max(null, "1.0"))
    assertNull(VersionComparatorUtil.min(null, "1.0"))
    assertNull(VersionComparatorUtil.max(null, null))
    assertNull(VersionComparatorUtil.min("1.0", null))
  }

  @Test
  fun comparatorFieldSortsWithNullsFirst() {
    val sorted = listOf("1.10", "1.0rc1", "1.2", null, "1.0").sortedWith(VersionComparatorUtil.COMPARATOR)
    assertEquals(listOf(null, "1.0rc1", "1.0", "1.2", "1.10"), sorted)
  }

  @Test
  fun tokenTypeLookup() {
    assertEquals(VersionTokenType._WS, VersionTokenType.lookup(null))
    assertEquals(VersionTokenType._WS, VersionTokenType.lookup("  "))
    assertEquals(VersionTokenType._WS, VersionTokenType.lookup("000"))
    assertEquals(VersionTokenType._DIGITS, VersionTokenType.lookup("0123"))
    assertEquals(VersionTokenType.RC, VersionTokenType.lookup("Rc"))
    assertEquals(VersionTokenType.SNAPSHOT, VersionTokenType.lookup(" snapshot "))
    assertEquals(VersionTokenType.BUNDLED, VersionTokenType.lookup("bundled"))
    assertEquals(VersionTokenType._WORD, VersionTokenType.lookup("trash"))
  }

  @Test
  fun leadingZerosInDigitTokens() {
    // fewer leading zeros wins when digit runs differ only by zero-padding
    assertTrue(VersionComparatorUtil.compare("1.01", "1.002") > 0)
    assertTrue(VersionComparatorUtil.compare("1.2", "1.02") > 0)
    // all-zero runs are classified _WS, so zero-padding alone does not differ
    assertEquals(0, VersionComparatorUtil.compare("1.0", "1.00"))
    assertEquals(0, VersionComparatorUtil.compare("1.00", "1.00"))
    // equally padded runs compare as numbers: 010 > 02
    assertTrue(VersionComparatorUtil.compare("1.010", "1.02") > 0)
  }

  @Test
  fun unicodeWhitespaceIsTrimmed() {
    // Kotlin's trim() strips Unicode whitespace, a deliberate deviation from
    // the Java original's String.trim(); EM SPACE (U+2003) exercises that
    val emSpace = 0x2003.toChar()
    assertEquals(0, VersionComparatorUtil.compare("${emSpace}1.0${emSpace}", "1.0"))
    assertEquals(0, VersionComparatorUtil.compare(" 1.0 ", "1.0"))
    assertEquals(0, VersionComparatorUtil.compare("\t1.0\n", "1.0"))
    assertEquals(listOf("1", "0"), VersionComparatorUtil.splitVersionString(" 1.0 "))
  }

  @Test
  fun splitOfEmptyOrBlankIsEmpty() {
    // the ported test's join-based assertion cannot tell [""] from []; pin it exactly
    assertEquals(emptyList<String>(), VersionComparatorUtil.splitVersionString(""))
    assertEquals(emptyList<String>(), VersionComparatorUtil.splitVersionString("   "))
  }

  @Test
  fun customTokenPrioritizer() {
    val constantPriority = VersionComparatorUtil.TokenPrioritizer { 42 }
    // with priorities tied, words fall back to lexicographic comparison
    // ("bb"/"aa", since single "b"/"a" are the BETA/ALPHA token aliases)...
    assertTrue(VersionComparatorUtil.compare("bb", "aa", constantPriority) > 0)
    // ...and digit runs to numeric comparison
    assertTrue(VersionComparatorUtil.compare("2", "10", constantPriority) < 0)
  }

  @Test
  fun pastReleaseVersionsSortChronologically() {
    // real MCP Steroid / devrig release lines from this repo's git tags, oldest first
    val releases = listOf(
      "0.86.0", "0.87.0", "0.88.0", "0.89.0", "0.90.0",
      "0.91.0", "0.92.0", "0.93.0", "0.94.0", "0.95.0",
      "0.100", "0.101",
    )
    assertEquals(releases, releases.reversed().sortedWith(VersionComparatorUtil.COMPARATOR))
    // 0.100 came after 0.95.0: numeric comparison, not lexicographic
    assertTrue(VersionComparatorUtil.compare("0.100", "0.95.0") > 0)
  }

  @Test
  fun ciBuildVersionsSortByCounter() {
    // CI build shape from the root build script: <VERSION>.<counter>-(gh|jb)-<hash>
    assertTrue(VersionComparatorUtil.compare("0.92.0.442-jb-abcdef1", "0.92.0.441-jb-fedcba9") > 0)
    assertTrue(VersionComparatorUtil.compare("0.101.1-gh-abcdef1", "0.100.999-jb-fedcba9") > 0)
  }

  @Test
  fun localSnapshotBuildIsTheNewestOfItsLine() {
    // local dev builds substitute the 19999-SNAPSHOT counter, so they sort after any
    // realistic CI counter of the same base version and after every earlier release
    val snapshot = "0.101.19999-SNAPSHOT-5d18a187"
    assertTrue(VersionComparatorUtil.compare(snapshot, "0.101.442-jb-abcdef1") > 0)
    assertTrue(VersionComparatorUtil.compare(snapshot, "0.101") > 0)
    assertTrue(VersionComparatorUtil.compare(snapshot, "0.100") > 0)
    assertTrue(VersionComparatorUtil.compare(snapshot, "0.95.0") > 0)
    // the bare SNAPSHOT marker (no counter) still sorts before its release, as in Maven
    assertTrue(VersionComparatorUtil.compare("0.101-SNAPSHOT", "0.101") < 0)
  }
}

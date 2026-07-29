// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
//
// This file is derived from IntelliJ IDEA Community Edition:
//   platform/util/testSrc/com/intellij/util/text/VersionComparatorTest.java
// Modifications (Apache License 2.0, Section 4(b)):
//   - converted from Java to Kotlin and from JUnit 3 (junit.framework.TestCase) to JUnit 5
//   - repackaged from com.intellij.util.text to com.jonnyzzz.mcpSteroid.util.text
//   - com.intellij.openapi.util.text.StringUtil.join replaced with kotlin.collections.joinToString
// See the NOTICE file at the repository root.
package com.jonnyzzz.mcpSteroid.util.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionComparatorTest {
  @Test
  fun testNulls() {
    assertVerGreater("a", null)
    assertVerLess(null, "null")
    assertVerEquals(null, null)
  }

  @Test
  fun testSplit() {
    assertStrsEquals(arrayOf("a", "b"), VersionComparatorUtil.splitVersionString("a b"))
    assertStrsEquals(arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "#ab"),
                     VersionComparatorUtil.splitVersionString("1(2)3.4.5_6;7:/8,9 10+11~12#ab"))
    assertStrsEquals(arrayOf("ab", "12", "ba", "6"), VersionComparatorUtil.splitVersionString("ab12ba6"))
    assertStrsEquals(arrayOf("ab", "12", "ba"), VersionComparatorUtil.splitVersionString("ab12ba"))
    assertStrsEquals(arrayOf("12", "ba"), VersionComparatorUtil.splitVersionString("12ba"))
    assertStrsEquals(arrayOf("12", "ba", "9"), VersionComparatorUtil.splitVersionString("12ba9"))
    assertStrsEquals(arrayOf("1", "0", "RC", "2"), VersionComparatorUtil.splitVersionString("1.0RC2"))
    assertStrsEquals(arrayOf("1", "0", "M", "1"), VersionComparatorUtil.splitVersionString("1.0M1"))
    assertStrsEquals(arrayOf("000123456789"), VersionComparatorUtil.splitVersionString("000123456789"))
    assertStrsEquals(arrayOf(""), VersionComparatorUtil.splitVersionString(""))
  }

  @Test
  fun testExamples() {
    assertVerEquals("1", "1")
    assertVerLess("1", "2")

    assertVerEquals("1.0.", "1.0")
    assertVerLess("1.0", "2.0")
    assertVerGreater("1.2", "1.02")
    assertVerGreater("1.1", "1.02")
    assertVerLess("1.1e", "1.1f")
    assertVerGreater("1.1", "1.02")
    assertVerGreater("1.01", "1.002")
    assertVerLess("1.01", "1.02")
    assertVerLess("1.35", "1.36")
    assertVerGreater("2.35", "1.36")

    assertVerLess("1.0rc1", "1.0release")
    assertVerGreater("1.0", "1.0rc")
    assertVerGreater("1.0.1", "1.0sp3")
    assertVerLess("1.02", "1.12")
    assertVerGreater("1.0sp", "1.0")
    assertVerLess("1.0bred", "1.0.1")
    assertVerEquals("1.3.0", "1.3")

    assertVerLess("r.1", "r.666")
    assertVerGreater("1.6-beta-1", "1.5.6")
    assertVerLess("2.7.1.final", "2.7.2.rc1")
    assertVerGreater("2.7.1.final", "2.7.1.rc1")
    assertVerLess("1.0M1", "1.0RC2")

    assertVerGreater(
        "11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112",
        "11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111")
  }

  @Test
  fun testFormal() {
    assertVerEquals("7-snapshot", "7-sNaP")
    assertVerEquals("7-alpha", "7-a")
    assertVerEquals("7-beta", "7-b")
    assertVerEquals("7-rel", "7-release")
    assertVerEquals("7-rel", "7-r")
    assertVerEquals("7-rel", "7-final")

    assertVerLess("snapshot", "m")
    assertVerLess("m", "eap")
    assertVerLess("eap", "alpha")
    assertVerLess("alpha", "beta")
    assertVerLess("beta", "rc")
    assertVerLess("rc", "")
    assertVerLess("", "sp")
    assertVerLess("sp", "release")
    assertVerLess("release", "trash")
    assertVerLess("trash", "1")
    assertVerLess("preview", "p")
  }

  private fun assertStrsEquals(expected: Array<String>, actual: Collection<String>) {
    assertEquals(expected.joinToString("^"), actual.joinToString("^"))
  }

  private fun assertVerEquals(v1: String?, v2: String?) {
    assertEquals(0, VersionComparatorUtil.compare(v1, v2))
  }

  private fun assertVerLess(v1: String?, v2: String?) {
    assertTrue(VersionComparatorUtil.compare(v1, v2) < 0)
    assertTrue(VersionComparatorUtil.compare(v2, v1) > 0)

    assertVerEquals(v1, v1)
    assertVerEquals(v2, v2)
  }

  private fun assertVerGreater(v1: String?, v2: String?) {
    assertTrue(VersionComparatorUtil.compare(v1, v2) > 0)
    assertTrue(VersionComparatorUtil.compare(v2, v1) < 0)

    assertVerEquals(v1, v1)
    assertVerEquals(v2, v2)
  }
}

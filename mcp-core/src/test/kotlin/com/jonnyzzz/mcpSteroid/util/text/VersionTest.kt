package com.jonnyzzz.mcpSteroid.util.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTest {
  @Test
  fun comparisonOperators() {
    assertTrue(Version("1.10") > Version("1.9"))
    assertTrue(Version("1.0rc1") < Version("1.0"))
    assertTrue(Version("1.0") >= Version("1.0.0"))
    assertTrue(Version("1.0") <= Version("1.0.0"))
    assertTrue(Version("7-snapshot") < Version("7"))
    assertTrue(Version("2.7.1.final") > Version("2.7.1.rc1"))
  }

  @Test
  fun sorting() {
    val sorted = listOf(Version("1.10"), Version("1.0rc1"), Version("1.2"), Version("1.0")).sorted()
    assertEquals(listOf("1.0rc1", "1.0", "1.2", "1.10"), sorted.map { it.value })
  }

  @Test
  fun minMaxOf() {
    assertEquals(Version("1.10"), maxOf(Version("1.10"), Version("1.9")))
    assertEquals(Version("1.9"), minOf(Version("1.10"), Version("1.9")))
  }

  @Test
  fun equalityIsTextualWhileOrderingIsSemantic() {
    assertEquals(0, Version("1.3").compareTo(Version("1.3.0")))
    assertNotEquals(Version("1.3"), Version("1.3.0"))
    assertEquals(Version("1.3"), Version("1.3"))
  }

  @Test
  fun toStringIsTheRawValue() {
    assertEquals("1.0-RC2", Version("1.0-RC2").toString())
  }
}

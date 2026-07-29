// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
//
// This file is derived from IntelliJ IDEA Community Edition:
//   platform/util-rt/src/com/intellij/util/text/VersionComparatorUtil.java
// Modifications (Apache License 2.0, Section 4(b)):
//   - converted to idiomatic Kotlin: object instead of a final class with static members,
//     Kotlin nullable types instead of org.jetbrains.annotations, Regex/split/getOrNull
//     instead of Pattern/StringTokenizer/null-padded lists, leading-zero counts in
//     compareNumbers
//   - uses Kotlin's Unicode-aware trim() where the original used Java String.trim()
//     (which strips only chars <= U+0020)
//   - repackaged from com.intellij.util.text to com.jonnyzzz.mcpSteroid.util.text to avoid
//     clashing with the identically-named platform class when loaded inside the IDE
// See the NOTICE file at the repository root.
package com.jonnyzzz.mcpSteroid.util.text

import java.util.Locale

/**
 * Provides advanced version comparison functionality with support for various version formats.
 * Superior to `com.intellij.openapi.util.text.StringUtil#compareVersionNumbers` by handling complex version patterns.
 *
 * Used for comparing versions of TeamCity plugins and Ruby gems (and probably more).
 *
 * See [Version] for a value type that wraps this ordering into [Comparable].
 *
 * @author Leonid Shalupov
 */
object VersionComparatorUtil {
  fun interface TokenPrioritizer {
    fun getPriority(token: String?): Int
  }

  /** Splits a token into runs of digits and runs of non-digits: `ab12ba6` -> `ab`, `12`, `ba`, `6`. */
  private val WORDS_SPLITTER = Regex("""\d+|\D+""")

  val COMPARATOR: Comparator<String?> = Comparator { v1, v2 -> compare(v1, v2) }

  private val DEFAULT_TOKEN_PRIORITIZER = TokenPrioritizer { token -> VersionTokenType.lookup(token).priority }

  fun max(v1: String?, v2: String?): String? = if (compare(v1, v2) > 0) v1 else v2

  fun min(v1: String?, v2: String?): String? = if (compare(v1, v2) < 0) v1 else v2

  // Entries prefixed with '_' are synthetic token classes, never matched literally by
  // lookup() — renaming _WS to WS would make the token "ws" classify as whitespace.
  enum class VersionTokenType(val priority: Int) {
    SNAP(10), SNAPSHOT(10),
    M(20),
    EAP(25), PRE(25), PREVIEW(25),
    ALPHA(30), A(30),
    BETA(40), BETTA(40), B(40),
    RC(50),
    _WS(60),
    SP(70),
    REL(80), RELEASE(80), R(80), FINAL(80),
    _WORD(90),
    _DIGITS(100),
    BUNDLED(666);

    companion object {
      fun lookup(str: String?): VersionTokenType {
        val trimmed = str?.trim().orEmpty()
        if (trimmed.isEmpty()) return _WS

        entries.firstOrNull { !it.name.startsWith('_') && it.name.equals(trimmed, ignoreCase = true) }
          ?.let { return it }

        return when {
          trimmed.all { it == '0' } -> _WS
          trimmed.all { it in '0'..'9' } -> _DIGITS
          else -> _WORD
        }
      }
    }
  }

  fun splitVersionString(ver: String): List<String> =
    ver.trim()
      .split('(', ')', '.', '_', '-', ';', ':', '/', ',', ' ', '+', '~')
      .filter { it.isNotEmpty() }
      .flatMap { token -> WORDS_SPLITTER.findAll(token).map { it.value } }

  /**
   * Compare two version strings. See TeamCity documentation on requirements comparison
   * for formal description.
   *
   * Examples: 1.0rc1 < 1.0release, 1.0 < 1.0.1, 1.1 > 1.02
   * @return 0 if ver1 equals ver2, positive value if ver1 > ver2, negative value if ver1 < ver2
   */
  fun compare(ver1: String?, ver2: String?): Int = compare(ver1, ver2, DEFAULT_TOKEN_PRIORITIZER)

  fun compare(ver1: String?, ver2: String?, tokenPriorityProvider: TokenPrioritizer): Int {
    if (ver1 == null || ver2 == null) return compareValues(ver1, ver2)

    val s1 = splitVersionString(ver1.lowercase(Locale.ENGLISH))
    val s2 = splitVersionString(ver2.lowercase(Locale.ENGLISH))

    for (i in 0 until maxOf(s1.size, s2.size)) {
      val e1 = s1.getOrNull(i)
      val e2 = s2.getOrNull(i)

      val byPriority = tokenPriorityProvider.getPriority(e1).compareTo(tokenPriorityProvider.getPriority(e2))
      if (byPriority != 0) return byPriority

      // a padding token (past the shorter version's end) has no text to compare
      if (e1 == null || e2 == null) continue

      val byValue = when (VersionTokenType.lookup(e1)) {
        VersionTokenType._WORD -> e1.compareTo(e2)
        VersionTokenType._DIGITS -> compareNumbers(e1, e2)
        else -> 0
      }
      if (byValue != 0) return byValue
    }

    return 0
  }

  private fun compareNumbers(n1: String, n2: String): Int {
    val zeros1 = n1.takeWhile { it == '0' }.length
    val zeros2 = n2.takeWhile { it == '0' }.length
    return when {
      // more leading zeros sorts lower: 1.2 > 1.02, 1.01 > 1.002
      zeros1 != zeros2 -> zeros2.compareTo(zeros1)
      // equally padded: a longer digit run is a bigger number
      n1.length != n2.length -> n1.length.compareTo(n2.length)
      else -> n1.compareTo(n2)
    }
  }
}

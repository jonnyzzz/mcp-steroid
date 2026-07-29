// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
//
// This file is derived from IntelliJ IDEA Community Edition:
//   platform/util-rt/src/com/intellij/util/text/VersionComparatorUtil.java
// Modifications (Apache License 2.0, Section 4(b)):
//   - converted from Java to Kotlin: object instead of a final class with static members,
//     Kotlin nullable types instead of org.jetbrains.annotations, cached enum `entries`
//     instead of a hand-cached values() array
//   - repackaged from com.intellij.util.text to com.jonnyzzz.mcpSteroid.util.text to avoid
//     clashing with the identically-named platform class when loaded inside the IDE
// See the NOTICE file at the repository root.
package com.jonnyzzz.mcpSteroid.util.text

import java.util.Locale
import java.util.StringTokenizer
import java.util.regex.Pattern

/**
 * Provides advanced version comparison functionality with support for various version formats.
 * Superior to `com.intellij.openapi.util.text.StringUtil#compareVersionNumbers` by handling complex version patterns.
 *
 * Used for comparing versions of TeamCity plugins and Ruby gems (and probably more).
 *
 * @author Leonid Shalupov
 */
object VersionComparatorUtil {
  fun interface TokenPrioritizer {
    fun getPriority(token: String?): Int
  }

  private val WORDS_SPLITTER = Pattern.compile("\\d+|[^\\d]+")
  private val ZERO_PATTERN = Pattern.compile("0+")
  private val DIGITS_PATTERN = Pattern.compile("\\d+")

  val COMPARATOR: Comparator<String?> = Comparator { v1, v2 -> compare(v1, v2) }

  private val DEFAULT_TOKEN_PRIORITIZER = TokenPrioritizer { token -> VersionTokenType.lookup(token).priority }

  fun max(v1: String?, v2: String?): String? = if (compare(v1, v2) > 0) v1 else v2

  fun min(v1: String?, v2: String?): String? = if (compare(v1, v2) < 0) v1 else v2

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
        if (str == null) {
          return _WS
        }

        // Java String.trim() semantics (strips chars <= U+0020), not Kotlin's Unicode-aware trim()
        val trimmed = str.trim { it <= ' ' }
        if (trimmed.isEmpty()) {
          return _WS
        }

        for (token in entries) {
          val name = token.name
          if (name[0] != '_' && name.equals(trimmed, ignoreCase = true)) {
            return token
          }
        }

        if (ZERO_PATTERN.matcher(trimmed).matches()) {
          return _WS
        }

        if (DIGITS_PATTERN.matcher(trimmed).matches()) {
          return _DIGITS
        }

        return _WORD
      }
    }
  }

  fun splitVersionString(ver: String): List<String> {
    // Java String.trim() semantics (strips chars <= U+0020), not Kotlin's Unicode-aware trim()
    val st = StringTokenizer(ver.trim { it <= ' ' }, "()._-;:/, +~")
    val result = ArrayList<String>()

    while (st.hasMoreTokens()) {
      val matcher = WORDS_SPLITTER.matcher(st.nextToken())

      while (matcher.find()) {
        result.add(matcher.group())
      }
    }

    return result
  }

  /**
   * Compare two version strings. See TeamCity documentation on requirements comparison
   * for formal description.
   *
   * Examples: 1.0rc1 < 1.0release, 1.0 < 1.0.1, 1.1 > 1.02
   * @return 0 if ver1 equals ver2, positive value if ver1 > ver2, negative value if ver1 < ver2
   */
  fun compare(ver1: String?, ver2: String?): Int = compare(ver1, ver2, DEFAULT_TOKEN_PRIORITIZER)

  fun compare(ver1: String?, ver2: String?, tokenPriorityProvider: TokenPrioritizer): Int {
    // todo duplicates com.intellij.openapi.util.text.StringUtil.compareVersionNumbers()
    // todo please refactor next time you make changes here
    if (ver1 == null) {
      return if (ver2 == null) 0 else -1
    }
    if (ver2 == null) {
      return 1
    }

    val s1: MutableList<String?> = ArrayList(splitVersionString(ver1.lowercase(Locale.ENGLISH)))
    val s2: MutableList<String?> = ArrayList(splitVersionString(ver2.lowercase(Locale.ENGLISH)))

    padWithNulls(s1, s2)

    for (i in s1.indices) {
      val e1 = s1[i]
      val e2 = s2[i]
      val t1 = VersionTokenType.lookup(e1)

      var res = tokenPriorityProvider.getPriority(e1).compareTo(tokenPriorityProvider.getPriority(e2))
      if (res != 0) {
        return res
      }
      if (t1 == VersionTokenType._WORD) {
        res = e1!!.compareTo(e2!!)
      } else if (t1 == VersionTokenType._DIGITS) {
        res = compareNumbers(e1!!, e2!!)
      }

      if (res != 0) {
        return res
      }
    }

    return 0
  }

  private fun compareNumbers(number1: String, number2: String): Int {
    var n1 = number1
    var n2 = number2

    // trim leading zeros
    while (n1.isNotEmpty() && n2.isNotEmpty() && n1[0] == '0' && n2[0] == '0') {
      n1 = n1.substring(1)
      n2 = n2.substring(1)
    }

    // starts with zero => less
    if (n1.isNotEmpty() && n1[0] == '0') {
      return -1
    }
    if (n2.isNotEmpty() && n2[0] == '0') {
      return 1
    }

    // compare as numbers
    if (n1.length > n2.length) {
      return 1
    }
    if (n2.length > n1.length) {
      return -1
    }

    return n1.compareTo(n2)
  }

  private fun padWithNulls(s1: MutableList<String?>, s2: MutableList<String?>) {
    if (s1.size != s2.size) {
      while (s1.size < s2.size) {
        s1.add(null)
      }
      while (s1.size > s2.size) {
        s2.add(null)
      }
    }
  }
}

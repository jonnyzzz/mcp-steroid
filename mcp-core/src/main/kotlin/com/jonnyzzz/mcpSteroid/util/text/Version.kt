/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.text

/**
 * A version string with semantic ordering.
 *
 * Comparison delegates to [VersionComparatorUtil.compare], so Kotlin's comparison
 * operators understand version semantics:
 * `Version("1.10") > Version("1.9")`, `Version("1.0rc1") < Version("1.0")`.
 *
 * Like [java.math.BigDecimal], equality is textual while ordering is semantic:
 * `Version("1.3") != Version("1.3.0")` even though their `compareTo` returns 0.
 * The natural ordering is therefore inconsistent with `equals`: ordering-based
 * collections ([java.util.TreeSet], [sortedMapOf]) treat such versions as the
 * same key, while hash-based collections keep them distinct.
 */
data class Version(val value: String) : Comparable<Version> {
  override fun compareTo(other: Version): Int = VersionComparatorUtil.compare(value, other.value)

  override fun toString(): String = value
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.text

/**
 * A devrig / MCP Steroid build version with the product's update-check ordering.
 *
 * Ordering delegates to [VersionComparatorUtil.compare] with two product rules on top:
 * - a snapshot build (a local dev build, `<base>.19999-SNAPSHOT-<hash>`) is **always
 *   newer** than any non-snapshot version. Update checks gate on `promoted > current`,
 *   so a developer running a snapshot build is never nagged about a promoted release;
 * - build metadata — everything after the first `-` (the git hash, the `gh`/`jb` CI
 *   origin, the `SNAPSHOT` marker) — carries no precedence, like semver's `+build`.
 *   Without this, a release `0.86.0-a1b2c3d` would sort *below* its own promoted base
 *   `0.86.0`: the hex hash's leading `a` tokenizes as the ALPHA keyword.
 *
 * [isSnapshotBuild] is a build-time fact: the generated version metadata
 * (`getBuildVersion()` in `PluginMetadata` / `DevrigVersionMetadata`) bakes it in at
 * build time; [parse] derives it from the `SNAPSHOT` marker for strings that arrive
 * at runtime (e.g. `version-base` from `version.json`, which is never a snapshot).
 *
 * Like [Version], equality is structural while ordering is semantic — ordering-based
 * collections may treat textually different versions as the same key.
 */
data class DevrigVersion(val value: String, val isSnapshotBuild: Boolean) : Comparable<DevrigVersion> {
  /** The precedence-carrying part of [value]: `0.101.441-jb-abcdef1` -> `0.101.441`. */
  val comparableVersion: String get() = value.substringBefore('-')

  override fun compareTo(other: DevrigVersion): Int = when {
    isSnapshotBuild != other.isSnapshotBuild -> if (isSnapshotBuild) 1 else -1
    else -> VersionComparatorUtil.compare(comparableVersion, other.comparableVersion)
  }

  override fun toString(): String = value

  companion object {
    fun parse(value: String): DevrigVersion =
      DevrigVersion(value, isSnapshotBuild = value.contains("SNAPSHOT"))

    /**
     * The update-notification gate shared by devrig and the IDE plugin: notify only
     * when the promoted version is strictly newer than the current build. A snapshot
     * build is newer than anything promoted, so it is never notified.
     */
    fun isUpdateAvailable(current: DevrigVersion, promoted: DevrigVersion): Boolean = promoted > current
  }
}

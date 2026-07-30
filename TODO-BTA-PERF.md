# BTA snippet-compilation performance — measured plan (follow-up to PR #361)

All numbers measured on IU-262.8665.337's classpath (1789 jars / 3.25 GB), BTA 2.4.10,
IN_PROCESS, wrapped exec_code-shaped snippets, adversarially verified probe runs
(raw outputs archived in /tmp/pin-probe-results at the time of measurement).

| Configuration | cold | warm steady | vs status quo | vs old kotlinc subprocess (3–6 s) |
|---|---|---|---|---|
| status quo (PR #361 as merged) | ~2.0 s | 367 ms | 1.0× | 8–16× |
| + application-environment pin | ~2.0 s | **218 ms** | 1.68× | 14–28× |
| + pin + merged STORED fat jar | ~1.7 s | **180 ms** | 2.04× | **17–33×** |

## 1. Application-environment pin (DO THIS — biggest single win, no infra)

BTA 2.4.10 disposes the Kotlin compiler application environment after **every**
compilation (`ourProjectCount` ref-count hits 0 per exec → `disposeApplicationEnvironment()`;
even `kotlin.environment.keepalive` still clears the FastJarFileSystem handler caches
via `idleCleanup()`, KotlinCoreEnvironment.kt:577-579 @ v2.4.10). So every warm compile
re-parses the central directories of all ~1800 jars and rebuilds their VFS trees.

Fix (mirrors what upstream master's BTA already does via `ApplicationEnvironmentPin`):
in `KotlinBuildsSession`, after `loadImplementation`, reflectively — inside the impl
classloader — call `KotlinCoreEnvironment.Companion.getOrCreateApplicationEnvironmentForProduction(disposable, CompilerConfiguration())`
once per session; `Disposer.dispose(disposable)` in `close()`.

Implementation notes (validated by the probe):
- The embeddable compiler **relocates** `com.intellij.*` → `org.jetbrains.kotlin.com.intellij.*`
  (Disposer/Disposable FQNs must use the relocated names).
- `IdeaStandaloneExecutionSetup.doSetup()` must run before acquiring the pin outside a
  compile (otherwise relocated `EarlyAccessRegistryManager` clinit fails on
  `PathManager.getHomePath()`); alternatively acquire the pin lazily after the first compile.
- Statics are **per impl-classloader**: the pin only helps within one `KotlinBuildsSession`.
  One long-lived session per project (CodeEvalManager) is already the design — keep it.
- Hazard: pinned FastJarFS never revalidates jars against disk (no mtime checks) — fine for
  an immutable IDE install; if the classpath set changes (plugin installed/updated), close
  the session (drops the pin) and start fresh.
- Pin acquisition costs ~100 ms once. No significant retained-heap penalty measured
  (~520–610 MB used either way); un-pinned runs actually churn more garbage.
- DAEMON policy note: production compiles via the daemon JVM; the same per-exec cache-clear
  happens there (daemon sets keepalive but `idleCleanup()`/session-release clears jar caches
  — CompileServiceImpl.kt:118,878-881 @ v2.4.10). The pin must live in the JVM that runs the
  compiler: for DAEMON policy this needs upstream (see 3) or switching exec_code to IN_PROCESS.
  Measure IN_PROCESS-in-IDE memory impact before switching.

## 2. Repackaged-classpath cache — optional second stage (~20% on top of the pin)

`~/.mcp-steroid/classpath/<backend>_<hash>/` with ONE merged, entry-stripped
(*.class minus module-info, *.kotlin_module, *.kotlin_builtins, *.kotlin_metadata;
first-wins order, STORED, zip64) jar:
- Measured: 12 s to build, 2.08 GiB on disk, warm 218→180 ms with the pin (280 ms without).
- Kills per-root costs (1789 opens/mmaps/EOCD scans; `FileAccessorCache` holds only ~20 hot
  file handles — a single jar is permanently hot; STORED = memcpy instead of inflate).
- Cache key (measured live on a real IDE): build string + loaded-plugin list + sorted
  (path, size, mtime) → SHA-256; < 10 ms to compute warm. Production classpath measured
  live: 1592 jars / 3.46 GiB (incl. user plugins — AI Assistant alone 370 MB).
- Full design (shards, Building|Ready two-state, Windows-safe swap via new-dir + deferred
  GC, no daemon restarts) drafted in the 2026-07-30 session; revive when stage 1 lands and
  the residual 40 ms matters.
- Correctness for the merge: first-wins dedupe; MERGE colliding `META-INF/*.kotlin_module`
  protobufs (union of PackageParts — do NOT rename); strip `META-INF/versions/**`,
  `module-info.class`, signature files.

## 3. Upstream / no-ops (verified against v2.4.10 sources)

- Kotlin master's BTA pins the environment per BuildSession automatically → upgrading the
  BTA impl to the 2.5 line makes item 1 free. Also relevant for DAEMON-policy pinning.
- `-Xuse-fast-jar-file-system` is already default-on for K2; **no other classpath-perf
  flags exist**; no persisted classpath-index support anywhere in kotlinc (IC classpath
  snapshots are ABI change-detection only — unusable for resolution).
- BTA 2.4.10 DAEMON policy leases + releases a daemon session per operation and clears the
  daemon's jar caches each time — Gradle KGP has the same problem (their source carries a
  TODO about it). Nothing to copy from Gradle/JPS; nobody upstream pre-indexes.

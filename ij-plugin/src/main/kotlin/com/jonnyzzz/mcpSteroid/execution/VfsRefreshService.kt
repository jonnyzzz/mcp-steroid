/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Fire-and-forget VFS refresh after every [steroid_execute_code] MCP call.
 *
 * After a script finishes it may have written to disk (`VfsUtil.saveText`,
 * `vf.setBinaryContent`, external processes running in parallel, etc.).
 * If another tool — native `Edit`, a peer process, a recently-run build —
 * also touched files, IntelliJ's VFS can drift out of sync with the real
 * filesystem. A subsequent semantic query (find-references, rename, hierarchy)
 * then sees stale PSI. This service re-anchors VFS to disk **asynchronously**
 * so the current MCP response is not delayed.
 *
 * ### Why this call
 * [VfsUtil.markDirtyAndRefresh] with `async = true`:
 * - schedules the refresh through [com.intellij.openapi.vfs.newvfs.RefreshQueue],
 *   which coalesces concurrent requests on a single-threaded dispatcher — so
 *   back-to-back `steroid_execute_code` calls do not pile up refresh work;
 * - only processes files whose timestamps actually changed, so the steady-state
 *   cost when nothing was written is effectively free;
 * - does not require a read/write action and is safe from a background coroutine.
 *
 * API reference: `platform/analysis-api/src/com/intellij/openapi/vfs/VfsUtil.java`
 * (markDirtyAndRefresh) and `platform/platform-impl/src/com/intellij/openapi/vfs/newvfs/RefreshQueueImpl.kt`.
 *
 * ### Scoping
 * The service owns its own [CoroutineScope] injected by the platform at project
 * level, so the fire-and-forget launch is automatically cancelled on project
 * dispose. Exceptions are logged at DEBUG and swallowed — a failed refresh must
 * never fail the MCP response.
 */
@Service(Service.Level.PROJECT)
class VfsRefreshService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val log = thisLogger()

    /**
     * Schedules an async VFS refresh on the project's content roots. Returns
     * immediately; the refresh runs on the RefreshQueue thread.
     *
     * Safe to call from any coroutine context. Must not be called while holding
     * a read or write action (the platform forbids nested refresh from inside
     * an action).
     *
     * This is the [tail-of-every-exec_code] path: even if the script threw, we
     * still want the next semantic query to see an up-to-date VFS, which is why
     * [com.jonnyzzz.mcpSteroid.execution.ExecutionManager] calls this from a
     * `finally` block.
     */
    fun scheduleAsyncRefresh() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val base = projectBaseVf() ?: return@launch
                VfsUtil.markDirtyAndRefresh(
                    /* async = */ true,
                    /* recursive = */ true,
                    /* reloadChildren = */ false,
                    base,
                )
            } catch (e: Exception) {
                log.debug("Async VFS refresh failed (non-fatal)", e)
            }
        }
    }

    /**
     * Triggers a VFS refresh and suspends until it finishes. Used before the
     * Kotlin compiler reads the script's source / classpath so kotlinc sees
     * filesystem changes made since the previous `steroid_execute_code`:
     * otherwise a user who edited a file between calls would get stale
     * compilation input.
     *
     * Uses the platform's coroutine-native [RefreshQueue.refresh] (suspend
     * overload), which runs on a background write action and propagates
     * cancellation through the coroutine context. Hard-capped at 30 s so a
     * pathological refresh cannot hang compilation forever.
     */
    suspend fun awaitRefresh() {
        // #318: a recursive project-root scan awaited from the EDT monopolizes it — a 31s
        // PerformanceWatcher freeze attributed to this plugin was captured with the RefreshWorker
        // traversal on AWT-EventQueue-0. Fail fast so no caller can reintroduce that: every await
        // must happen on a background dispatcher (syncDocuments awaits AFTER its EDT commit/save
        // block; CodeEvalManager's pre-compile path is background already). Plain if+throw on
        // purpose — this guard is unconditional, fixed behavior, never elidable.
        if (ApplicationManager.getApplication().isDispatchThread) {
            throw IllegalStateException(
                "awaitRefresh must not be awaited from the EDT — the recursive VFS scan would freeze the UI (#318)",
            )
        }
        val base = projectBaseVf() ?: return
        // Use the platform's coroutine-native [RefreshQueue.refresh] (suspend
        // overload). The callback-based variant uses an EDT-side
        // `finishRunnable` which deadlocks when the calling coroutine is
        // dispatched from `runBlocking` on the EDT (BasePlatformTestCase
        // default) — the EDT is parked, so the runnable never fires and the
        // CompletableDeferred never completes. The suspend overload runs on a
        // background write action and propagates cancellation through the
        // coroutine context, no EDT round-trip.
        val result = try {
            withTimeoutOrNull(30.seconds) {
                RefreshQueue.getInstance().refresh(/* recursive = */ true, listOf(base))
            }
        } catch (e: CancellationException) {
            // Outer-coroutine cancellation must propagate so the request slot
            // frees promptly; it is NOT a non-fatal VFS refresh failure.
            throw e
        } catch (e: Exception) {
            log.debug("awaitRefresh failed (non-fatal)", e)
            return
        }
        if (result == null) {
            log.warn("awaitRefresh timed out after 30 s; compilation will proceed against possibly stale VFS")
        }
    }

    private fun projectBaseVf(): VirtualFile? {
        val basePath = project.basePath
        if (basePath == null) {
            log.debug("No project basePath; skipping VFS refresh")
            return null
        }
        val base = LocalFileSystem.getInstance().findFileByPath(basePath)
        if (base == null) {
            log.debug("Project basePath '$basePath' has no VirtualFile; skipping")
        }
        return base
    }
}

inline val Project.vfsRefreshService: VfsRefreshService get() = service()

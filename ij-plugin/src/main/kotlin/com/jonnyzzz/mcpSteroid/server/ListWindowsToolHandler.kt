/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressModel
import com.intellij.openapi.progress.TaskInfo
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.jonnyzzz.mcpSteroid.execution.dialogWindowsLookup
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

class ListWindowsToolHandlerIJ : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse {
        val snapshot = service<IdeWindowsCollector>().collect()
        val self = describeSelfBackend()
        return ListWindowsResponse(
            windows = snapshot.windows.map { it.listed(it.projectName, self.backendName) },
            backgroundTasks = snapshot.backgroundTasks.map { it.listed(it.projectName, self.backendName) },
            backends = listOf(self.backend),
        )
    }
}

/**
 * Wire-shaped snapshot of this IDE's windows and background tasks — exactly the lists carried inside
 * the pristine [NpxBridgeWindowsResponse]. Shared seam between the `/windows` bridge
 * ([NpxBridgeService.buildWindows], wire) and the MCP handler ([ListWindowsToolHandlerIJ], which wraps
 * the same lists into [ListedWindow]/[ListedBackgroundTask]).
 */
data class IdeWindowsSnapshot(
    val windows: List<WindowInfo>,
    val backgroundTasks: List<ProgressTaskInfo>,
)

/** Collects the [IdeWindowsSnapshot] from the running IDE (EDT enumeration, modality-aware). */
@Service(Service.Level.APP)
class IdeWindowsCollector {
    private val log = logger<IdeWindowsCollector>()

    suspend fun collect(): IdeWindowsSnapshot {
        // Use DialogWindowsLookup for reliable modal detection:
        // fast negative path (canPumpEdtNonModal), then EDT check if needed.
        val lookup = dialogWindowsLookup()
        val (windowInfos, progressTasks) = lookup.withModalityCheck { isModalShowing ->
            // Window enumeration runs on EDT with ModalityState.any() so it works
            // even when a modal dialog is blocking the normal EDT dispatcher.
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                val frames = WindowManager.getInstance().allProjectFrames.toList()

                // Collect progress indicators from all frames
                val allProgressTasks = mutableListOf<ProgressTaskInfo>()

                val frameInfos = frames.map { frame ->
                    val project = frame.project
                    val component = frame.component
                    val window = SwingUtilities.getWindowAncestor(component)
                    val bounds = window?.bounds

                    val statusBar = frame.statusBar as? StatusBarEx
                    statusBar?.let { bar ->
                        val tasks = try {
                            val listOfAny: List<Any> = bar.backgroundProcessModels

                            listOfAny.mapNotNull {
                                // Collect progress tasks from the status bar.
                                // Wrapped in try/catch because IntelliJ 262+ changed the return type of
                                // StatusBarEx.backgroundProcessModels from List<c.i.o.u.Pair> to
                                // List<kotlin.Pair>, causing ClassCastException when the plugin is built
                                // against 253. See mcp-steroid#18.
                                runCatching {
                                    val inner = it as com.intellij.openapi.util.Pair<*, *>
                                    return@mapNotNull inner.first to inner.second
                                }
                                runCatching {
                                    return@mapNotNull it as Pair<*, *>
                                }
                                null
                            }.mapNotNull { (a, b) ->
                                (a as? TaskInfo ?: return@mapNotNull null) to (b as? ProgressModel
                                    ?: return@mapNotNull null)
                            }
                        } catch (e: Throwable) {
                            if (e is ControlFlowException) throw e
                            log.warn("Failed to get list windows. Skipping. ${e.message}", e)
                            listOf()
                        }

                        tasks.forEach { pair ->
                            val taskInfo = pair.first
                            val progressModel = pair.second
                            allProgressTasks.add(
                                ProgressTaskInfo(
                                    title = taskInfo.title,
                                    text = progressModel.getText() ?: "",
                                    text2 = progressModel.getDetails() ?: "",
                                    fraction = if (progressModel.isIndeterminate()) null else progressModel.getFraction(),
                                    isIndeterminate = progressModel.isIndeterminate(),
                                    isCancellable = progressModel.isCancellable(),
                                    projectName = project?.name
                                )
                            )
                        }
                    }

                    WindowInfo(
                        projectName = project?.name,
                        projectPath = project?.basePath,
                        title = (window as? Frame)?.title,
                        isActive = window?.isActive ?: false,
                        isVisible = window?.isVisible ?: false,
                        bounds = bounds?.let { WindowBounds(it.x, it.y, it.width, it.height) },
                        windowId = WindowIdUtil.compute(window, component),
                        modalDialogShowing = isModalShowing,
                        indexingInProgress = project?.let { DumbService.isDumb(it) },
                        projectInitialized = project?.isInitialized,
                    )
                }

                val knownWindowIds = frameInfos.map { it.windowId }.toMutableSet()
                val extraInfos = java.awt.Window.getWindows()
                    .filter { it.isDisplayable }
                    .mapNotNull { window ->
                        val windowId = WindowIdUtil.compute(window, window)
                        if (!knownWindowIds.add(windowId)) return@mapNotNull null
                        val bounds = window.bounds
                        WindowInfo(
                            projectName = null,
                            projectPath = null,
                            title = (window as? Frame)?.title,
                            isActive = window.isActive,
                            isVisible = window.isVisible,
                            bounds = WindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                            windowId = windowId,
                            modalDialogShowing = isModalShowing,
                        )
                    }

                (frameInfos + extraInfos) to allProgressTasks.toList()
            }
        }

        return IdeWindowsSnapshot(
            windows = windowInfos,
            backgroundTasks = progressTasks,
        )
    }
}

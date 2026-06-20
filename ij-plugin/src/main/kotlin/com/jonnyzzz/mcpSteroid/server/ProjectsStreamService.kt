/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.IdeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level service that publishes the IDE's currently-open project
 * list as a [StateFlow]. Powers the devrig bridge's `…/projects/stream`.
 *
 * Wiring:
 *  - Open: [SteroidsMcpServerStartupActivity] (a `postStartupActivity`)
 *    calls [refresh] for every project that opens — that's the canonical
 *    non-deprecated hook for the "project opened" signal.
 *  - Close: this service subscribes to [ProjectCloseListener.TOPIC] in its
 *    init block and refreshes from there.
 *
 * Subscribers (i.e. the `/projects/stream` route handler) consume [projects]
 * and react via `Flow.collectLatest { ... }`. Because [MutableStateFlow]
 * deduplicates equal emissions, the route only writes a fresh snapshot
 * envelope when the open-project set actually changes.
 */
@Service(Service.Level.APP)
class ProjectsStreamService : Disposable {
    private val log = thisLogger()

    val ideInstanceId: String = "ide-${UUID.randomUUID()}"
    val idePid: Long = ProcessHandle.current().pid()
    private val seqCounter = AtomicLong(0)

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    init {
        refresh()  // seed the flow before any client subscribes

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosed(project: Project) {
                refresh()
            }
        })
    }

    /**
     * Recompute the snapshot from [ProjectManager.openProjects] and publish
     * it on the flow. Idempotent — no emission if the value is unchanged.
     * Called externally from [SteroidsMcpServerStartupActivity] on project
     * open and internally from the close listener.
     */
    fun refresh() {
        // Additive, informational (#92): the IDE stamps its own within-IDE-unique key + self backend id
        // onto the wire so the protocol is symmetric with the MCP surface. devrig still recomputes these
        // and does not depend on the wire values.
        val selfBackendName = backendNameForMarker(pid = idePid, build = IdeInfo.ofApplication().build)
        val snapshot = ApplicationManager.getApplication().runReadAction<List<ProjectInfo>> {
            ProjectManager.getInstance().openProjects.map { project ->
                ProjectInfo(
                    name = project.name,
                    path = project.basePath ?: "",
                    projectName = project.service<ProjectNameService>().projectName,
                    backendName = selfBackendName,
                )
            }
        }
        _projects.value = snapshot
    }

    fun nextSeq(): Long = seqCounter.incrementAndGet()

    fun clientConnected(info: NpxStreamClientInfo) {
        log.info(
            "devrig monitor connected — client=${info.client} " +
                    "clientPid=${info.clientPid} clientVersion=${info.clientVersion} " +
                    "clientInstanceId=${info.clientInstanceId} " +
                    "platform=${info.platform} arch=${info.arch} " +
                    "(ideInstanceId=$ideInstanceId pid=$idePid)"
        )
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): ProjectsStreamService = service()
    }
}

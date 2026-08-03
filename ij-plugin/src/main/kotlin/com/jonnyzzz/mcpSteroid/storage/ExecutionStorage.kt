/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import kotlinx.coroutines.CoroutineScope

inline val Project.executionStorage: ExecutionStorage get() = service<IjExecutionStorage>()

/**
 * IntelliJ-side wrapper for the generic [ExecutionStorage] file storage.
 *
 * The base class (in the `:execution-storage` module) is IDE-free; this
 * service supplies the callbacks it needs — the global storage
 * dir resolved via [StoragePaths], the [Project] identity captured at write
 * time, and this IDE backend's identity.
 *
 * `coroutineScope` is unused today but is kept in the constructor to opt
 * into IntelliJ's service-scoped coroutine lifecycle, matching the previous
 * shape of this service — preserves the contract for tests/tooling that
 * inspect service constructors.
 */
@Service(Service.Level.PROJECT)
class IjExecutionStorage(
    project: Project,
    @Suppress("unused", "UNUSED_PARAMETER") coroutineScope: CoroutineScope,
) : ExecutionStorage(
    baseDirProvider = { project.storagePaths.getGetMcpRunDir() },
    projectInfoProvider = { ExecutionProjectInfo(name = project.name, basePath = project.basePath) },
    backendInfoProvider = {
        ExecutionBackendInfo(
            kind = 's',
            name = backendNameForMarker(
                ProcessHandle.current().pid(),
                ApplicationInfo.getInstance().build.asString(),
            ),
        )
    },
)

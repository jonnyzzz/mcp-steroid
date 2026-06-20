/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level source of the within-IDE-unique `project_name` (issue #92). Two projects with the same
 * IntelliJ name but different paths — e.g. a main checkout and a git worktree — must stay individually
 * addressable, so the exposed name is `<Project.name>-<hash(basePath, pid)>` via the shared
 * [uniqueProjectName] (the same scheme devrig uses, so the two derive the same string).
 *
 * **Recomputed on every access — NEVER cached.** A project's model can change (rename, moved base path),
 * and that must be reflected immediately. Every caller that lists or resolves a `project_name` goes
 * through this single service: `project.service<ProjectNameService>().projectName`.
 */
@Service(Service.Level.PROJECT)
class ProjectNameService(private val project: Project) {
    val projectName: String
        get() = uniqueProjectName(project.name, project.basePath ?: "", ProcessHandle.current().pid())
}

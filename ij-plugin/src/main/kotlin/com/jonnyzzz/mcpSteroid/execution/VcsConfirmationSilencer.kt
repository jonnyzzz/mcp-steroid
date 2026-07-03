/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vcs.VcsShowConfirmationOption

/**
 * Permanently silence the IntelliJ "Add files to Git/VCS?" and "Remove files
 * from VCS?" confirmation dialogs for every project that opens with MCP Steroid
 * installed.
 *
 * ### Why
 *
 * MCP Steroid agents create and delete files via [steroid_execute_code]
 * (VfsUtil.saveText / createChildData and friends). The platform's default for
 * `VcsConfiguration.StandardConfirmation.ADD` is
 * [VcsShowConfirmationOption.Value.SHOW_CONFIRMATION], which surfaces a modal
 * dialog on the EDT every time a new unversioned file appears in a Git-tracked
 * project. The dialog blocks every subsequent agent step until the user clicks
 * a button — making the IDE effectively unresponsive to the agent.
 *
 * The platform decision point is `VcsConfirmationUtil.requestConfirmation` —
 * if `option.value == DO_NOTHING_SILENTLY` it returns `false` without showing
 * the dialog. There is no scoped (per-call) override exposed by the platform;
 * the confirmation value is read on every VFS event, including events that
 * fire asynchronously after a tool call returns. That means a per-call
 * save/restore would race the VFS notification queue.
 *
 * Therefore this silencer flips the value once per project at open time and
 * leaves it set. The change is logged at WARN level so users can see exactly
 * what MCP Steroid did to their VCS settings, and is a no-op when the user
 * has already chosen a non-default value (`DO_ACTION_SILENTLY` or
 * `DO_NOTHING_SILENTLY`) — in that case we honour their preference.
 *
 * ### Side-effect surface
 *
 * After this runs, IntelliJ will not pop the "Add to Git?" / "Remove from
 * Git?" confirmation when files appear or disappear under VCS control. Files
 * remain visible in the "Local Changes → Unversioned Files" tool window and
 * can still be added via the commit dialog, the context menu, or
 * `git add` — only the proactive modal prompt is gone. No `git` operation is
 * performed automatically; the change is purely about prompting.
 *
 * To restore the prompt, open Settings → Version Control → Confirmation and
 * pick "Show options before adding files to the VCS".
 *
 * ### See also
 *
 * - `~/Work/intellij/community/platform/vcs-api/src/com/intellij/util/ui/VcsConfirmationUtil.kt:29` — the decision point that consults `option.value`.
 * - `~/Work/intellij/community/platform/vcs-api/src/com/intellij/openapi/vcs/ProjectLevelVcsManager.kt:237` — `runAfterInitialization` ensures we run after VCS detection completes.
 */
class VcsConfirmationSilencer : ProjectActivity {
    private val log = Logger.getInstance(VcsConfirmationSilencer::class.java)

    override suspend fun execute(project: Project) {
        val pm = ProjectLevelVcsManager.getInstance(project)
        // First pass once core VCS services are up — covers the common case (project already mapped).
        pm.runAfterInitialization {
            silenceFor(project, pm)
        }
        // `runAfterInitialization` is single-shot, and VCS root detection can finish AFTER it (a freshly
        // created `.git`, or async mapping detection at startup): that early pass then sees no active VCS,
        // no-ops, and the confirmation is left at the platform default — which can be SHOW_CONFIRMATION, so
        // the "Add to VCS?" modal can still pop. This matters here because MCP Steroid agents create files
        // programmatically right after opening a project, inside that first-detection window. Re-apply on
        // every directory-mapping change so the silence lands whenever a VCS becomes active. `silenceFor`
        // is idempotent (only flips SHOW_CONFIRMATION; honours any user-set value).
        // Uses the public, stable `ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED` topic — NOT an
        // `@ApiStatus.Internal` API.
        project.messageBus.connect().subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsMappingListener { silenceFor(project, pm) },
        )
    }

    private fun silenceFor(project: Project, pm: ProjectLevelVcsManager) {
        val activeVcss = pm.getAllActiveVcss()
        if (activeVcss.isEmpty()) {
            log.debug("[mcp-vcs-silencer] No active VCS for project '${project.name}' — no confirmation to silence")
            return
        }

        for (vcs in activeVcss) {
            silenceConfirmation(project, pm, VcsConfiguration.StandardConfirmation.ADD, vcs)
            silenceConfirmation(project, pm, VcsConfiguration.StandardConfirmation.REMOVE, vcs)
        }
    }

    private fun silenceConfirmation(
        project: Project,
        pm: ProjectLevelVcsManager,
        kind: VcsConfiguration.StandardConfirmation,
        vcs: AbstractVcs,
    ) {
        val option = pm.getStandardConfirmation(kind, vcs)
        val current = option.value
        if (current != VcsShowConfirmationOption.Value.SHOW_CONFIRMATION) {
            // Only flip the platform default. If the user explicitly set
            // DO_ACTION_SILENTLY (auto-add) or DO_NOTHING_SILENTLY (already
            // silent), leave their preference alone.
            log.debug(
                "[mcp-vcs-silencer] Project '${project.name}' / vcs '${vcs.name}' / kind '$kind' " +
                        "is already $current — leaving user preference intact"
            )
            return
        }

        log.warn(
            "[mcp-vcs-silencer] MCP Steroid is silencing the '$kind' confirmation dialog for project " +
                    "'${project.name}' / vcs '${vcs.name}'. " +
                    "$current → ${VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY}. " +
                    "Files will no longer trigger an 'Add to ${vcs.name}?' modal when created/removed. " +
                    "Restore via Settings → Version Control → Confirmation if you need the prompt back."
        )
        option.value = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY
    }
}

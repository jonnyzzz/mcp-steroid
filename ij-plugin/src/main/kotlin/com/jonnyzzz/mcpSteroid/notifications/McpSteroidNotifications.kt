/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin's ONE notification group, shared by everything the plugin says: the plugin-update
 * notification, the devrig promotion, and the install results and failures. Plain `BALLOON`
 * on purpose: every message here may auto-hide — a balloon is a nudge, and everything it said stays
 * reachable in the Notifications tool window. Must match `plugin.xml`.
 */
const val MCP_STEROID_NOTIFICATION_GROUP = "jonnyzzz.mcp.steroid.updates"

/**
 * Every message the plugin can show, one entry per kind. [McpSteroidNotifications] keeps at most one
 * live notification per kind — a newer message of the same kind replaces the older one instead of
 * stacking a second balloon next to it.
 */
enum class McpSteroidNotificationKind {
    /** The devrig install flow's outcome: installed, already being installed elsewhere, or failed. */
    DEVRIG_INSTALL,

    /** The once-per-run "Install devrig to connect an AI agent" promotion. */
    DEVRIG_INSTALL_OFFER,

    /** "A new version of MCP Steroid is available" from the periodic update check. */
    PLUGIN_UPDATE,
}

/**
 * The single owner of the plugin's notifications API use: every balloon the plugin shows goes through
 * [notify], nothing else touches [NotificationGroupManager]. Owning the one call site is what makes the
 * two policies below hold everywhere at once, instead of being re-implemented (and drifting) per caller:
 *
 * - **One group.** Everything is posted to [MCP_STEROID_NOTIFICATION_GROUP], so the user has exactly one
 *   switch to configure or mute the plugin.
 * - **At most one live notification per [kind][McpSteroidNotificationKind].** Showing a message expires
 *   the still-pending previous message of the same kind first: the newest text is the true one (a retry's
 *   failure supersedes the original failure), and repeated events must not pile up balloons.
 *
 * Tracking is by [pending]: an entry lives from [notify] until its notification expires — by the user
 * closing it, an expiring action running, or a same-kind successor replacing it. `Notification.expire`
 * is idempotent and thread-safe, so this whole service is callable from any thread, which the callers
 * need: they report from background progress tasks and coroutines, never from the EDT.
 */
@Service(Service.Level.APP)
class McpSteroidNotifications {
    private val pending = ConcurrentHashMap<McpSteroidNotificationKind, Notification>()

    /**
     * Show [title]/[content] of [type] as [kind], first expiring the pending notification of the same
     * kind, if any. [project] only anchors the balloon and may be null for application-level surfaces.
     * [actions] keep their own semantics (the caller builds them, typically
     * `NotificationAction.createSimpleExpiring`); this service owns lifecycle, not behavior.
     */
    fun notify(
        kind: McpSteroidNotificationKind,
        project: Project?,
        type: NotificationType,
        title: String,
        content: String,
        vararg actions: AnAction,
    ): Notification {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(MCP_STEROID_NOTIFICATION_GROUP)
            .createNotification(title, content, type)
        for (action in actions) notification.addAction(action)
        // However this notification ends — user close, an expiring action, or a same-kind successor —
        // drop it from the tracking map, so the map only ever holds live notifications. The two-arg
        // remove is deliberate: when a successor already replaced the entry, this must not remove IT.
        notification.whenExpired { pending.remove(kind, notification) }
        pending.put(kind, notification)?.expire()
        notification.notify(project)
        return notification
    }

    companion object {
        fun getInstance(): McpSteroidNotifications = service()
    }
}

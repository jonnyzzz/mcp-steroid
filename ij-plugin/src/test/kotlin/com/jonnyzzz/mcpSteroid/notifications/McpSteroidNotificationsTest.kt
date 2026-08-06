/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class McpSteroidNotificationsTest : BasePlatformTestCase() {
    private val shown = mutableListOf<Notification>()

    override fun tearDown() {
        try {
            // The service is application-level and its tracking outlives a test; expiring every shown
            // notification both cleans the screen state and empties the map (via whenExpired).
            shown.forEach { it.expire() }
            shown.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun show(kind: McpSteroidNotificationKind, title: String = "test title"): Notification =
        McpSteroidNotifications.getInstance()
            .notify(kind, project, NotificationType.INFORMATION, title, "test content")
            .also { shown += it }

    fun `test the single notification group is registered and auto-hides`() {
        // ONE group for everything the plugin says: plugin updates, the devrig promotion, and
        // install/register results. Plain BALLOON — the promotion balloon may disappear (owner call);
        // anything missed stays in the Notifications tool window.
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(MCP_STEROID_NOTIFICATION_GROUP)
        assertNotNull("The plugin's notification group must be registered in plugin.xml", group)
        assertEquals(
            "The plugin's balloons must NOT be sticky",
            NotificationDisplayType.BALLOON,
            group.displayType,
        )
    }

    fun `test the retired onboarding groups are gone`() {
        // The sticky onboarding group and the separate results group were merged into the one above.
        // A leftover registration would resurrect the split (and the sticky balloon) silently.
        val manager = NotificationGroupManager.getInstance()
        assertFalse(
            "the sticky onboarding group must not be registered any more",
            manager.isGroupRegistered("jonnyzzz.mcp.steroid.onboarding"),
        )
        assertFalse(
            "the separate results group must not be registered any more",
            manager.isGroupRegistered("jonnyzzz.mcp.steroid.onboarding.results"),
        )
    }

    fun `test a second notification of the same kind expires the first`() {
        val first = show(McpSteroidNotificationKind.DEVRIG_INSTALL, "first")
        val second = show(McpSteroidNotificationKind.DEVRIG_INSTALL, "second")
        assertTrue("the superseded notification must be expired", first.isExpired)
        assertFalse("the newest notification must stay live", second.isExpired)
        assertSame(
            "the newest notification is the tracked one",
            second,
            McpSteroidNotifications.getInstance().pendingNotification(McpSteroidNotificationKind.DEVRIG_INSTALL),
        )
    }

    fun `test different kinds never expire each other`() {
        val install = show(McpSteroidNotificationKind.DEVRIG_INSTALL)
        val update = show(McpSteroidNotificationKind.PLUGIN_UPDATE)
        assertFalse("a different kind must not supersede this one", install.isExpired)
        assertFalse(update.isExpired)
    }

    fun `test an expired notification is dropped from tracking`() {
        val offer = show(McpSteroidNotificationKind.DEVRIG_INSTALL_OFFER)
        // However it expires — user close, an expiring action, a successor — tracking must let go of it.
        offer.expire()
        assertNull(
            "an expired notification must not be reported as pending",
            McpSteroidNotifications.getInstance().pendingNotification(McpSteroidNotificationKind.DEVRIG_INSTALL_OFFER),
        )
    }

    fun `test expiring the superseded notification does not untrack its successor`() {
        // The whenExpired cleanup uses the two-arg remove on purpose: when a successor already replaced
        // the map entry, the OLD notification's expiry must not remove the NEW one.
        val first = show(McpSteroidNotificationKind.AGENT_REGISTRATION, "first")
        val second = show(McpSteroidNotificationKind.AGENT_REGISTRATION, "second")
        first.expire() // idempotent: notify() already expired it
        assertSame(
            "the successor must stay tracked after the superseded one expires",
            second,
            McpSteroidNotifications.getInstance().pendingNotification(McpSteroidNotificationKind.AGENT_REGISTRATION),
        )
    }
}

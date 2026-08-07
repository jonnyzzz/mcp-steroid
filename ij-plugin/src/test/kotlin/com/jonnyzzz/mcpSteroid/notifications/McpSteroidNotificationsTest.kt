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
        // install results. Plain BALLOON — the promotion balloon may disappear (owner call);
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

    // The service exposes nothing beyond notify() on purpose (no test-only accessors); every contract
    // below is observed through the returned Notification objects' own expiry state.

    fun `test a second notification of the same kind expires the first`() {
        val first = show(McpSteroidNotificationKind.DEVRIG_INSTALL, "first")
        val second = show(McpSteroidNotificationKind.DEVRIG_INSTALL, "second")
        assertTrue("the superseded notification must be expired", first.isExpired)
        assertFalse("the newest notification must stay live", second.isExpired)
    }

    fun `test different kinds never expire each other`() {
        val install = show(McpSteroidNotificationKind.DEVRIG_INSTALL)
        val update = show(McpSteroidNotificationKind.PLUGIN_UPDATE)
        assertFalse("a different kind must not supersede this one", install.isExpired)
        assertFalse(update.isExpired)
    }

    fun `test a fresh notification after a user-expired one shows live`() {
        // The tracking map exists for double-balloon prevention, nothing else; after the user closes a
        // notification, the visible contract is simply that the next notify() of the kind is a fresh
        // live balloon and the closed one stays closed.
        val offer = show(McpSteroidNotificationKind.DEVRIG_INSTALL_OFFER, "first")
        offer.expire()
        val next = show(McpSteroidNotificationKind.DEVRIG_INSTALL_OFFER, "second")
        assertFalse("a fresh notification after expiry must be live", next.isExpired)
        assertTrue("the user-closed notification must stay expired", offer.isExpired)
    }

    fun `test expiring the superseded notification does not untrack its successor`() {
        // The whenExpired cleanup uses the two-arg remove on purpose: when a successor already replaced
        // the map entry, the OLD notification's expiry must not remove the NEW one. Observable through
        // notify() alone: were the successor untracked (one-arg remove bug), a THIRD notification could
        // not expire it — leaving two live balloons of the same kind.
        val first = show(McpSteroidNotificationKind.PLUGIN_UPDATE, "first")
        val second = show(McpSteroidNotificationKind.PLUGIN_UPDATE, "second")
        first.expire() // idempotent: notify() already expired it
        val third = show(McpSteroidNotificationKind.PLUGIN_UPDATE, "third")
        assertTrue("the successor must have stayed tracked, so the third supersedes it", second.isExpired)
        assertFalse("the newest notification must stay live", third.isExpired)
    }
}

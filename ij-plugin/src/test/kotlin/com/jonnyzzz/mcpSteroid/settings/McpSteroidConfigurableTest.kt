/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.fields.ExtendableTextField
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.devrigMcpCommandLine
import com.jonnyzzz.mcpSteroid.onboarding.devrigStdioMcpConfigJson
import com.jonnyzzz.mcpSteroid.onboarding.probeDevrigInstallState
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import java.nio.file.Path
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import javax.swing.JButton
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.text.AbstractDocument
import javax.swing.text.JTextComponent

class McpSteroidConfigurableTest : BasePlatformTestCase() {

    fun `test applicationConfigurable EP is registered and instantiable`() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList
            .singleOrNull { it.id == McpSteroidConfigurable.CONFIGURABLE_ID }
            ?: error("No <applicationConfigurable> with id=${McpSteroidConfigurable.CONFIGURABLE_ID} is registered in plugin.xml")

        assertEquals("tools", ep.parentId)
        assertEquals(McpSteroidConfigurable.DISPLAY_NAME, ep.displayName)
        assertTrue(
            "Settings tab name must lead with the Devrig product name; got '${McpSteroidConfigurable.DISPLAY_NAME}'",
            McpSteroidConfigurable.DISPLAY_NAME.startsWith("Devrig"),
        )

        val configurable = ep.createConfigurable()
        assertNotNull("ConfigurableEP must instantiate the settings page", configurable)
        assertTrue(
            "Expected McpSteroidConfigurable, got ${configurable!!.javaClass.name}",
            configurable is McpSteroidConfigurable,
        )
        assertEquals(McpSteroidConfigurable.DISPLAY_NAME, configurable.displayName)
        configurable.disposeUIResources()
    }

    fun `test panel promotes devrig, renders statuses as value fields, and deprecates direct HTTP`() {
        val configurable = McpSteroidConfigurable()
        try {
            val component = configurable.createComponent()

            // The devrig block populates on show, never at build time: creating the panel must leave
            // only the "Checking…" placeholder — the EDT does no file I/O while building Swing.
            assertTrue(
                "before the on-show populate the devrig block must say Checking…; found:\n" +
                    collectTexts(component).joinToString("\n"),
                collectTexts(component).any { it.contains("Checking…") },
            )

            // A test panel is never physically showing, so the launchOnShow block never runs here.
            // Drive the same populate path it takes, with the same probe it uses.
            configurable.applyInstallState(probeDevrigInstallState())

            val texts = collectTexts(component)
            val joined = texts.joinToString("\n")

            // No tabs: everything is on one page, with the deprecated path collapsed at the bottom.
            assertTrue(
                "The page must not use a tabbed pane; found ${findAllTabbedPanes(component).size}",
                findAllTabbedPanes(component).isEmpty(),
            )

            // No pitch: this page is for state and actions, and its reader already installed the plugin.
            assertFalse(
                "The settings page must not open with marketing copy; found:\n$joined",
                joined.contains("not just over your files"),
            )

            // The feedback link points at GitHub issues only, and lives at the bottom — it is never the
            // next step for someone who just opened the page. There is no Slack workspace; the old label
            // falsely promised one.
            assertContainsText(texts, "Report an issue on GitHub")
            assertFalse("No Slack workspace exists — the label must not mention Slack", joined.contains("Slack"))
            assertTrue(
                "The feedback link must come after the devrig content; order was:\n$joined",
                texts.indexOfFirst { it.contains("Report an issue on GitHub") } >
                    texts.indexOfFirst { it.contains("What is devrig?") },
            )

            // The by-hand installer one-liners are gone: the button is the way to install devrig from the
            // IDE, and a copyable `curl … | sh` next to it only invited a second, unmanaged install.
            assertFalse(
                "The page must not print installer one-liners any more; found:\n$joined",
                joined.contains("install.sh") || joined.contains("install.ps1") || joined.contains("| iex"),
            )

            // Exactly one of the two state-dependent blocks, never both: the install button has nothing
            // to offer someone who already has devrig, and the registration commands mean nothing to
            // someone who does not. Asserted against the real state so this holds on any machine.
            val installed = probeDevrigInstallState().installed
            if (installed) {
                // One row per agent devrig can register — never just Claude.
                for (agent in AiAgentCli.entries) {
                    assertContainsText(texts, agent.displayName)
                }

                // devrig registers three agents by CLI; everything else — Cursor, Windsurf, any
                // mcpServers file — gets the same server as a copyable stdio snippet, so an unsupported
                // client is a paste away rather than a dead end. Only once devrig is there: the snippet
                // names a launcher path that has to exist to be worth copying.
                assertContainsText(texts, McpSteroidConfigurable.OTHER_CLIENTS_SECTION_TITLE)
                assertContainsText(texts, "\"mcpServers\"")
                val expected = devrigStdioMcpConfigJson(
                    Path.of(System.getProperty("user.home")),
                    SystemInfo.isWindows,
                )
                assertTrue(
                    "The snippet must be the one devrig itself would register; expected:\n$expected",
                    texts.any { it.contains(expected) },
                )

                // The bare command line — the real absolute launcher plus the `mcp` subcommand, for
                // clients that ask for a command instead of a file — is its own row directly UNDER the
                // agent rows, not buried inside the collapsed JSON section below the snippet (owner
                // click-testing feedback). Rendered in a copyable field, so display and clipboard are
                // the same string.
                assertContainsText(texts, "Register devrig as an MCP server in")
                val leadInIndex = texts.indexOfFirst { it.contains("Register devrig as an MCP server in") }
                val lastAgentIndex = texts.indexOfFirst {
                    it.contains("${AiAgentCli.entries.last().displayName}:")
                }
                val otherClientsIndex = texts.indexOfFirst {
                    it.contains(McpSteroidConfigurable.OTHER_CLIENTS_SECTION_TITLE)
                }
                assertTrue(
                    "the command-line row must sit under the agent rows (last agent label at index " +
                        "$lastAgentIndex) and before the collapsed " +
                        "'${McpSteroidConfigurable.OTHER_CLIENTS_SECTION_TITLE}' section (at index " +
                        "$otherClientsIndex); its lead-in was at index $leadInIndex in:\n$joined",
                    leadInIndex in (lastAgentIndex + 1) until otherClientsIndex,
                )
                val commandLine = devrigMcpCommandLine(System.getProperty("user.home"), SystemInfo.isWindows)
                val fields = collectValueFields(component).map { it.text }
                assertTrue(
                    "The stdio command line must be a copyable value field with the absolute launcher " +
                        "path; expected '$commandLine' among: $fields",
                    fields.any { it == commandLine },
                )

                assertFalse(
                    "devrig is installed — the panel must not offer to install it again; found:\n$joined",
                    joined.contains("devrig is not installed"),
                )
            } else {
                assertContainsText(texts, "devrig is not installed")
                assertContainsText(texts, "Install devrig")
                assertFalse(
                    "Without devrig the stdio snippet would name a launcher that does not exist; found:\n$joined",
                    joined.contains(McpSteroidConfigurable.OTHER_CLIENTS_SECTION_TITLE),
                )
                assertFalse(
                    "Without devrig the command line would name a launcher that does not exist; found:\n$joined",
                    joined.contains("Register devrig as an MCP server in"),
                )
                assertFalse(
                    "devrig is missing — registering an agent is not yet the next step; found:\n$joined",
                    joined.contains("Point an agent at it"),
                )
            }

            // The panel still links to the devrig site; the exact URL shape is pinned in its own test.
            assertContainsText(texts, "What is devrig?")

            // The server's state is rendered as a read-only value field, and it lives INSIDE the Direct
            // HTTP section: this single IDE's port only matters to someone wiring HTTP by hand, so the
            // row sits with the URL and the commands it belongs to (manual click-testing feedback).
            assertContainsText(texts, "MCP server:")
            assertTrue(
                "The MCP server row must live under the Direct HTTP section title; order was:\n$joined",
                texts.indexOfFirst { it.contains("MCP server:") } >
                    texts.indexOfFirst { it.contains(McpSteroidConfigurable.HTTP_SECTION_TITLE) },
            )
            val valueFields = collectValueFields(component)
            val fieldTexts = valueFields.map { it.text }
            assertTrue(
                "The server status must be a value field, not a bare label; fields: $fieldTexts",
                fieldTexts.any { it.startsWith("Running on port") || it == "Not running" },
            )

            // Every value field is read-only the platform way: isEditable=false, no keystroke-swallowing
            // DocumentFilter. An earlier revision kept isEditable=true and filtered edits out — the field
            // showed a caret, took focus, and ignored typing, which read as frozen.
            assertTrue("the page must render at least one value field", valueFields.isNotEmpty())
            for (field in valueFields) {
                assertFalse(
                    "value field '${field.text}' must set isEditable=false, not fake read-only behind an editable look",
                    field.isEditable,
                )
                assertNull(
                    "value field '${field.text}' must not carry a DocumentFilter — isEditable=false is the read-only mechanism",
                    (field.document as? AbstractDocument)?.documentFilter,
                )
            }

            // Display policy: every devrig path on this page names the real absolute home — never `~`,
            // which Windows would not expand and which would make the display differ from what the copy
            // buttons put on the clipboard.
            assertFalse(
                "No user-visible devrig path may render '~'; found:\n$joined",
                joined.contains("~/.mcp-steroid") || joined.contains("~\\.mcp-steroid"),
            )
            if (installed) {
                assertTrue(
                    "devrig's status must be a value field too; fields: $fieldTexts",
                    fieldTexts.any { it.startsWith("Installed") },
                )

                // Owner click-testing feedback: the installed-state field must not stretch across the
                // whole page. It and the agent status fields ("Checking…", "Registered") share one fixed
                // column width, so the devrig block reads as one column of answers. The agent fields are
                // asserted opportunistically — a background check may already have swapped one for a
                // button state — but the Installed field is always there in this branch.
                val statusFields = collectValueFields(component).filter {
                    it.text.startsWith("Installed") || it.text == "Registered" || it.text == "Checking…"
                }
                assertTrue(
                    "expected at least the Installed field among: $fieldTexts",
                    statusFields.isNotEmpty(),
                )
                for (field in statusFields) {
                    assertEquals(
                        "status field '${field.text}' must be ${McpSteroidConfigurable.STATUS_FIELD_COLUMNS} " +
                            "columns wide, matching the value areas around it — not page-wide",
                        McpSteroidConfigurable.STATUS_FIELD_COLUMNS,
                        field.columns,
                    )
                }
            }

            // The direct-HTTP path is collapsed at the bottom and says outright that it is deprecated —
            // in the section title and in its first line.
            assertTrue(
                "The HTTP section title must mark the path deprecated; got '${McpSteroidConfigurable.HTTP_SECTION_TITLE}'",
                McpSteroidConfigurable.HTTP_SECTION_TITLE.contains("deprecated"),
            )
            assertContainsText(texts, McpSteroidConfigurable.HTTP_SECTION_TITLE)
            assertContainsText(texts, "Deprecated")

            // Legacy HTTP section must reference the registry keys so pre-devrig
            // HTTP-based setups can still find their port/host configuration.
            assertContainsText(texts, "mcp.steroid.server.port")
            assertContainsText(texts, "mcp.steroid.server.host")

            // The server URL is part of wiring an HTTP connection by hand, not part of the page's status,
            // so it moved into that section. Rendered only when the server actually bound a port.
            val serverUrl = ApplicationManager.getApplication()
                .getServiceIfCreated(SteroidsMcpServer::class.java)
                ?.takeIf { it.port > 0 }
                ?.mcpUrl
            if (serverUrl != null) {
                assertContainsText(texts, serverUrl)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** The read-only fields the page renders every value in — statuses, URLs and commands alike. */
    private fun collectValueFields(component: Component): List<ExtendableTextField> {
        val found = mutableListOf<ExtendableTextField>()
        fun walk(c: Component) {
            if (c is ExtendableTextField) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    private fun findAllTabbedPanes(component: Component): List<JTabbedPane> {
        val found = mutableListOf<JTabbedPane>()
        fun walk(c: Component) {
            if (c is JTabbedPane) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    /**
     * Copying is the one action on this page with no visible consequence, and a JetBrains button cannot
     * answer for itself: `DarculaButtonUI` paints no pressed state at all. So the copy must both land on
     * the clipboard and be confirmed — and must not blow up when the component is not on screen, which is
     * exactly the case here (and before the Settings dialog is shown).
     */
    fun `test copying puts the content on the clipboard and survives an off-screen component`() {
        val button = JButton("Copy JSON")
        assertFalse("the test component must not be showing", button.isShowing)

        copyWithFeedback("{\"mcpServers\": {}}", button)

        assertEquals(
            "{\"mcpServers\": {}}",
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor),
        )
        assertEquals("Copied", COPIED_HINT)
    }

    /**
     * The receipt under a Register/Enable button must draw the boundary between the label and the command:
     * "runs devrig install claude" read as one sentence, and where the prose ended was anyone's guess
     * (manual click-testing feedback). One explicit method per pinned property — no parameterized tests.
     */
    fun `test the register receipt leads with 'runs command' before the command itself`() {
        for (agent in AiAgentCli.entries) {
            val receipt = agentRegisterCommandComment(agent)
            assertTrue(
                "the receipt must lead with 'runs command:' for ${agent.displayName}; got '$receipt'",
                receipt.startsWith("runs command: "),
            )
            assertTrue(
                "the receipt must name the exact command for ${agent.displayName}; got '$receipt'",
                receipt.contains("<code>devrig install ${agent.binary}</code>"),
            )
        }
    }

    /**
     * "What is devrig?" (settings page) and "Learn more" (status-bar popup) both go to the devrig site
     * ROOT — no sub-URL or doc path — with the IDE build attached as a query parameter, so the site can
     * tell which IDE sent the visitor (owner click-testing feedback). The build is injectable precisely
     * so this test can pin the exact shape.
     */
    fun `test the what-is-devrig link targets the site root with the IDE build as a query param`() {
        assertEquals(
            "https://devrig.dev/?fromIntelliJ=IU-261.25134.95",
            McpSteroidConfigurable.whatIsDevrigUrl("IU-261.25134.95"),
        )

        // The pieces the URL is built from stay pinned on their own: root with no path, and the
        // agreed parameter name.
        assertEquals("https://devrig.dev/", McpSteroidConfigurable.DEVRIG_SITE_URL)
        assertEquals("fromIntelliJ", McpSteroidConfigurable.FROM_INTELLIJ_PARAM)

        // The build value is URL-encoded, so an unexpected build string cannot corrupt the query.
        assertEquals(
            "https://devrig.dev/?fromIntelliJ=IU-261%2F95%26x",
            McpSteroidConfigurable.whatIsDevrigUrl("IU-261/95&x"),
        )

        // Production callers take the default — the running IDE's own build.
        val build = ApplicationInfo.getInstance().build.asString()
        assertEquals(
            McpSteroidConfigurable.whatIsDevrigUrl(build),
            McpSteroidConfigurable.whatIsDevrigUrl(),
        )
        assertTrue(
            "the default URL must start with the root + param prefix; got '${McpSteroidConfigurable.whatIsDevrigUrl()}'",
            McpSteroidConfigurable.whatIsDevrigUrl().startsWith("https://devrig.dev/?fromIntelliJ="),
        )
    }

    private fun assertContainsText(texts: List<String>, expected: String) {
        assertTrue(
            "Settings panel must contain text '$expected'; found:\n${texts.joinToString("\n")}",
            texts.any { it.contains(expected) },
        )
    }

    private fun collectTexts(component: Component, out: MutableList<String> = mutableListOf()): List<String> {
        when (component) {
            // Icon-only labels (e.g. the warning sign) have null text — skip them.
            is JTextComponent -> component.text?.let { out.add(it) }
            is JLabel -> component.text?.let { out.add(it) }
            // browserLink(...) renders as an ActionLink (a JButton subclass).
            is AbstractButton -> component.text?.let { out.add(it) }
        }
        if (component is Container) {
            for (child in component.components) {
                collectTexts(child, out)
            }
        }
        return out
    }
}

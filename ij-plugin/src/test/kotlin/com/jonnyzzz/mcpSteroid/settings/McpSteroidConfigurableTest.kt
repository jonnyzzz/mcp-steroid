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
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
import com.jonnyzzz.mcpSteroid.devrig.devrigInstallAgentCommandLine
import com.jonnyzzz.mcpSteroid.devrig.devrigInstallOneLiner
import com.jonnyzzz.mcpSteroid.devrig.devrigLauncherDisplayPath
import com.jonnyzzz.mcpSteroid.devrig.devrigMcpCommandLine
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable.Companion.COPIED_HINT
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable.Companion.copyWithFeedback
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable.Companion.devrigStdioMcpConfigJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
        val uiScope = CoroutineScope(Job())
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
            // Drive the same populate path it takes: the real devrig probe decides the branch, and the
            // panel is dumb — it renders the answer it is handed, so no subprocess ever spawns here.
            val installed = DevrigSetupRunner.devrigInstalled()
            configurable.applyDevrigInstalled(uiScope, installed)

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

            // Exactly one of the two state-dependent blocks, never both: the install button has nothing
            // to offer someone who already has devrig, and the registration commands mean nothing to
            // someone who does not. Asserted against the real state so this holds on any machine.
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
                    joined.contains("To install:"),
                )
                // With devrig installed there is no install offer, so no installer one-liner either: a
                // copyable `curl … | sh` here would only invite a second, unmanaged install.
                assertFalse(
                    "an installed page must not print installer one-liners; found:\n$joined",
                    joined.contains("install.sh") || joined.contains("install.ps1") || joined.contains("| iex"),
                )
            } else {
                // The install block is transparent: the CLI one-liner is promoted, the Install button
                // beside it does the same thing. The exact per-OS string and the button wiring are
                // pinned in their own test below.
                assertContainsText(texts, "To install:")
                assertContainsText(texts, "Install")
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
                // whole page — short status answers keep one fixed column width.
                val statusFields = collectValueFields(component).filter {
                    it.text.startsWith("Installed") || it.text == "Checking…"
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
            uiScope.cancel()
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
     * What the settings page offers to paste into Cursor (or any other client devrig has no CLI for) must
     * be the registration devrig itself writes — same launcher, same `mcp` subcommand — or the manual path
     * silently stops matching the automatic one. Lives here because the page's companion owns the snippet.
     */
    fun `test the stdio snippet points at the stable launcher and matches what devrig registers`() {
        val home = Path.of("/home/u")

        val posix = devrigStdioMcpConfigJson(home, windows = false)
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("/home/u/.mcp-steroid/bin/devrig", listOf("mcp"))),
            posix,
        )

        // The Windows case pins the cmd.exe wrapping and the quoting, not the path separator: this test
        // also runs on POSIX, where Path.resolve joins with '/'. Separators are the bin-path test's job.
        val winHome = Path.of("C:\\Users\\u")
        val launcher = DevrigSetupRunner.devrigBinPath(winHome, windows = true).toString()
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("cmd.exe", listOf("/d", "/c", "\"$launcher\" mcp"))),
            devrigStdioMcpConfigJson(winHome, windows = true),
        )
    }

    /**
     * Owner rule: every hint on this page names a concrete action — a button, a command, a registry
     * key — never "see the IDE log". A log pointer reports a problem and hands the user homework, which
     * is worse than saying nothing. The panel renders whatever answer it is handed, so this walks BOTH
     * populate branches plus the deprecated-HTTP hints, deterministically on any machine, and rejects
     * any pointer at the log.
     */
    fun `test no rendered hint points at the IDE log`() {
        val configurable = McpSteroidConfigurable()
        val uiScope = CoroutineScope(Job())
        try {
            val component = configurable.createComponent()
            for (installed in listOf(false, true)) {
                configurable.applyDevrigInstalled(uiScope, installed)
                val joined = collectTexts(component).joinToString("\n")
                assertFalse(
                    "every hint must name its action, never the IDE log; with installed=$installed found:\n$joined",
                    joined.contains("IDE log", ignoreCase = true) || joined.contains("Show Log"),
                )
            }
        } finally {
            uiScope.cancel()
            configurable.disposeUIResources()
        }
    }

    /**
     * The agent rows are DISPLAY-ONLY (owner direction, 2026-08-06): one long read-only copyable field
     * per agent, carrying the exact platform-correct command the user runs in a terminal — the absolute
     * stable launcher plus devrig's canonical, idempotent 'install <agent>' verb (issue #399 contract).
     * No state checking, no Register/Enable buttons: the panel renders the installed branch it is
     * handed, so this is deterministic on any machine. The per-OS path forms (POSIX vs `.cmd`,
     * backslashes, space-quoting) are pinned in devrig-common's DevrigUserLauncherTest; here the page
     * must render THIS OS's form, verbatim, one field per agent.
     */
    fun `test each agent gets a display-only copyable field with the absolute install command`() {
        val configurable = McpSteroidConfigurable()
        val uiScope = CoroutineScope(Job())
        try {
            val component = configurable.createComponent()
            configurable.applyDevrigInstalled(uiScope, devrigInstalled = true)

            val fields = collectValueFields(component).map { it.text }
            val launcher = devrigLauncherDisplayPath(System.getProperty("user.home"), SystemInfo.isWindows)
            for (agent in AiAgentCli.entries) {
                val command = devrigInstallAgentCommandLine(
                    System.getProperty("user.home"), SystemInfo.isWindows, agent,
                )
                assertTrue(
                    "the ${agent.displayName} row must render '$command' verbatim; fields: $fields",
                    fields.any { it == command },
                )
                assertTrue(
                    "the command must lead with the absolute launcher path, never a bare 'devrig'; got '$command'",
                    command.startsWith(launcher) ||
                        // The quoted Windows forms — with PowerShell's call operator, since this is a
                        // terminal command (pinned per-OS in DevrigUserLauncherTest).
                        command.startsWith("\"$launcher\"") || command.startsWith("& \"$launcher\""),
                )
                assertTrue(
                    "the command must end with devrig's canonical install verb; got '$command'",
                    command.endsWith(" install ${agent.binary}"),
                )
            }

            // Display-only means NO registration machinery: no Register/Enable buttons and no live
            // per-agent state words anywhere on the page.
            val joined = collectTexts(component).joinToString("\n")
            for (gone in listOf("Registered", "not registered", "switched off", "Checking…", "on your PATH")) {
                assertFalse(
                    "the page must not render per-agent registration state; found '$gone' in:\n$joined",
                    joined.contains(gone),
                )
            }
            val buttons = collectButtons(component).mapNotNull { it.text }
            assertFalse(
                "no Register/Enable buttons may remain; buttons: $buttons",
                buttons.any { it == "Register" || it == "Enable" },
            )
        } finally {
            uiScope.cancel()
            configurable.disposeUIResources()
        }
    }

    /**
     * The install block is fully transparent (owner direction, 2026-08-06): the CLI path is promoted —
     * the canonical one-liner the website publishes, rendered read-only and copyable, VERBATIM for this
     * OS — and the adjacent Install button does visibly the same thing (fetch that script via the shared
     * devrig-common download, run it under the progress task). Nothing else may live in the block: no
     * state prose, no cost paragraph, no second path. The panel renders the branch it is handed, so both
     * sides are deterministic on any machine.
     */
    fun `test the install block promotes the CLI one-liner next to a trivial Install button`() {
        val configurable = McpSteroidConfigurable()
        val uiScope = CoroutineScope(Job())
        try {
            val component = configurable.createComponent()
            configurable.applyDevrigInstalled(uiScope, devrigInstalled = false)

            // The one-liner: this OS's canonical form, verbatim, in the page's copyable field style.
            val oneLiner = devrigInstallOneLiner(SystemInfo.isWindows)
            val fields = collectValueFields(component).map { it.text }
            assertTrue(
                "the install row must render '$oneLiner' verbatim in a copyable field; fields: $fields",
                fields.any { it == oneLiner },
            )
            assertContainsText(collectTexts(component), "To install:")

            // The Install button sits right next to it — the same install, one press instead of a paste.
            val buttons = collectButtons(component).mapNotNull { it.text }
            assertTrue("expected an Install button; buttons: $buttons", buttons.any { it == "Install" })

            // Transparency means ONLY the install action: the old prose block is gone.
            val joined = collectTexts(component).joinToString("\n")
            assertFalse(
                "the install block keeps only the install action; found stale prose in:\n$joined",
                joined.contains("devrig is not installed") || joined.contains("Downloads about 611 MB"),
            )

            // Once installed, both the one-liner and the button disappear together.
            configurable.applyDevrigInstalled(uiScope, devrigInstalled = true)
            val installedFields = collectValueFields(component).map { it.text }
            assertFalse(
                "an installed page must not render the installer one-liner; fields: $installedFields",
                installedFields.any { it == oneLiner },
            )
            assertFalse(
                "an installed page must not render an Install button",
                collectButtons(component).mapNotNull { it.text }.any { it == "Install" },
            )
        } finally {
            uiScope.cancel()
            configurable.disposeUIResources()
        }
    }

    /** Buttons only — [collectTexts] also picks up links and labels, which may share words. */
    private fun collectButtons(component: Component): List<JButton> {
        val found = mutableListOf<JButton>()
        fun walk(c: Component) {
            if (c is JButton) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    /**
     * The settings page's "What is devrig?" link goes to the devrig site ROOT — no sub-URL or doc
     * path — with the IDE build attached as a query parameter, so the site can tell which IDE sent
     * the visitor (owner click-testing feedback). The build is injectable precisely so this test can
     * pin the exact shape.
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

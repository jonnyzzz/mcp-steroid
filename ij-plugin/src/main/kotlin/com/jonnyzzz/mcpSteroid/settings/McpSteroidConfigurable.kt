/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.launchOnShow
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
import com.jonnyzzz.mcpSteroid.devrig.DevrigUserLauncher
import com.jonnyzzz.mcpSteroid.devrig.devrigInstallAgentCommandLine
import com.jonnyzzz.mcpSteroid.devrig.devrigInstallOneLiner
import com.jonnyzzz.mcpSteroid.devrig.devrigMcpCommandLine
import com.jonnyzzz.mcpSteroid.devrig.resolveHomePaths
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Application-level settings page: Settings | Tools | Devrig — MCP Steroid.
 *
 * Purely informational — no persistent state, no mutable options. The page exists so users
 * can confirm the plugin is installed and connect an AI agent:
 *
 * 1. **Devrig** — the whole recommended path in one group, read top to bottom: why it is worth a separate
 *    binary, then devrig's own state plus whatever the next step is: install it, or point an agent at it.
 *    The per-agent registrations are DISPLAY-ONLY: one long copyable command per agent, the same
 *    `<launcher> install <agent>` the docs promote, for the user to run in a terminal. The IDE neither
 *    checks an agent's registration state nor runs the registration itself — an earlier revision did
 *    both, and the state machine (per-agent probes, Register/Enable buttons, failure notifications with
 *    Retry) broke in practice where a printed command could not (owner direction, 2026-08-06).
 * 2. **Direct HTTP (deprecated)**, collapsed and last: the in-IDE server's live state (a cheap
 *    [SteroidsMcpServer.port] read from an in-memory atomic, no background work on the settings thread),
 *    its URL, per-agent `mcp add` commands, generic `mcpServers` JSON, registry keys. Nobody should start
 *    here; the setups that already did still need to look their own configuration up — and the server row
 *    lives with them, because a port on this single IDE only matters to someone wiring HTTP by hand.
 *
 * **The EDT never touches the disk here, and the panel is dumb.** The devrig block starts as a
 * "Checking…" placeholder; [launchOnShow] re-runs the populate every time the page becomes showing —
 * read the one remaining fact ([DevrigSetupRunner.devrigInstalled], file I/O on [Dispatchers.IO]), render the answer —
 * and cancels it when the page is hidden. The install button's await is a child of that same
 * dialog-scoped coroutine, so it dies with the dialog too, while the work it waits on (a background
 * install task) runs to completion regardless. There is no cached state: every show computes reality
 * afresh, so there is also nothing to invalidate and nothing to listen to. (Precedents: the
 * platform's own MCP-server settings page and the Terminal's shell-path detection use the same
 * on-show idiom.)
 */
class McpSteroidConfigurable : BoundConfigurable(DISPLAY_NAME) {

    /** The one state-dependent block, swapped in place as its state is computed. EDT-confined. */
    private var installStatus: Placeholder? = null

    override fun disposeUIResources() {
        installStatus = null
        super.disposeUIResources()
    }

    override fun createPanel(): DialogPanel {
        // Cheap reads only: port comes from an AtomicReference inside the app service.
        // getServiceIfCreated makes the panel structurally incapable of triggering service
        // construction on the EDT — in production the server service is created at IDE startup,
        // so a null here renders the same "Not running" state as a port that never bound.
        val server = ApplicationManager.getApplication().getServiceIfCreated(SteroidsMcpServer::class.java)
        val port = server?.port ?: 0
        val info = if (server != null && port > 0) McpConnectionInfo.build(server.mcpUrl) else null
        // Prose references only the actual bound port, so every message matches the Status block;
        // with no bound port (server not running) it degrades to a generic phrase instead of "port 0".
        // No hard-coded default here — 6315 lives solely in the registry config.
        val portPhrase = if (port > 0) "on port <b>$port</b>" else "on its HTTP port"

        val panel = panel {
            // No pitch row. Whoever opens this page has already installed the plugin, so "AI agents work
            // inside your IDE — not just over your files" was selling something they own, in the one place
            // they came to check state and press a button. That copy belongs in the Marketplace description,
            // and the one line still worth reading here — why devrig is a separate binary — is where it is
            // actionable, next to the button.

            // One group for the whole recommended path: why it exists, then where both ends of it stand.
            // It was two groups — a Status one and a Devrig one — which split one story across two boxes
            // and made the pitch outlive its usefulness by sitting between the reader and the state.
            group("Devrig") {
                // One line, then a link. The long version of this pitch is what made the page unreadable:
                // by the time a user got to something clickable they had read a dozen lines of prose.
                row {
                    text(
                        "One bridge between your agent and <b>every</b> IntelliJ IDE you have open. It survives " +
                            "IDE restarts and port changes, and can even start an IDE on demand for headless runs."
                    )
                }
                row {
                    browserLink("What is devrig?", whatIsDevrigUrl())
                }

                // A placeholder, not a plain row: the state is computed off the EDT after the page is
                // shown, and an install finishes minutes later — the block must be replaceable in place.
                row {
                    installStatus = placeholder().align(AlignX.FILL)
                }
                installStatus?.component = checkingPanel()
            }

            httpSection(port, portPhrase, info)

            // Last, because it is never the next step for someone who just opened this page — it is where
            // you go after everything else failed to help.
            row {
                browserLink("Report an issue on GitHub", FEEDBACK_URL)
            }.topGap(TopGap.SMALL)
        }

        // Populate on show, recompute on every re-show. The block starts on the UI dispatcher under the
        // panel's own modality whenever the panel becomes showing, and is cancelled when it is hidden —
        // the platform's own settings pages (MCP server clients detection, Terminal shell-path detection)
        // use the same idiom for slow disk answers. The EDT only launches, awaits and applies; the one
        // fact read is the devrigInstalled() file probe, on Dispatchers.IO.
        panel.launchOnShow("McpSteroidConfigurable devrig state") {
            installStatus?.component = checkingPanel()
            applyDevrigInstalled(this, withContext(Dispatchers.IO) { DevrigSetupRunner.devrigInstalled() })
            // The scope must outlive the populate: the install button just rendered launches its
            // await-and-render child on it, and that must be dialog-scoped the same way the
            // populate is. launchOnShow cancels this coroutine when the panel stops showing and
            // restarts it on re-show — which is exactly the populate-on-show contract.
            awaitCancellation()
        }
        return panel
    }

    /** The devrig block while the probe runs: the same field shape its answers render in. */
    private fun checkingPanel(): DialogPanel = panel {
        row("devrig:") {
            cell(valueTextField("Checking…")).columns(STATUS_FIELD_COLUMNS)
        }
    }

    /**
     * Swap the devrig block for one rendering [DevrigSetupRunner.devrigInstalled]. EDT only; a no-op once the page is
     * gone. Public so a test, whose panel is never physically showing, can drive the same populate path
     * the on-show launch takes — and hand it either answer, because the panel just renders what it is
     * given.
     *
     * [uiScope] carries the await the install button starts: in production it is the on-show
     * coroutine's own scope, so a block update launched from a press is cancelled with the dialog
     * exactly like the populate itself — while the awaited work (a background task) runs on.
     */
    fun applyDevrigInstalled(uiScope: CoroutineScope, devrigInstalled: Boolean) {
        val placeholder = installStatus ?: return
        placeholder.component = installStatusPanel(uiScope, devrigInstalled)
    }

    /**
     * The only state-dependent block, and the only one rebuilt while the page is open: either the offer
     * to install devrig, or — once it is there — the next step, which is registering an agent with it.
     *
     * Showing exactly one of the two is the point. The button has nothing to offer someone who already
     * has devrig, and the registration commands mean nothing to someone who does not.
     */
    private fun installStatusPanel(uiScope: CoroutineScope, devrigInstalled: Boolean): DialogPanel = panel {
        // The one raw fact every path below derives from. Read once; everything path-shaped is then
        // built by devrig-common's renderers / resolveHomePaths over this value — never joined by hand.
        val userHome = System.getProperty("user.home")
        if (devrigInstalled) {
            row("devrig:") {
                // A fixed medium width, not AlignX.FILL: stretched across the whole page the field
                // dwarfed the agent value areas right below it (owner click-testing feedback). Plain
                // "Installed" — devrig updates itself, so there is no version worth naming here.
                cell(valueTextField("Installed")).columns(STATUS_FIELD_COLUMNS)
            }
            row {
                text(
                    "<b>Point an agent at it</b> — run its command in a terminal, once per machine, " +
                        "not once per project. Re-running one is safe: it repairs and never duplicates."
                )
            }.topGap(TopGap.SMALL)
            // Display-only, one long copyable command per agent — the same field style as the
            // command-line rows below. The IDE deliberately neither checks an agent's registration
            // state nor runs the registration itself: the earlier per-agent state machine
            // (Checking…/Registered/Register/Enable buttons) broke in practice where a printed
            // command could not (owner direction, 2026-08-06). Built through
            // [devrigInstallAgentCommandLine] (real absolute launcher, quoted when the path holds a
            // space), copyable so display and clipboard are the same string.
            for (agent in AiAgentCli.entries) {
                row("${agent.displayName}:") {
                    cell(copyableTextField(devrigInstallAgentCommandLine(userHome, SystemInfo.isWindows, agent)))
                        .align(AlignX.FILL)
                }
            }
            // The registration devrig has no verb for, right under the three it does: any client with an
            // "add MCP server" dialog takes this one command line. It used to hide inside the collapsed
            // "Another MCP client" group below the JSON snippet, where nobody wiring such a client would
            // look first (owner click-testing feedback) — the agent rows are where "point a client at
            // devrig" answers live, so the command line is one of them. Built through [devrigMcpCommandLine]
            // (real absolute launcher, quoted when the path holds a space), copyable so display and
            // clipboard are the same string.
            row {
                text("Register devrig as an MCP server in <b>any other client</b> with:")
            }.topGap(TopGap.SMALL)
            row {
                cell(copyableTextField(devrigMcpCommandLine(userHome, SystemInfo.isWindows)))
                    .align(AlignX.FILL)
            }
            row {
                comment(
                    "Registrations point at the stable launcher, so they survive devrig updates. Restart a " +
                        "running agent session to pick one up."
                )
            }
            otherClientsSection(Path.of(userHome))
        } else {
            // The install block is fully transparent (owner direction, 2026-08-06): only the install
            // action, with the CLI path promoted as THE way in. The copyable field carries the exact
            // one-liner the website publishes ([devrigInstallOneLiner], pinned verbatim in
            // devrig-common), and the Install button beside it visibly does the very same thing the
            // text shows — fetch that script (the shared devrig-common downloadInstallerScript) and
            // run it, inside the existing cancellable progress task ([DevrigSetupRunner]). Same URL,
            // same script, one behavior with two triggers; nothing else is kept in the block.
            row("To install:") {
                cell(copyableTextField(devrigInstallOneLiner(SystemInfo.isWindows)))
                    .align(AlignX.FILL)
                button("Install") {
                    // Application-level page: any open project just anchors the progress bar, and none
                    // is fine too (the task then runs at IDE level). The install itself is a background
                    // task the user must be able to see through to the end; this coroutine — the
                    // completion of a button the user pressed, not a monitoring pipeline — only awaits
                    // it to stop offering an install that just succeeded, and dies with the dialog
                    // while the task keeps going.
                    uiScope.launch {
                        DevrigSetupRunner.getInstance()
                            .install(ProjectManager.getInstance().openProjects.firstOrNull())
                        applyDevrigInstalled(uiScope, withContext(Dispatchers.IO) { DevrigSetupRunner.devrigInstalled() })
                    }
                }
            }
        }
    }

    /**
     * The manual recipe for the clients devrig has no CLI for and that read an `mcpServers` JSON file —
     * Cursor, Windsurf, and friends. Same server, same launcher, same `mcp` subcommand as the buttons
     * above; the only difference is that the user pastes it themselves. Clients that ask for a bare
     * command instead of a file are served by the `<launcher> mcp` command-line row up with the agent
     * rows — this collapsed group holds only the JSON shape.
     *
     * Collapsed, and only shown once devrig is installed, because the snippet names a launcher path that
     * has to exist to be worth copying. This is the settings-page twin of `devrig install config`; the
     * JSON is built through [devrigStdioMcpConfigJson], so it cannot drift from what
     * `devrig install <agent>` writes.
     */
    private fun Panel.otherClientsSection(userHome: Path) {
        val json = devrigStdioMcpConfigJson(userHome, SystemInfo.isWindows)
        collapsibleGroup(OTHER_CLIENTS_SECTION_TITLE) {
            row {
                text(
                    "Any MCP client that reads an <code>mcpServers</code> JSON file can reach this IDE " +
                        "through the same devrig — it runs as a stdio MCP server. Add:"
                )
            }
            row {
                val textArea = JBTextArea(json).apply {
                    isEditable = false
                    rows = json.lines().size
                }
                cell(JBScrollPane(textArea)).align(Align.FILL)
            }.topGap(TopGap.NONE)
            row {
                button("Copy JSON") { event ->
                    copyWithFeedback(json, event.source as? JComponent)
                }
            }
            row {
                comment(
                    "The same stable launcher as every command above, so it survives devrig updates too. " +
                        "<code>devrig install config</code> prints this in a terminal."
                )
            }
        }.topGap(TopGap.SMALL)
    }

    /**
     * The deprecated single-IDE path, collapsed and last: the in-IDE server's live state, its URL, the
     * per-agent `mcp add` commands, the generic JSON, and the registry keys.
     *
     * Collapsed rather than deleted. Nobody should start here, but the setups that already did need
     * somewhere to look up their own configuration — and the server row is part of that lookup, not part
     * of the page's status: this single IDE's port is only ever useful to someone wiring HTTP by hand
     * (devrig finds every running IDE on its own).
     */
    private fun Panel.httpSection(port: Int, portPhrase: String, info: McpConnectionInfo?) {
        collapsibleGroup(HTTP_SECTION_TITLE) {
            row {
                icon(AllIcons.General.Warning)
                text(
                    "<b>Deprecated.</b> These manual HTTP commands point at <b>this</b> IDE " +
                        "$portPhrase — they stop working when the IDE restarts or that port is reassigned, " +
                        "and every agent must be set up by hand. Use devrig instead: it reaches every running " +
                        "IDE automatically and keeps working across restarts and port changes."
                )
            }
            if (info != null) {
                row("MCP server:") {
                    cell(valueTextField("Running on port $port")).align(AlignX.FILL)
                }
            } else {
                row("MCP server:") {
                    cell(valueTextField("Not running")).align(AlignX.FILL)
                }
                row {
                    // The one fix a user can apply from here is named outright: a taken port is the
                    // usual reason the bind fails, and the key to move it is on this very page. "Check
                    // the IDE log for bind errors" was homework in place of that action.
                    comment(
                        "The server normally starts at IDE startup; a taken port is the usual reason it " +
                            "could not. Set <code>mcp.steroid.server.port</code> via the registry keys " +
                            "below and restart the IDE."
                    )
                }
            }
            if (info != null) {
                row("Server URL:") {
                    cell(copyableTextField(info.serverUrl)).align(AlignX.FILL)
                }
                row {
                    text("If you still want a direct streamable-HTTP connection to this single IDE instance:")
                }.topGap(TopGap.SMALL)
                for ((name, command) in info.commands) {
                    row("$name:") {
                        cell(copyableTextField(command)).align(AlignX.FILL)
                    }
                }
                group("JSON Config") {
                    val json = info.jsonConfig.trim()
                    row {
                        // Size the area to the content so the whole block is visible without
                        // an inner scrollbar.
                        val textArea = JBTextArea(json).apply {
                            isEditable = false
                            rows = json.lines().size.coerceAtLeast(3)
                        }
                        cell(JBScrollPane(textArea)).align(Align.FILL)
                    }.topGap(TopGap.NONE)
                    row {
                        button("Copy JSON Config") { event ->
                            copyWithFeedback(json, event.source as? JComponent)
                        }
                    }
                }
            }
            row {
                comment(
                    "Port and bind address are configurable via the IDE Registry: " +
                        "<code>mcp.steroid.server.port</code> (0 = auto-assign) and " +
                        "<code>mcp.steroid.server.host</code>."
                )
            }
        }
    }

    /**
     * Read-only value field, no copy icon: the shape every status on this page is rendered in.
     *
     * A bare label made the values hard to find — the label-and-value pairs ran together as one line of
     * prose, and nothing said which half was the answer. A field draws the boundary the reader is looking
     * for, and being a text component it also lets the value be selected and copied by hand.
     *
     * Read-only via isEditable = false — the platform affordance. An earlier revision kept
     * isEditable = true and swallowed keystrokes with a DocumentFilter, claiming a non-editable field
     * "greys out and reads as broken". Verified against the platform LaF: that claim conflated
     * non-editable with DISABLED. Non-editable does change the paint — DarculaTextFieldUI's
     * paintDarculaBackground skips the inner fill (the interior takes the panel background) and
     * DarculaTextBorderNew dims the border — but the text keeps its normal foreground (greying is tied
     * to isEnabled, not isEditable), and selection and copy keep working. That flatter rendering is
     * exactly how the IDE marks a read-only field; the filter instead faked an editable field — caret,
     * focus, no reaction to typing — which read as frozen.
     */
    private fun valueTextField(content: String): ExtendableTextField =
        ExtendableTextField().apply {
            text = content
            isEditable = false
        }

    /**
     * The same field plus an in-border copy-to-clipboard icon — for content that exists to be pasted
     * somewhere else (URLs, commands), where reaching for the mouse to select it is the wrong ask.
     *
     * The icon sits visually INSIDE the field border, the same as the Terminal's env-vars fields.
     */
    private fun copyableTextField(content: String): ExtendableTextField =
        valueTextField(content).apply {
            addExtension(ExtendableTextComponent.Extension.create(
                AllIcons.General.InlineCopy,
                AllIcons.General.InlineCopyHover,
                "Copy to clipboard"
            ) {
                // Same receipt as the buttons: the icon swaps to its hover variant and nothing else on
                // screen moves, so a copy that worked and a click that missed look the same.
                copyWithFeedback(content, this)
            })
        }

    companion object {
        /** Must match the id attribute of the applicationConfigurable EP in plugin.xml. */
        const val CONFIGURABLE_ID = "com.jonnyzzz.mcp-steroid.settings"

        /** Must match the displayName attribute of the applicationConfigurable EP in plugin.xml. */
        const val DISPLAY_NAME = "Devrig — MCP Steroid"

        /** Title of the collapsed section holding the deprecated direct-HTTP setup. */
        const val HTTP_SECTION_TITLE = "Direct HTTP connection (deprecated)"

        /** Title of the collapsed section with the manual stdio config for clients devrig cannot register. */
        const val OTHER_CLIENTS_SECTION_TITLE = "Another MCP client (Cursor, Windsurf, …)"

        /**
         * Width of every short status field in the devrig block ("Installed", "Checking…"), in
         * text-field columns. One shared constant, so these fields read as one column of answers: the
         * installed-state field used to be AlignX.FILL and spanned the entire page, dwarfing the value
         * areas right below it (owner click-testing feedback). COLUMNS_MEDIUM is the DSL's own standard
         * value-field width; long copyable content (URLs, command lines) keeps AlignX.FILL because it
         * exists to be read in full.
         */
        const val STATUS_FIELD_COLUMNS = COLUMNS_MEDIUM

        /**
         * Where every "What is devrig?" / "Learn more" link in the plugin UI goes: the site ROOT —
         * the pitch is the front page, not a doc path (owner click-testing feedback: the old
         * `/docs/devrig/` target dropped visitors into reference material).
         */
        const val DEVRIG_SITE_URL = "https://devrig.dev/"

        /** Query parameter carrying which IDE build sent the visitor, e.g. `fromIntelliJ=IU-261.25134.95`. */
        const val FROM_INTELLIJ_PARAM = "fromIntelliJ"

        /**
         * The full link target: site root plus the IDE build as a query parameter, so the site can tell
         * (and tailor for) visits coming from inside an IDE. [ideBuild] is injectable so tests can pin
         * the exact URL shape; production callers take the default — the running IDE's own build.
         */
        fun whatIsDevrigUrl(
            ideBuild: String = ApplicationInfo.getInstance().build.asString(),
        ): String = DEVRIG_SITE_URL + "?" + FROM_INTELLIJ_PARAM + "=" +
            URLEncoder.encode(ideBuild, StandardCharsets.UTF_8)

        const val FEEDBACK_URL = "https://github.com/jonnyzzz/mcp-steroid/issues"

        /** What the confirmation says. Short on purpose: it is a receipt, not a message. */
        const val COPIED_HINT = "Copied"

        /** Long enough to read one word, short enough to never be in the way. */
        private const val COPIED_HINT_FADEOUT_MS = 2000L

        /**
         * Put [content] on the clipboard and say so, next to [source].
         *
         * A copy button is the one control on this page that changes nothing you can see: the field keeps
         * its text, no panel is rebuilt, no progress starts. And a JetBrains button cannot answer for
         * itself either — `DarculaButtonUI` paints no pressed state at all (the theme defines no pressed
         * colour, and `JBUI.CurrentTheme.Button`'s palette takes no state), so pressing one looks identical
         * to hovering over it. Without a hint, "did that work?" has no answer anywhere on screen.
         *
         * A balloon above the button is the platform's own gesture for this, so nothing new is invented —
         * and it is skipped when the component is not on screen (a panel built in a test, or before the
         * dialog is shown), where asking for a screen location would throw.
         */
        fun copyWithFeedback(content: String, source: JComponent?) {
            CopyPasteManager.getInstance().setContents(StringSelection(content))
            if (source == null || !source.isShowing) return
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(COPIED_HINT, MessageType.INFO, null)
                .setFadeoutTime(COPIED_HINT_FADEOUT_MS)
                .createBalloon()
                .show(RelativePoint.getCenterOf(source), Balloon.Position.above)
        }

        /**
         * The `mcpServers` snippet that points an MCP client at this machine's devrig over stdio — for the
         * clients devrig has no CLI for (Cursor, Windsurf, anything configured by an `mcp.json`-style
         * file). This page is its only consumer: the settings-page twin of `devrig install config`.
         *
         * Built from the same [DevrigUserLauncher.invocation] that devrig itself registers with (and that
         * `devrig install config` prints), so what the settings page offers to copy and what
         * `devrig install <agent>` writes cannot drift.
         */
        fun devrigStdioMcpConfigJson(userHome: Path, windows: Boolean): String =
            stdioMcpServersJson(DevrigUserLauncher.invocation(resolveHomePaths(userHome), listOf("mcp"), windows))
    }
}

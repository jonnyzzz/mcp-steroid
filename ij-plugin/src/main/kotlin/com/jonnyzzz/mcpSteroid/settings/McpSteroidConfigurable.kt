/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.aiAgents.devrigHomeDisplayPath
import com.jonnyzzz.mcpSteroid.aiAgents.devrigMcpCommandLine
import com.jonnyzzz.mcpSteroid.onboarding.devrigStdioMcpConfigJson
import com.jonnyzzz.mcpSteroid.onboarding.AgentRegistrationState
import com.jonnyzzz.mcpSteroid.onboarding.DEVRIG_STATE_CHANGED
import com.jonnyzzz.mcpSteroid.onboarding.DevrigAgentRegistrationService
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionState
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionStateService
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner
import com.jonnyzzz.mcpSteroid.onboarding.DevrigStateListener
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

/** What the confirmation says. Short on purpose: it is a receipt, not a message. */
const val COPIED_HINT = "Copied"

/**
 * Put [content] on the clipboard and say so, next to [source].
 *
 * A copy button is the one control on this page that changes nothing you can see: the field keeps its
 * text, no panel is rebuilt, no progress starts. And a JetBrains button cannot answer for itself either —
 * `DarculaButtonUI` paints no pressed state at all (the theme defines no pressed colour, and
 * `JBUI.CurrentTheme.Button`'s palette takes no state), so pressing one looks identical to hovering over
 * it. Without a hint, "did that work?" has no answer anywhere on screen.
 *
 * A balloon above the button is the platform's own gesture for this, so nothing new is invented — and it
 * is skipped when the component is not on screen (a panel built in a test, or before the dialog is shown),
 * where asking for a screen location would throw.
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

/** Long enough to read one word, short enough to never be in the way. */
private const val COPIED_HINT_FADEOUT_MS = 2000L

/**
 * The receipt under a Register/Enable button. It leads with "runs command:" so the boundary between the
 * label and the command itself is unambiguous — "runs devrig install claude" read as one sentence, and a
 * user could not tell where the prose ended and the command began.
 */
fun agentRegisterCommandComment(agent: AiAgentCli): String =
    "runs command: <code>devrig install ${agent.binary}</code>"

/**
 * Application-level settings page: Settings | Tools | Devrig — MCP Steroid.
 *
 * Purely informational — no persistent state, no mutable options. The page exists so users
 * can confirm the plugin is installed and connect an AI agent:
 *
 * 1. **Devrig** — the whole recommended path in one group, read top to bottom: why it is worth a separate
 *    binary, then devrig's own state plus whatever the next step is: install it, or point an agent at it.
 *    This page is the only place the plugin offers the install — see
 *    [com.jonnyzzz.mcpSteroid.onboarding.devrigWidgetEnabled].
 * 2. **Direct HTTP (deprecated)**, collapsed and last: the in-IDE server's live state (a cheap
 *    [SteroidsMcpServer.port] read from an in-memory atomic, no background work on the settings thread),
 *    its URL, per-agent `mcp add` commands, generic `mcpServers` JSON, registry keys. Nobody should start
 *    here; the setups that already did still need to look their own configuration up — and the server row
 *    lives with them, because a port on this single IDE only matters to someone wiring HTTP by hand.
 *
 * The only I/O is two small file reads that answer "is devrig installed, and which version" while the
 * panel is being built.
 *
 * The panel is built once per Settings dialog opening, so the status is a snapshot — that
 * matches the old (pre-0.96) connection-info page behavior.
 */
class McpSteroidConfigurable : BoundConfigurable(DISPLAY_NAME) {

    /** The one block that is rebuilt while the page is open; see [refreshInstallStatus]. */
    private var installStatus: Placeholder? = null

    /** Scopes the [DEVRIG_STATE_CHANGED] subscription to one opening of the dialog. */
    private var uiDisposable: CheckedDisposable? = null

    override fun disposeUIResources() {
        installStatus = null
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
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
        // Two file reads (is the launcher there, and which version does it point at) — computed here
        // rather than cached, because the panel is built once per dialog opening and a stale answer on
        // this page is worse than a stat.
        val devrigState = DevrigConnectionStateService.getInstance().localState()

        // An install runs in the background and finishes while this page is open, so the page listens
        // instead of staying a snapshot. Scoped to this opening of the dialog.
        val disposable = Disposer.newCheckedDisposable()
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = disposable
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(DEVRIG_STATE_CHANGED, DevrigStateListener { refreshInstallStatus() })

        return panel {
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

                // A placeholder, not a plain row: an install takes minutes and finishes while this page is
                // open, so the block has to be replaceable in place. Rebuilt from DEVRIG_STATE_CHANGED.
                row {
                    installStatus = placeholder().align(AlignX.FILL)
                }
                installStatus?.component = installStatusPanel(devrigState)
            }

            httpSection(port, portPhrase, info)

            // Last, because it is never the next step for someone who just opened this page — it is where
            // you go after everything else failed to help.
            row {
                browserLink("Report an issue on GitHub", FEEDBACK_URL)
            }.topGap(TopGap.SMALL)
        }
    }

    /**
     * The only state-dependent block, and the only one rebuilt while the page is open: either the offer
     * to install devrig, or — once it is there — the next step, which is registering an agent with it.
     *
     * Showing exactly one of the two is the point. The button has nothing to offer someone who already
     * has devrig, and the registration commands mean nothing to someone who does not.
     */
    private fun installStatusPanel(state: DevrigConnectionState): DialogPanel = panel {
        if (state.devrigInstalled) {
            row("devrig:") {
                // A fixed medium width, not AlignX.FILL: stretched across the whole page the field
                // dwarfed the agent value areas right below it (owner click-testing feedback). The
                // status fields in this block all share STATUS_FIELD_COLUMNS so they line up as one
                // column of answers.
                cell(valueTextField("Installed" + (state.installedVersion?.let { " — version $it" } ?: "")))
                    .columns(STATUS_FIELD_COLUMNS)
            }
            row {
                text("<b>Point an agent at it</b> — once per machine, not once per project:")
            }.topGap(TopGap.SMALL)
            for (agent in AiAgentCli.entries) {
                row("${agent.displayName}:") {
                    val placeholder = placeholder()
                    placeholder.component = agentRow(agent, AgentRegistrationState.CHECKING, placeholder)
                    // Answered in the background: the check runs the agent's own CLI, which is not
                    // instant, and a settings page must not wait on it.
                    DevrigAgentRegistrationService.getInstance().checkAsync(agent) { state ->
                        updateAgentRow(placeholder, agent, state)
                    }
                }
            }
            row {
                comment(
                    "Registrations point at the stable launcher, so they survive devrig updates. Restart a " +
                        "running agent session to pick one up."
                )
            }
            otherClientsSection()
        } else {
            row {
                text("<b>devrig is not installed yet.</b>")
            }
            row {
                button("Install devrig") {
                    // Application-level page: any open project just anchors the progress bar, and none
                    // is fine too (the task then runs at IDE level).
                    DevrigSetupRunner().runInstall(ProjectManager.getInstance().openProjects.firstOrNull())
                }
            }
            row {
                // The real home, never `~` — on Windows a tilde is a placeholder the OS will not expand,
                // and this page's policy is that a displayed path is the literal path on disk.
                val home = devrigHomeDisplayPath(System.getProperty("user.home"), SystemInfo.isWindows)
                comment(
                    "Downloads about 611 MB (a pinned JDK plus devrig) into <code>$home</code> and " +
                        "puts <code>devrig</code> on your PATH. It registers nothing with your agents — " +
                        "that is the next step, and it appears here once devrig is in place."
                )
            }
        }
    }

    /**
     * The manual recipe for the clients devrig has no CLI for — Cursor, Windsurf, anything configured by an
     * `mcpServers` JSON file or an "add MCP server" dialog. Same server, same launcher, same `mcp`
     * subcommand as the buttons above; the only difference is that the user pastes it themselves. Two
     * shapes of the same registration: the JSON snippet for file-configured clients, and the bare
     * `<launcher> mcp` command line for clients that ask for a command.
     *
     * Collapsed, and only shown once devrig is installed, because both name a launcher path that has to
     * exist to be worth copying. This is the settings-page twin of `devrig install config`; the JSON is
     * built through [devrigStdioMcpCommand] and the command line through [devrigMcpCommandLine], so
     * neither can drift from what `devrig install <agent>` writes.
     */
    private fun Panel.otherClientsSection() {
        val userHome = System.getProperty("user.home")
        val windows = SystemInfo.isWindows
        val json = devrigStdioMcpConfigJson(Path.of(userHome), windows)
        val commandLine = devrigMcpCommandLine(userHome, windows)
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
                text("Or register devrig as an MCP server with the following command line:")
            }.topGap(TopGap.SMALL)
            row {
                cell(copyableTextField(commandLine)).align(AlignX.FILL)
            }
            row {
                comment(
                    "The same registration the buttons above write, so it survives devrig updates too. " +
                        "<code>devrig install config</code> prints this in a terminal."
                )
            }
        }.topGap(TopGap.SMALL)
    }

    /**
     * One agent's cell: what we know, and a button only where pressing one would achieve something.
     *
     * The states are not interchangeable. "Register" is offered when devrig would actually change
     * something; an already-registered agent gets no button, because a button on a finished step reads as
     * an unfinished setup. A missing CLI says so instead of offering a press that could only fail, and a
     * check we could not complete says *that* — reporting it as "not registered" would be a guess.
     */
    private fun agentRow(
        agent: AiAgentCli,
        state: AgentRegistrationState,
        placeholder: Placeholder,
    ): DialogPanel = panel {
        row {
            when (state) {
                // The two states that are pure fact get the same value field as the rows above them — same
                // shape, same STATUS_FIELD_COLUMNS width, so the devrig row and the agent rows read as one
                // column of answers. The rest are an action plus a reason, and a field would only dress up
                // a button.
                AgentRegistrationState.CHECKING ->
                    cell(valueTextField("Checking…")).columns(STATUS_FIELD_COLUMNS)
                AgentRegistrationState.REGISTERED ->
                    cell(valueTextField("Registered")).columns(STATUS_FIELD_COLUMNS)
                AgentRegistrationState.NOT_REGISTERED -> {
                    registerButton(agent, "Register", placeholder)
                    comment(agentRegisterCommandComment(agent))
                }
                // Registered and switched off in the agent's own config. Same fix, different word: the
                // user is not missing a registration, theirs is turned off — and no `mcp list` mentions
                // it, which is why saying "Registered" here would be actively misleading.
                AgentRegistrationState.DISABLED -> {
                    registerButton(agent, "Enable", placeholder)
                    comment("registered, but switched off for this agent")
                }
                AgentRegistrationState.CLI_MISSING ->
                    comment("no <code>${agent.binary}</code> on your PATH — install the agent first")
                AgentRegistrationState.CHECK_FAILED -> {
                    registerButton(agent, "Register", placeholder)
                    comment("could not read the current state — see the IDE log")
                }
            }
        }
    }

    /**
     * The button behind every actionable state. One verb does all of them — `devrig install <agent>`
     * registers, repairs a stale entry, and switches a disabled one back on — so the label changes with
     * the situation while the action stays single.
     */
    private fun Row.registerButton(agent: AiAgentCli, label: String, placeholder: Placeholder) {
        button(label) {
            placeholder.component = agentRow(agent, AgentRegistrationState.CHECKING, placeholder)
            DevrigAgentRegistrationService.getInstance()
                .register(agent, ProjectManager.getInstance().openProjects.firstOrNull()) { result ->
                    updateAgentRow(placeholder, agent, result)
                }
        }
    }

    /** Replace one agent's cell with [state], on the EDT, unless this dialog is already gone. */
    private fun updateAgentRow(placeholder: Placeholder, agent: AiAgentCli, state: AgentRegistrationState) {
        onEdtEvenUnderThisDialog { placeholder.component = agentRow(agent, state, placeholder) }
    }

    /**
     * Run a UI update on the EDT **while the Settings dialog is still up**, and skip it if this page is
     * already gone.
     *
     * `ModalityState.any()` is the whole point. A plain `invokeLater` from a background thread inherits
     * `ModalityState.nonModal()`, so the platform holds the runnable until every modal dialog closes —
     * and Settings is modal. That is what left every agent row on "Checking…" forever: the check had
     * finished, its result simply could not reach the screen until the user closed the dialog. `any()` is
     * sanctioned for exactly this (updating UI that must refresh regardless of modality) as long as the
     * runnable touches nothing but its own components, which is all these do.
     */
    private fun onEdtEvenUnderThisDialog(update: () -> Unit) {
        val disposable = uiDisposable ?: return
        ApplicationManager.getApplication().invokeLater({
            // A CheckedDisposable knows its own state; Disposer.isDisposed(Disposable) is deprecated
            // precisely because asking the Disposer about an arbitrary disposable is the unreliable way.
            if (disposable.isDisposed) return@invokeLater
            update()
        }, ModalityState.any())
    }

    /**
     * Swap the install block for one built from the current state. Called on [DEVRIG_STATE_CHANGED],
     * which fires when an install finishes — the moment this page would otherwise keep offering to
     * install something that is already there.
     */
    private fun refreshInstallStatus() {
        val placeholder = installStatus ?: return
        onEdtEvenUnderThisDialog {
            if (installStatus !== placeholder) return@onEdtEvenUnderThisDialog   // panel rebuilt meanwhile
            placeholder.component = installStatusPanel(DevrigConnectionStateService.getInstance().localState())
        }
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
                    comment(
                        "The server normally starts at IDE startup. Check the IDE log (Help | Show Log in Finder/Explorer) " +
                            "for bind errors, or adjust the registry keys below."
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
     * for, and being a text component it also lets a version string be selected and copied by hand.
     *
     * isEditable stays true so the background paints as a normal field (a disabled one greys out and reads
     * as broken); the DocumentFilter is what actually makes it read-only.
     */
    private fun valueTextField(content: String): ExtendableTextField =
        ExtendableTextField().apply {
            text = content
            (document as? AbstractDocument)?.documentFilter = object : DocumentFilter() {
                override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {}
                override fun remove(fb: FilterBypass, offset: Int, length: Int) {}
                override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {}
            }
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
         * Width of every short status field in the devrig block ("Installed — version X", "Registered",
         * "Checking…"), in text-field columns. One shared constant, because these fields sit in adjacent
         * rows and must read as one column of answers: the installed-state field used to be AlignX.FILL
         * and spanned the entire page, dwarfing the agent value areas right below it (owner click-testing
         * feedback). COLUMNS_MEDIUM is the DSL's own standard value-field width; long copyable content
         * (URLs, command lines) keeps AlignX.FILL because it exists to be read in full.
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

    }
}

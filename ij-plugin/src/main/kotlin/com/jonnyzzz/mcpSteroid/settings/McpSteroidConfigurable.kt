/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.onboarding.AgentRegistrationState
import com.jonnyzzz.mcpSteroid.onboarding.DEVRIG_STATE_CHANGED
import com.jonnyzzz.mcpSteroid.onboarding.DevrigAgentRegistrationService
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionState
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionStateService
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner
import com.jonnyzzz.mcpSteroid.onboarding.DevrigStateListener
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import java.awt.datatransfer.StringSelection
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

/**
 * Application-level settings page: Settings | Tools | Devrig — MCP Steroid.
 *
 * Purely informational — no persistent state, no mutable options. The page exists so users
 * can confirm the plugin is installed and connect an AI agent:
 *
 * 1. Promotes the devrig CLI setup (the recommended path): install it from here or by hand, then
 *    `devrig install claude|codex|gemini` to register an agent. This page is the only place the
 *    plugin offers the install — see [com.jonnyzzz.mcpSteroid.onboarding.devrigWidgetEnabled].
 * 2. Shows the live MCP/HTTP server status (port + URL) — a cheap [SteroidsMcpServer.port]
 *    read from an in-memory atomic, no background work on the settings thread.
 * 3. Keeps the legacy direct-HTTP connection info (per-agent `mcp add` commands, generic
 *    `mcpServers` JSON, registry keys) so pre-devrig HTTP setups can still find their config.
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
    private var uiDisposable: Disposable? = null

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
        val disposable = Disposer.newDisposable("McpSteroidConfigurable")
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = disposable
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(DEVRIG_STATE_CHANGED, DevrigStateListener { refreshInstallStatus() })

        return panel {
            // One line. The five-line version of this pitch used to be the most prominent thing on a
            // settings page, above every actual control — which is backwards for a page you open to do
            // something. What it says lives on the website; the link is one row down.
            row {
                text(
                    "<b>AI Agents work inside your IDE — not just over your files.</b> Claude, Codex, " +
                        "Gemini and any MCP-compatible agent drive the full IntelliJ Platform through " +
                        "MCP Steroid."
                )
            }
            row {
                browserLink("Report issues on GitHub", FEEDBACK_URL)
            }

            group("Status") {
                if (info != null) {
                    row("MCP server:") {
                        label("Running on port $port")
                    }
                    row("Server URL:") {
                        cell(copyableTextField(info.serverUrl)).align(AlignX.FILL)
                    }
                } else {
                    row("MCP server:") {
                        label("Not running")
                    }
                    row {
                        comment(
                            "The server normally starts at IDE startup. Check the IDE log (Help | Show Log in Finder/Explorer) " +
                                "for bind errors, or adjust the registry keys listed below."
                        )
                    }
                }
            }

            // Two ways to connect, one per tab, in the order we recommend them. They were stacked
            // groups before, which read as "do both" and buried the recommended path under the one we
            // are steering people away from.
            row {
                cell(
                    JBTabbedPane().apply {
                        addTab("Devrig — recommended", devrigTab(devrigState))
                        addTab("Direct HTTP", httpTab(portPhrase, info))
                    }
                ).align(Align.FILL)
            }.topGap(TopGap.SMALL).resizableRow()
        }
    }

    /**
     * The recommended path. This is the one page with room to say *why* installing a separate binary is
     * worth it, which is exactly why the offer lives here rather than in a balloon that has three lines
     * and ten seconds.
     *
     * The button appears only when devrig is missing — there is nothing to offer someone who already has
     * it, and a permanently present "install" button on a settings page reads as an unfinished setup.
     */
    private fun devrigTab(state: DevrigConnectionState): DialogPanel = panel {
        // One line, then a link. The long version of this pitch is what made the page unreadable: by the
        // time a user got to something clickable they had read a dozen lines of prose.
        row {
            text(
                "One bridge between your agent and <b>every</b> IntelliJ IDE you have open. It survives " +
                    "IDE restarts and port changes, and can even start an IDE on demand for headless runs."
            )
        }
        row {
            browserLink("What is devrig?", DEVRIG_DOCS_URL)
        }

        // A placeholder, not a plain row: an install takes minutes and finishes while this page is open,
        // so the block has to be replaceable in place. Rebuilt from DEVRIG_STATE_CHANGED.
        row {
            installStatus = placeholder()
        }.topGap(TopGap.SMALL)
        installStatus?.component = installStatusPanel(state)

        // Collapsed: everyone who wanted the button already pressed it, and the one-liners are for the
        // minority who would rather paste them into a terminal (or update a devrig the button cannot see).
        collapsibleGroup("Install or update devrig by hand") {
            row("macOS / Linux:") {
                cell(copyableTextField(DEVRIG_INSTALL_SH)).align(AlignX.FILL)
            }
            row("Windows (PowerShell):") {
                cell(copyableTextField(DEVRIG_INSTALL_PS1)).align(AlignX.FILL)
            }
            row {
                comment(
                    "Exactly what the button runs. Re-running either one is also how you update devrig. " +
                        "The agent rows above are equivalent to <code>devrig install claude</code> " +
                        "(or <code>codex</code> / <code>gemini</code>) in a terminal."
                )
            }
        }.topGap(TopGap.SMALL)
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
                label("Installed" + (state.installedVersion?.let { " — version $it" } ?: ""))
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
                comment(
                    "Downloads about 611 MB (a pinned JDK plus devrig) into <code>~/.mcp-steroid</code> and " +
                        "puts <code>devrig</code> on your PATH. It registers nothing with your agents — " +
                        "that is the next step, and it appears here once devrig is in place."
                )
            }
        }
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
                AgentRegistrationState.CHECKING -> label("Checking…")
                AgentRegistrationState.REGISTERED -> label("Registered")
                AgentRegistrationState.NOT_REGISTERED -> {
                    button("Register") {
                        placeholder.component = agentRow(agent, AgentRegistrationState.CHECKING, placeholder)
                        DevrigAgentRegistrationService.getInstance()
                            .register(agent, ProjectManager.getInstance().openProjects.firstOrNull()) { result ->
                                updateAgentRow(placeholder, agent, result)
                            }
                    }
                    comment("runs <code>devrig install ${agent.binary}</code>")
                }
                AgentRegistrationState.CLI_MISSING ->
                    comment("no <code>${agent.binary}</code> on your PATH — install the agent first")
                AgentRegistrationState.CHECK_FAILED -> {
                    button("Register") {
                        placeholder.component = agentRow(agent, AgentRegistrationState.CHECKING, placeholder)
                        DevrigAgentRegistrationService.getInstance()
                            .register(agent, ProjectManager.getInstance().openProjects.firstOrNull()) { result ->
                                updateAgentRow(placeholder, agent, result)
                            }
                    }
                    comment("could not read the current state — see the IDE log")
                }
            }
        }
    }

    /** Replace one agent's cell with [state], on the EDT, unless this dialog is already gone. */
    private fun updateAgentRow(placeholder: Placeholder, agent: AiAgentCli, state: AgentRegistrationState) {
        val disposable = uiDisposable ?: return
        ApplicationManager.getApplication().invokeLater {
            if (Disposer.isDisposed(disposable)) return@invokeLater
            placeholder.component = agentRow(agent, state, placeholder)
        }
    }

    /**
     * Swap the install block for one built from the current state. Called on [DEVRIG_STATE_CHANGED],
     * which fires when an install finishes — the moment this page would otherwise keep offering to
     * install something that is already there.
     */
    private fun refreshInstallStatus() {
        val placeholder = installStatus ?: return
        ApplicationManager.getApplication().invokeLater {
            if (installStatus !== placeholder) return@invokeLater   // panel rebuilt meanwhile
            placeholder.component = installStatusPanel(DevrigConnectionStateService.getInstance().localState())
        }
    }

    /** The manual, single-IDE path. Kept intact so pre-devrig setups can still find their config. */
    private fun httpTab(portPhrase: String, info: McpConnectionInfo?): DialogPanel = panel {
        row {
            icon(AllIcons.General.Warning)
            text(
                "<b>Not recommended.</b> These manual HTTP commands point at <b>this</b> IDE " +
                    "$portPhrase — they stop working when the IDE restarts or that port is reassigned, " +
                    "and every agent must be set up by hand. Use devrig instead: it reaches every running " +
                    "IDE automatically and keeps working across restarts and port changes."
            )
        }
        row {
            text("If you still want a direct streamable-HTTP connection to this single IDE instance:")
        }.topGap(TopGap.SMALL)
        if (info != null) {
            for ((name, command) in info.commands) {
                row("$name:") {
                    cell(copyableTextField(command)).align(AlignX.FILL)
                }
            }
            collapsibleGroup("JSON Config") {
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
                    button("Copy JSON Config") {
                        CopyPasteManager.getInstance().setContents(StringSelection(json))
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

    /**
     * Read-only text field with an in-border copy-to-clipboard icon.
     * Keep isEditable=true so the background paints normally and the copy icon appears
     * visually INSIDE the field border (same as Terminal env vars fields); the
     * DocumentFilter silently blocks any edits by the user.
     */
    private fun copyableTextField(content: String): ExtendableTextField =
        ExtendableTextField().apply {
            text = content
            (document as? AbstractDocument)?.documentFilter = object : DocumentFilter() {
                override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {}
                override fun remove(fb: FilterBypass, offset: Int, length: Int) {}
                override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {}
            }
            addExtension(ExtendableTextComponent.Extension.create(
                AllIcons.General.InlineCopy,
                AllIcons.General.InlineCopyHover,
                "Copy to clipboard"
            ) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            })
        }

    companion object {
        /** Must match the id attribute of the applicationConfigurable EP in plugin.xml. */
        const val CONFIGURABLE_ID = "com.jonnyzzz.mcp-steroid.settings"

        /** Must match the displayName attribute of the applicationConfigurable EP in plugin.xml. */
        const val DISPLAY_NAME = "Devrig — MCP Steroid"

        const val DEVRIG_DOCS_URL = "https://devrig.dev/docs/devrig/"

        /** One-line devrig installers (served from the website). Shown copyable on the settings page. */
        const val DEVRIG_INSTALL_SH = "curl -fsSL https://devrig.dev/install.sh | sh"
        const val DEVRIG_INSTALL_PS1 = "irm https://devrig.dev/install.ps1 | iex"

        const val FEEDBACK_URL = "https://github.com/jonnyzzz/mcp-steroid/issues"

    }
}

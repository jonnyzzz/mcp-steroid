/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionState
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionStateService
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner
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

        return panel {
            row {
                text(
                    "<b>AI Agents work inside your IDE — not just over your files.</b> Claude, Codex, Gemini, " +
                        "and any MCP-compatible agent connect to MCP Steroid and drive the full IntelliJ " +
                        "Platform: they run Kotlin against the live IDE, navigate the PSI, run inspections, " +
                        "refactorings, the debugger and tests — and even <i>see</i> the IDE through screenshots. " +
                        "Your agent gets the whole IntelliJ, not just the text."
                )
            }
            row {
                browserLink("Report issues on GitHub", FEEDBACK_URL)
            }.topGap(TopGap.SMALL)

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
                        addTab("Devrig — recommended", devrigTab(devrigState, portPhrase))
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
    private fun devrigTab(state: DevrigConnectionState, portPhrase: String): DialogPanel = panel {
        row {
            text(
                "<b>Devrig</b> is one small command-line bridge between your AI Agent and your IDEs. " +
                    "Point your agent at it once and it reaches <b>every</b> IntelliJ-family IDE you " +
                    "have open — across projects — and routes each call to the right one. It keeps " +
                    "working when the IDE restarts or the port changes, and it can even download and " +
                    "start an IDE on demand for headless and CI runs."
            )
        }
        row {
            text(
                "Direct HTTP (the other tab) also works, but it is tied to <b>this</b> IDE " +
                    "$portPhrase — that changes when the IDE restarts or the port is taken, and every " +
                    "agent must be wired up by hand. Devrig handles all of that for you."
            )
        }

        if (state.devrigInstalled) {
            row("devrig:") {
                label("Installed" + (state.installedVersion?.let { " — version $it" } ?: ""))
            }.topGap(TopGap.SMALL)
        } else {
            row {
                text("<b>devrig is not installed yet.</b>")
            }.topGap(TopGap.SMALL)
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
                        "that is the separate step below."
                )
            }
        }

        row {
            text("Prefer to run it yourself? These are the same installers:")
        }.topGap(TopGap.SMALL)
        row("macOS / Linux:") {
            cell(copyableTextField(DEVRIG_INSTALL_SH)).align(AlignX.FILL)
        }
        row("Windows (PowerShell):") {
            cell(copyableTextField(DEVRIG_INSTALL_PS1)).align(AlignX.FILL)
        }
        row {
            comment(
                "Then point your agent at it: <code>devrig install claude</code> " +
                    "(or <code>codex</code> / <code>gemini</code>)."
            )
        }
        row {
            browserLink("Read the Devrig documentation to get started", DEVRIG_DOCS_URL)
        }.topGap(TopGap.SMALL)
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

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import java.awt.datatransfer.StringSelection
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

/**
 * Application-level settings page: Settings | Tools | MCP Steroid — devrig.
 *
 * Purely informational — no persistent state, no mutable options. The page exists so users
 * can confirm the plugin is installed and connect an AI agent:
 *
 * 1. Promotes the devrig CLI setup (the recommended path): one-command install plus
 *    `devrig install claude|codex|gemini` agent registration.
 * 2. Shows the live MCP/HTTP server status (port + URL) — a cheap [SteroidsMcpServer.port]
 *    read from an in-memory atomic, never any I/O or background work on the settings thread.
 * 3. Keeps the legacy direct-HTTP connection info (per-agent `mcp add` commands, generic
 *    `mcpServers` JSON, registry keys) so pre-devrig HTTP setups can still find their config.
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

            group("Devrig — the recommended way to connect") {
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
                        "The direct HTTP server below also works, but it is tied to <b>this</b> IDE on " +
                            "<b>this</b> port — it moves when 6315 is busy or the IDE restarts, and every agent " +
                            "must be wired up by hand. Devrig handles all of that for you, so it is the way we " +
                            "recommend connecting."
                    )
                }
                row {
                    browserLink("Read the Devrig documentation to get started", DEVRIG_DOCS_URL)
                }.topGap(TopGap.SMALL)
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

            group("Legacy HTTP Configuration") {
                row {
                    icon(AllIcons.General.Warning)
                    text(
                        "<b>Not recommended.</b> These manual HTTP commands point at <b>this</b> IDE on " +
                            "<b>this</b> port — they break when port 6315 is busy, the IDE restarts, or you open " +
                            "another IDE. Use devrig instead: it connects to every running IDE automatically and " +
                            "keeps working across restarts and port changes."
                    )
                }
                row {
                    browserLink("Set up devrig instead", DEVRIG_DOCS_URL)
                }.topGap(TopGap.NONE)
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
                            val textArea = JBTextArea(json).apply {
                                isEditable = false
                                rows = 6
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
                            "<code>mcp.steroid.server.port</code> (default 6315, 0 = auto-assign) and " +
                            "<code>mcp.steroid.server.host</code> (default 127.0.0.1)."
                    )
                }
            }

            row {
                browserLink("Report issues, Join Slack & Community", FEEDBACK_URL)
            }.topGap(TopGap.SMALL)
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
        const val DISPLAY_NAME = "MCP Steroid — devrig"

        const val DEVRIG_DOCS_URL = "https://mcp-steroid.jonnyzzz.com/docs/devrig/"

        const val FEEDBACK_URL = "https://mcp-steroid.jonnyzzz.com"
    }
}

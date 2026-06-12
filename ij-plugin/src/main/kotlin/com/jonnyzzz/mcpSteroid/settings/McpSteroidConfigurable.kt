/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.icons.AllIcons
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
        val server = SteroidsMcpServer.getInstance()
        val port = server.port
        val info = if (port > 0) McpConnectionInfo.build(server.mcpUrl) else null

        return panel {
            row {
                text(
                    "AI agents (Claude, Codex, Gemini, …) drive this IDE through the Model Context Protocol: " +
                        "MCP Steroid runs an MCP server inside the IDE, and agents call its tools to execute " +
                        "Kotlin against the full IntelliJ Platform API."
                )
            }

            group("Devrig CLI (Recommended)") {
                row {
                    text(
                        "Devrig registers MCP Steroid with your coding agent and bridges it to every running " +
                            "IDE at once — no manual MCP configuration. Install it with one command:"
                    )
                }
                row("Install:") {
                    cell(copyableTextField(DEVRIG_INSTALL_COMMAND)).align(AlignX.FILL)
                }
                row {
                    comment("Then register devrig as the <code>mcp-steroid</code> MCP server in your agent:")
                }
                row("Claude:") {
                    cell(copyableTextField("devrig install claude")).align(AlignX.FILL)
                }
                row("Codex:") {
                    cell(copyableTextField("devrig install codex")).align(AlignX.FILL)
                }
                row("Gemini:") {
                    cell(copyableTextField("devrig install gemini")).align(AlignX.FILL)
                }
                row {
                    browserLink("Devrig documentation", DOCS_URL)
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
                            "The server normally starts at IDE startup. Check the IDE log (Help | Show Log) " +
                                "for bind errors, or adjust the registry keys listed below."
                        )
                    }
                }
            }

            group("Legacy HTTP Configuration") {
                row {
                    text(
                        "Direct streamable-HTTP connection to this single IDE instance — the pre-devrig setup. " +
                            "Prefer devrig above: it survives IDE restarts, port changes, and routes to every IDE."
                    )
                }
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

        const val DEVRIG_INSTALL_COMMAND = "curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh"

        const val DOCS_URL = "https://mcp-steroid.jonnyzzz.com/docs/"

        const val FEEDBACK_URL = "https://mcp-steroid.jonnyzzz.com"
    }
}

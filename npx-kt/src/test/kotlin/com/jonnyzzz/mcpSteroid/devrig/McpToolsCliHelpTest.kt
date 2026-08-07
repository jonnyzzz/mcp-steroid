/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "MCP tools as CLI" reference `devrig tools` prints is GENERATED from each tool's own
 * declaration — its [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec] and the parameters `asCliParams()`
 * exposes — and never hand-written (PR #272 review r3579479002: "re-use information from the MCP tools to
 * generate these texts").
 *
 * Two claims are under test and they pull in opposite directions, which is why both are needed:
 *  - the *set* of what is shown is derived (a ninth tool, or a ninth parameter, appears with no edit here),
 *    asserted by reading the same specs the renderer reads;
 *  - the *shape* it is rendered in is pinned whole, because a wording or an alignment nobody asserts is a
 *    wording that silently rots. Three defects on this branch survived substring assertions.
 */
class McpToolsCliHelpTest {
    @TempDir
    lateinit var testHome: Path

    private fun section(): String = renderMcpToolsCliSection(devrigCliTools())

    private fun globalHelp(): String {
        val invocation = parseDevrigCommand(arrayOf("--help"))
        assertEquals("help", invocation.commandPath)
        return requireNotNull(invocation.informationalText).trimEnd() + "\n"
    }

    private fun toolsCommandOutput(): String {
        val invocation = parseDevrigCommand(arrayOf("tools"))
        assertEquals("devrig tools", invocation.commandPath)
        val run = runCliForToolTest(testHome, invocation)
        assertEquals(0, run.exit, "devrig tools must succeed; stderr:\n${run.stderr}")
        return run.stdout
    }

    private fun visibleTools() = devrigCliTools().filterNot { it.cli.hidden }

    /**
     * The block the section devotes to one command. Blocks are separated by a blank line, and looking one
     * up by command matters: `--code` belongs to two different tools with two different synopses, so a
     * section-wide search would happily confirm the wrong one.
     */
    private fun blockOf(command: String): String = section().split("\n\n")
        .first { it.startsWith("  devrig $command ") || it.startsWith("  devrig $command\n") }

    /** The detail line a block devotes to [label], or null; labels are padded, hence the trailing space. */
    private fun String.detailLineFor(label: String): String? =
        lines().firstOrNull { it.startsWith("        $label ") }

    @Test
    fun `the section shows exactly the visible tools of devrigToolSpecs, in factory order`() {
        val rendered = section().lines()
            .filter { it.startsWith("  devrig ") }
            .map { it.removePrefix("  devrig ").substringBefore(' ') }

        assertEquals(visibleTools().map { it.cli.name }, rendered, "the shown tool set must be the canonical one")
    }

    @Test
    fun `every tool block carries its own declared command synopsis`() {
        for (tool in visibleTools()) {
            // Looked up inside the tool's OWN block: a section-wide search would confirm a synopsis
            // rendered under the wrong tool, and would pick the wrong line if two tools ever shared one.
            val block = blockOf(tool.cli.name)
            assertEquals(
                "      ${tool.cli.synopsis}",
                block.lines().firstOrNull { it.trim() == tool.cli.synopsis },
                "the synopsis line of ${tool.cli.name} must be its declared cliSynopsis, indented six:\n$block",
            )
        }
    }

    @Test
    fun `every visible parameter contributes one line carrying its own declared cliSynopsis`() {
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (param in tool.schema.asCliParams().filterNot { it.cliHidden }) {
                val label = if (param.cliPositional) "<${param.name}>" else param.cliFlag
                val line = block.detailLineFor(label)
                assertNotNull(line, "no help line for $label of ${tool.cli.name}:\n$block")
                assertEquals(
                    param.cliSynopsis,
                    line.substringAfter("        $label ").trim(),
                    "$label of ${tool.cli.name} must be described by its declared cliSynopsis",
                )
            }
        }
    }

    @Test
    fun `a declared file source renders from its own declaration, with no hand-written text`() {
        var seen = 0
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (source in tool.schema.asCliParams().mapNotNull { it.cliFileSource }) {
                seen++
                val line = block.detailLineFor(source.flag)
                assertNotNull(line, "no help line for the declared file source ${source.flag}:\n$block")
                assertEquals(source.synopsis, line.substringAfter("        ${source.flag} ").trim())
            }
        }
        assertTrue(seen > 0, "no tool declares a cliFileSource, so this test proves nothing")
    }

    @Test
    fun `a declared tool-level extra option renders from its own declaration, with no hand-written text`() {
        var seen = 0
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (extra in tool.cli.extraOptions) {
                seen++
                val line = block.detailLineFor(extra.flag)
                assertNotNull(line, "no help line for the declared extra option ${extra.flag}:\n$block")
                assertEquals(extra.synopsis, line.substringAfter("        ${extra.flag} ").trim())
            }
        }
        assertTrue(seen > 0, "no tool declares a CliExtraOption, so this test proves nothing")
    }

    @Test
    fun `a tool with no parameters renders its usage line alone`() {
        assertTrue(
            "  devrig list_windows\n      list IDE windows, readiness, and background tasks\n" in section(),
            "list_windows declares no parameter or alias, so its block is the usage line plus the synopsis:\n${section()}",
        )
    }

    @Test
    fun `the usage line spells each parameter by its declared shape, wrapped at the help width`() {
        // execute_code covers every shape at once: a plain required parameter (project_name — un-bracketed,
        // the parser demands it), a required value reachable two ways (code / --code-file), plain required
        // values, an optional number, an enum, and — because its result can carry an image — the framework
        // [--out=<path>] flag, which non-image commands do not render.
        val expected =
            "  devrig execute_code --project_name=<project_name> (--code=<code> | --code-file=<path>)\n" +
                "                      --task_id=<task_id> --reason=<reason> [--timeout=<timeout>]\n" +
                "                      [--modal=<smart_non_modal | non_modal | unleashed>] [--out=<path>]\n"

        assertTrue(expected in section(), "execute_code's usage line must render every declared shape:\n${section()}")
    }

    @Test
    fun `a boolean switch, an optional flag and a tool-level extra all reach the usage line`() {
        // The boolean renders as the pair `--trust_project / --no-trust_project`: `false` is reachable
        // ONLY through the negative spelling, and a banner that named only `--trust_project` hid that half.
        assertTrue(
            "  devrig open_project --project_path=<project_path> --task_id=<task_id> --reason=<reason>\n" +
                "                      [--trust_project / --no-trust_project] [--backend_name=<backend_name>]\n" +
                "                      [--wait]\n" in section(),
            "open_project's usage line must render its boolean as a pair, its optional flag and --wait:\n${section()}",
        )
    }

    @Test
    fun `a boolean switch advertises its negative spelling in the banner`() {
        // Regression A3: `--no-trust_project` is the only way to set the switch false, so the global banner
        // — not just the per-command --help — must name it. The usage line carries the pair as one wrapped
        // token; the per-flag detail column stays the bare `--trust_project` so the pair's width does not
        // stretch the alignment column and push a long synopsis past HELP_WIDTH.
        val block = blockOf("open_project")
        assertTrue(
            "[--trust_project / --no-trust_project]" in block,
            "open_project's usage line must advertise the negative spelling of its boolean switch:\n$block",
        )
        assertNotNull(
            block.detailLineFor("--trust_project"),
            "the per-flag detail line stays the bare flag, aligned with its siblings:\n$block",
        )
    }

    @Test
    fun `a tool's declared aliases trail its usage line`() {
        assertTrue(
            "  devrig list_projects (aliases: projects, project)\n" in section(),
            "list_projects must advertise its declared plural and legacy singular aliases:\n${section()}",
        )
        assertTrue(
            "  devrig fetch_resource <uri> --project_name=<project_name> (alias: prompt)\n" in section(),
            "fetch_resource must advertise its declared `prompt` alias:\n${section()}",
        )
    }

    @Test
    fun `the footer is exactly the framework-level facts that belong to no parameter`() {
        // Three headings, because the scopes are genuinely different: `--debug` reaches every command,
        // `--json` reaches only commands that advertise structured output, while `--out` is registered on
        // DevrigToolCliktCommand and reaches only the tool commands whose result can carry an image
        // (execute_code, take_screenshot — the CliCommandSpec.producesImage set). One heading over all three
        // is what let `devrig list_projects --out=/tmp/x.png` be advertised, parse, and do nothing; a heading over
        // every tool command is what let `devrig list_projects --out=x` fail 65 after a pointless call.
        val expected =
            "  Global CLI flag (accepted by every command, tool and lifecycle alike):\n" +
                "    --debug       $DEVRIG_DEBUG_FLAG_HELP\n" +
                "  Accepted by commands that advertise structured output:\n" +
                "    --json        $DEVRIG_JSON_FLAG_HELP\n" +
                "  Accepted only by execute_code, take_screenshot — the commands whose result carries an image:\n" +
                "    --out=<path>  $DEVRIG_OUT_FLAG_HELP\n" +
                "    Run `devrig <command> --help` for one command's full option list.\n"

        assertEquals(expected, section().substring(section().indexOf("  Global CLI flag")))
    }

    @Test
    fun `the footer promises no cwd inference, because nothing infers project_name`() {
        // The footer used to state "--project_name is inferred from the current directory when omitted."
        // Nothing performs that inference: `resolveProjectFromCwd` has no production caller, so the flag is
        // simply mandatory — `devrig execute_code` without `--project_name` fails at PARSE time, which is
        // why the usage line renders the flag un-bracketed.
        //
        // Both halves are asserted together on purpose. The first alone would be a wording pin; the
        // second pins the BEHAVIOUR the sentence described, so whoever implements the inference breaks
        // this test and has to restore the sentence in the same commit — which is exactly the coupling
        // whose absence let the help and the runtime drift apart.
        assertFalse(
            "inferred from the current directory" in section(),
            "the footer must not promise an inference the CLI does not perform:\n${section()}",
        )

        val command = parseDevrigCommand(arrayOf("execute_code", "--code=x", "--task_id=t", "--reason=r"))
        assertEquals(
            "parse-error",
            command.commandPath,
            "nothing fills project_name from the cwd today, so the parser must demand it; if that changed, " +
                "restore the footer line documenting the inference. Got: $command",
        )
    }

    @Test
    fun `each shared framework-flag help string is pinned literally`() {
        // The footer pin above interpolates these constants, so it stays green through any rewording of
        // them. That is the right call for the layout assertion — but it leaves the WORDING unpinned, and
        // the wording is what carried the defect: the banner used to name a `DEBUG` env var that has never
        // existed, while the only variable the code reads (`System.getenv("DEVRIG_DEBUG")`) is DEVRIG_DEBUG.
        assertEquals(
            "enable verbose stderr logging (also enabled by the DEVRIG_DEBUG env var)",
            DEVRIG_DEBUG_FLAG_HELP,
        )
        assertEquals("emit one machine-readable JSON document where supported", DEVRIG_JSON_FLAG_HELP)
        assertEquals(
            "write the image the command returns to this path instead of the devrig temp dir",
            DEVRIG_OUT_FLAG_HELP,
        )
    }

    @Test
    fun `the framework flags are documented under exactly one heading per surface`() {
        // Root help documents --debug and --json once each, in its own Options list; --out is registered
        // only on image-producing tool commands, so the root banner must not mention it at all. The
        // `devrig tools` reference documents each of the three exactly once, in the scoped footer.
        val documented = { text: String, flag: String ->
            // A line that *documents* the flag opens with it; the flag may carry a metavar (`--out=<path>`).
            text.lines().count { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith(flag) && (trimmed.length == flag.length || trimmed[flag.length] in " =")
            }
        }
        val help = globalHelp()
        for ((flag, expectedCount) in mapOf("--debug" to 1, "--json" to 1, "--out" to 0)) {
            assertEquals(expectedCount, documented(help, flag), "$flag in the root banner:\n$help")
        }
        val reference = toolsCommandOutput()
        for ((flag, expectedCount) in mapOf("--debug" to 1, "--json" to 1, "--out" to 1)) {
            assertEquals(expectedCount, documented(reference, flag), "$flag in the tools reference:\n$reference")
        }
        assertFalse(
            "Options applicable to every mode:" in help,
            "the old heading incorrectly implied that --json and --out share --debug's universal scope:\n$help",
        )
    }

    @Test
    fun `the footer documents no flag that a tool declares for itself`() {
        val footer = section().substringAfter("  Global CLI flag")
        for (hidden in listOf("--code-file", "--wait")) {
            assertFalse(
                hidden in footer,
                "$hidden is declared on its tool and must render from that declaration, not from the footer:\n$footer",
            )
        }
    }

    @Test
    fun `no rendered line exceeds the help width`() {
        val tooWide = section().lines().filter { it.length > 100 }
        assertEquals(emptyList(), tooWide, "generated help lines must stay within 100 columns")
    }

    @Test
    fun `devrig tools prints the generated section verbatim`() {
        assertTrue(
            section() in toolsCommandOutput(),
            "`devrig tools` must print the generated section:\n${toolsCommandOutput()}",
        )
    }

    @Test
    fun `devrig tools rejects --json, because the reference is prose`() {
        assertEquals("parse-error", parseDevrigCommand(arrayOf("tools", "--json")).commandPath)
    }

    @Test
    fun `root help stays an index and points at the tools reference`() {
        val help = globalHelp()
        for (marker in listOf(
            "Usage: devrig",
            "Commands:",
            "backend",
            "install",
            "devrig tools",
            "DEVRIG_JVM_OPTS",
        )) {
            assertTrue(marker in help, "root help lost '$marker':\n$help")
        }
        assertFalse(
            "MCP tools as CLI" in help,
            "the per-tool reference lives in `devrig tools`; embedding it buried the command index:\n$help",
        )
        assertFalse("devrig mpc" in help, "the hidden mpc alias must stay unadvertised:\n$help")
    }

    @Test
    fun `focused execute_code help renders every declared guide and a copyable fetch route`() {
        val spec = visibleTools().single { it.cli.name == "execute_code" }
        val invocation = parseDevrigCommand(arrayOf("help", "execute_code"))
        assertEquals("help", invocation.commandPath)
        val help = requireNotNull(invocation.informationalText)

        assertTrue("Guides for deeper workflows:" in help, help)
        for (uri in spec.cli.guideUris) assertTrue(uri in help, "focused help omitted $uri:\n$help")
        assertTrue(
            "devrig prompt <uri> --project_name=<routing-key>" in help,
            "focused help must lead directly from a guide URI to an executable CLI action:\n$help",
        )
    }

    @Test
    fun `root help comes from the executable Clikt tree rather than the old manual banner`() {
        val help = globalHelp()
        assertTrue(help.startsWith("Usage: devrig [<options>] <command> [<args>]..."), help)
        assertTrue("\nOptions:\n" in help, help)
        assertTrue("\nCommands:\n" in help, help)
        assertFalse(
            "\n  devrig backend [--json]" in help,
            "the removed hand-written lifecycle banner must not be appended to Clikt help:\n$help",
        )
    }
}

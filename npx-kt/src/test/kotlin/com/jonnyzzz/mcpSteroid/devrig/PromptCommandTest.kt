/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.ApplyPatchPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.TypeHierarchyPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * `devrig prompt` (issue #99): the CLI window into the `mcp-steroid://` prompt corpus.
 * Tests drive the pure [runPromptCommand] overload directly — no [DevrigServices], no IDE.
 */
class PromptCommandTest {

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runPrompt(arg: String?, context: PromptsContext? = null): Run {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val exitCode = runPromptCommand(
            arg = arg,
            out = PrintStream(outBuf, true, Charsets.UTF_8),
            err = PrintStream(errBuf, true, Charsets.UTF_8),
            context = context,
        )
        return Run(exitCode, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    // ------------------------------ parsing ---------------------------------

    @Test
    fun `prompt parses with and without an argument`() {
        val noArg = assertIs<DevrigCommand.DevrigCommandPrompt>(parseDevrigCommand(arrayOf("prompt")))
        assertEquals(null, noArg.uri)

        val path = assertIs<DevrigCommand.DevrigCommandPrompt>(parseDevrigCommand(arrayOf("prompt", "ide/apply-patch")))
        assertEquals("ide/apply-patch", path.uri)

        val fullUri = ApplyPatchPromptArticle().uri
        val uriForm = assertIs<DevrigCommand.DevrigCommandPrompt>(parseDevrigCommand(arrayOf("prompt", fullUri)))
        assertEquals(fullUri, uriForm.uri)

        val withFlags = assertIs<DevrigCommand.DevrigCommandPrompt>(
            parseDevrigCommand(arrayOf("--debug", "prompt", "ide/apply-patch")),
        )
        assertTrue(withFlags.debug)
        assertEquals("ide/apply-patch", withFlags.uri)

        assertIs<DevrigCommand.DevrigCommandParseError>(parseDevrigCommand(arrayOf("prompt", "a", "b")))
    }

    // ----------------------------- root index -------------------------------

    @Test
    fun `no-arg renders the root index with copy-pasteable URIs`() {
        val run = runPrompt(arg = null)

        assertEquals(0, run.exitCode)
        // The root render is the entry-point catalog: the skill index article labeled by its full
        // URI, plus the all-resources catalog where every article URI is copy-pasteable.
        assertContains(run.stdout, SkillPromptArticle().uri)
        assertContains(run.stdout, ApplyPatchPromptArticle().uri)
        assertContains(run.stdout, "server instructions")
        // Diagnostics (which context was used) stay on stderr, never stdout.
        assertContains(run.stderr, "no single routed IDE")
    }

    // --------------------------- resource fetch -----------------------------

    @Test
    fun `scheme-less path fetch returns the exact agent payload when a context is routed`() {
        val article = ApplyPatchPromptArticle()
        val context = PromptsContext(productCode = "IU", baselineVersion = 261)

        val run = runPrompt(article.uri.substringAfter("://"), context)

        assertEquals(0, run.exitCode)
        assertEquals(article.readPayload(context).trimEnd(), run.stdout.trimEnd())
    }

    @Test
    fun `full mcp-steroid uri works`() {
        val article = ApplyPatchPromptArticle()

        val run = runPrompt(article.uri)

        assertEquals(0, run.exitCode)
        assertContains(run.stdout, article.title.readPrompt())
        assertContains(run.stdout, article.description.readPrompt())
    }

    @Test
    fun `bare stem resolves when unambiguous`() {
        val article = ApplyPatchPromptArticle()
        val stem = article.uri.substringAfterLast('/')

        val run = runPrompt(stem)

        assertEquals(0, run.exitCode)
        assertContains(run.stdout, article.title.readPrompt())
    }

    @Test
    fun `ambiguous bare stem errors and lists every match`() {
        // Several folders publish an `overview` article (ide/, debugger/, lsp/, vcs/, …),
        // so the bare stem must refuse to guess and list the competing URIs instead.
        val run = runPrompt("overview")

        assertNotEquals(0, run.exitCode)
        assertContains(run.stderr, "Ambiguous")
        val listedUris = Regex("""\S+://\S+""").findAll(run.stderr).count()
        assertTrue(listedUris >= 2, "ambiguity report must list the competing URIs; got:\n${run.stderr}")
        assertEquals("", run.stdout, "stdout must stay clean on errors")
    }

    @Test
    fun `unknown path errors with same-folder candidates and a non-zero exit`() {
        val run = runPrompt("ide/definitely-not-a-recipe")

        assertEquals(PROMPT_NOT_FOUND_EXIT_CODE, run.exitCode)
        assertContains(run.stderr, "Unknown resource: ide/definitely-not-a-recipe")
        // Near-misses come from the same folder — the apply-patch recipe lives under ide/.
        assertContains(run.stderr, ApplyPatchPromptArticle().uri)
        assertEquals("", run.stdout, "stdout must stay clean on errors")
    }

    // ------------------------- neutral-context gates -------------------------

    @Test
    fun `neutral rendering annotates IDE-gated content instead of hiding it`() {
        // TypeHierarchy is gated to IU at the article level. Without a routed IDE the content must
        // still be shown — with the gate visibly annotated (issue #99) — never silently dropped.
        val article = TypeHierarchyPromptArticle()

        val rendered = renderArticleAnnotated(article)

        assertContains(rendered, "[gated:")
        assertContains(rendered, "IU")
        assertContains(rendered, article.title.readPrompt())

        val run = runPrompt(article.uri.substringAfter("://"))
        assertEquals(0, run.exitCode)
        assertContains(run.stdout, "[gated:")
    }

    @Test
    fun `explicitly requested article gated away from the routed IDE is still rendered, with a note`() {
        val article = TypeHierarchyPromptArticle()
        val riderContext = PromptsContext(productCode = "RD", baselineVersion = 261)

        val run = runPrompt(article.uri, riderContext)

        assertEquals(0, run.exitCode)
        assertContains(run.stdout, article.title.readPrompt())
        assertContains(run.stderr, "NOT served to agents")
    }

    // ------------------------------ catalog ---------------------------------

    @Test
    fun `catalog lists gated articles with their gate label`() {
        val catalog = renderResourceCatalog()

        assertContains(catalog, ApplyPatchPromptArticle().uri)
        assertContains(catalog, "${TypeHierarchyPromptArticle().uri} [gated: IU]")
    }

    // ------------------------------- help -----------------------------------

    @Test
    fun `help text advertises devrig prompt`() {
        val buf = ByteArrayOutputStream()
        printHelp(PrintStream(buf, true, Charsets.UTF_8))
        val help = buf.toString(Charsets.UTF_8)

        assertContains(help, "devrig prompt")
        assertFalse(help.contains("devrig prompt list"), "there is no separate list subcommand — the root index is the list")
    }
}

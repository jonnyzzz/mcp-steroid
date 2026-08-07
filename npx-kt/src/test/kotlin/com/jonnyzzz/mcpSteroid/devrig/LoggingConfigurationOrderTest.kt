/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * jonnyzzz/mcp-steroid#462 — logback configures itself on the FIRST `LoggerFactory.getLogger` call and
 * pins every `${...}` substitution in `logback.xml` at that moment. Command-tree construction makes
 * that call: building the schema-driven tool commands constructs `FetchResourceToolHandler`, which
 * holds a logger field (confirmed by class-load order — it is the last devrig class loaded before
 * `org.slf4j.LoggerFactory`).
 *
 * So every property `logback.xml` reads MUST be published before the parser runs. Setting them after
 * it, as `configureLoggingAndLogStarted` did, pinned all four at their fallbacks: `--debug` printed
 * nothing to stderr, every process shared one `devrig-session.log` instead of its own per-pid file,
 * and every line read `[pid:?]`.
 *
 * The tests drive [runDevrigMain] with an injected parser — the seam [LastResortCrashHandlerTest]
 * uses — which snapshots the properties exactly where the real parser would run, then aborts so the
 * command never executes and the developer's real `~/.mcp-steroid` is never touched.
 *
 * Property names are spelled literally here on purpose: they are a contract with `logback.xml`, which
 * cannot import a constant. `logback-xml reads exactly the properties devrig publishes` pins both ends.
 */
class LoggingConfigurationOrderTest {

    private val logProperties = listOf("devrig.log.dir", "devrig.log.session", "devrig.pid", "devrig.log.level")

    /**
     * Runs [runDevrigMain] far enough to parse, and returns what the four properties looked like at
     * that instant. Restores them afterwards — they are JVM-global and this JVM runs other tests.
     */
    private fun propertiesAtParseTime(vararg rawArgs: String): Map<String, String?> {
        var snapshot: Map<String, String?>? = null
        val original = logProperties.associateWith { System.getProperty(it) }
        val originalErr = System.err
        try {
            // The deliberate abort below prints its trace; keep it out of the test log.
            System.setErr(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
            val exit = runDevrigMain(arrayOf(*rawArgs)) { _, _ ->
                snapshot = logProperties.associateWith { System.getProperty(it) }
                error("abort before the command runs")
            }
            assertEquals(CliExit.SOFTWARE, exit)
        } finally {
            System.setErr(originalErr)
            original.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
        return snapshot ?: fail("the injected parser was never called")
    }

    @Test
    fun `the log directory, session and pid are published before the command tree is parsed`() {
        val properties = propertiesAtParseTime("backend")
        val pid = ProcessHandle.current().pid()

        assertEquals(resolveHomePaths().logsDir.toString(), properties["devrig.log.dir"])
        assertEquals(pid.toString(), properties["devrig.pid"], "log lines must be attributable to a process")

        val session = properties["devrig.log.session"]
        assertNotNull(session, "without a session every devrig process writes the same devrig-session.log")
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2}-\d{6}-pid$pid$""").matches(session),
            "session must be <timestamp>-pid<pid> so a log monitor sees each process as a new file: $session",
        )
    }

    @Test
    fun `--debug raises the stderr level before the command tree is parsed`() {
        assertEquals("DEBUG", propertiesAtParseTime("backend", "--debug")["devrig.log.level"])
    }

    @Test
    fun `--debug is honoured wherever it sits in argv`() {
        assertEquals("DEBUG", propertiesAtParseTime("--debug", "backend", "list")["devrig.log.level"])
    }

    @Test
    fun `the level stays unset without a debug request so an external -D still wins`() {
        // DEVRIG_DEBUG is a documented way to ask for diagnostics, so honour it if this env has it set
        // rather than assuming a clean environment.
        val expected = if (System.getenv("DEVRIG_DEBUG").isNullOrBlank()) null else "DEBUG"
        assertEquals(expected, propertiesAtParseTime("backend")["devrig.log.level"])
    }

    @Test
    fun `logback-xml reads exactly the properties devrig publishes`() {
        val xml = javaClass.getResource("/logback.xml")?.readText()
            ?: fail("the bundled logback.xml is missing from the devrig resources")
        for (name in logProperties) {
            assertTrue("\${$name" in xml, "logback.xml must read $name — otherwise publishing it is dead code")
        }
    }
}

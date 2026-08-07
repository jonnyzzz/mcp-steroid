package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ofPattern

/**
 * Wire the `--debug` flag into the bundled logback configuration.
 * The `devrig.log.level` system property is read at logback-init time
 * (see `logback.xml`). The default level is WARN. With `--debug`, the level
 * is DEBUG.
 *
 * MUST run before the first SLF4J call — logback initialises lazily on first
 * use and pins the level. [main] calls this right after command parsing for
 * exactly that reason.
 */
fun applyDebugLogging(debug: Boolean) {
    // Only set the property when --debug is requested — leaving it unset lets
    // operators override the WARN default from the outside with
    // `-Ddevrig.log.level=INFO` etc. The hard-coded default in logback.xml
    // (`${devrig.log.level:-WARN}`) handles the no-flag case.
    if (debug) {
        System.setProperty("devrig.log.level", "DEBUG")
    }
}

private class DevrigLog

/**
 * Publish every substitution `logback.xml` reads — and do it before ANYTHING can touch SLF4J.
 *
 * logback configures itself on the first `LoggerFactory.getLogger` call and pins each `${...}` at that
 * moment. Command-tree construction makes that call long before a home is resolved: building the
 * schema-driven tool commands constructs `FetchResourceToolHandler`, which holds a logger field. Setting
 * the properties after parsing therefore set nothing at all — `--debug` printed no stderr, every process
 * shared one `devrig-session.log`, and every line read `[pid:?]` (jonnyzzz/mcp-steroid#462).
 *
 * Everything here is derived from argv and this process alone: pure path math, no validation, no I/O,
 * nothing that can fail or print. devrig's home is hardcoded and [resolveHomePaths] is the same pure
 * path math `resolveHomePathsOrDie` runs later, so the directory published here is exactly the one that
 * gets validated — the two cannot drift apart.
 *
 * Must stay the first statement of [runDevrigMain]. The regression nets are
 * `LoggingConfigurationOrderTest`, which snapshots these properties at parse time, and
 * `CliOptionsIntegrationTest`, which asserts `--debug` output and the per-pid file from the packaged CLI.
 */
fun configureLoggingSystemProperties(rawArgs: Array<String>) {
    val pid = ProcessHandle.current().pid()
    System.setProperty("devrig.log.dir", resolveHomePaths().logsDir.toString())
    // The PID is in BOTH the session (so every devrig process writes its OWN file — a log monitor detects
    // each as a new file) and the log-line pattern (so interleaved output is attributable to a process).
    System.setProperty("devrig.log.session", "${LocalDateTime.now().format(ofPattern("yyyy-MM-dd-HHmmss"))}-pid$pid")
    System.setProperty("devrig.pid", pid.toString())
    applyDebugLogging(rawArgs.debugRequested())
}

/**
 * Record the startup line, once a validated home exists.
 *
 * Deliberately takes no `debug`: [configureLoggingSystemProperties] already configured the level from
 * argv before logback initialised, and logback has long since pinned it — accepting a flag here and
 * "applying" it would be a no-op that lies. `--debug` on argv (and `DEVRIG_DEBUG`) is the one source of
 * truth for logging verbosity; `DevrigCliInvocation.debug` records the same request for the parser's own
 * consumers.
 */
fun configureLoggingAndLogStarted(homePaths: HomePaths, rawArgs: List<String>) {
    val log = logger<DevrigLog>()
    val pid = ProcessHandle.current().pid()
    // homePaths.home, not homePaths: the class has no toString(), so the line used to record
    // "HomePaths@1b8a29df" where the operator needs the actual directory.
    log.info("Starting Devrig ${DevrigVersionMetadata.getDevrigVersion()} (pid=$pid) with home: ${homePaths.home} and args: ${rawArgs.joinToString(" ")}")
}

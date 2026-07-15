package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.executeCodeGuideUris
import java.io.PrintStream

fun printVersion(out: PrintStream) : Int {
    out.println(DevrigVersionMetadata.getDevrigVersion())
    return 0
}

/**
 * Layered help: `devrig help <topic>` / `devrig <cmd> --help`. A [topic] gives a concise per-command
 * entry that ends with `devrig prompt <uri>` pointers so an agent drills only as deep as it needs;
 * an unknown/absent topic falls back to the global banner.
 */
fun printTopicHelp(topic: String?, out: PrintStream): Int = when (topic) {
    "execute_code" -> printExecuteCodeHelp(out)
    else -> printHelp(out)
}

/**
 * Concise entry point for `devrig execute_code` — the flags + the must-know rules for a first call,
 * then a "go deeper" list into the `mcp-steroid://` article graph (fetch with `devrig prompt <uri>`).
 * Deliberately short: the full reference stays fetch-on-demand, not inlined here.
 */
fun printExecuteCodeHelp(out: PrintStream): Int {
    out.print(
        """
        devrig execute_code — run a Kotlin script inside the target IDE (steroid_execute_code).

        Usage:
          devrig execute_code --project_name=<key> (--code-file=<path> | --code=<inline> | --code-file=-)
                              --task_id=<id> --reason=<text> [--modal=<mode>] [--timeout=<sec>] [--json]

        Required:
          --project_name   routing key from `devrig list_projects` (NOT the folder name)
          --code-file      path to a .kts file; pass "-" to read the script from stdin (blocks until EOF)
          --code           inline script (alternative to --code-file)
          --task_id        groups related calls in audit logs
          --reason         full task description

        Optional:
          --modal          smart_non_modal (default) | non_modal | unleashed
          --timeout        script timeout in seconds (default 600)
          --json           emit the unified {tool, command, isError, data} envelope

        Must know (first call):
          - the script is a Kotlin SUSPEND body — never use runBlocking
          - PSI reads go in readAction { }, writes in writeAction { }
          - nothing is auto-printed: end with println(...) / printJson(...) to see output
          - route by project_name (get it from `devrig list_projects`), not the folder name

        Go deeper — fetch only what you need:
        """.trimIndent() + "\n"
    )
    for ((uri, blurb) in executeCodeGuideUris()) {
        out.println("  devrig prompt $uri")
        out.println("      $blurb")
    }
    out.println()
    return 0
}

fun printHelp(out: PrintStream) : Int {
    out.print(
        """
        Usage:

          devrig mcp                     run as an MCP stdio server,
                                         register that setup in your coding agent

          devrig backend [--json]        list discovered backends (with versions) and the
                                         projects each one has open. `--json` emits a
                                         single machine-readable object on stdout
                                         (pipe through `jq`); default is human text.

          devrig project [--json]        list open projects across discovered backends.
                                         `--json` emits a single machine-readable
                                         object on stdout; default is human text.

          devrig install claude|codex|gemini [--check]
                                         register this devrig binary as the
                                         mcp-steroid stdio MCP server in the
                                         selected coding agent. `--check` is a
                                         read-only dry-run: it reports the current
                                         registration, the changes install would
                                         apply, and how many IDE backends with the
                                         MCP Steroid plugin are reachable; exits 1
                                         when install would change anything.

          devrig backend download [<id>] [--version <v>] [--json]
                                         no id → list IDEs available for download.
                                         With id, download and install a managed
                                         backend under the devrig home. Accepts
                                         <product>, <product>:<version>, or
                                         <product>-<version>.

          devrig backend start    [<id>] [--version <v>] [--json]
                                         no id → list installed backends. With id,
                                         start an installed managed backend in
                                         detached mode and print its pid/log/config
                                         paths. Product-only id prefers the
                                         highest locally installed backend.

          devrig backend stop     [<id>] [--version <v>] [--json]
                                         no id → list currently running backends.
                                         With id, stop a managed backend by pid file.
                                         Product-only id prefers the highest
                                         locally installed backend.

          devrig backend provision [<id>] [--json]
                                         no id → list port-discovered IDEs that can be
                                         provisioned. With id (for example port-63342),
                                         print manual MCP Steroid plugin install
                                         instructions for that IDE.

        MCP tools as CLI (same tools as the `devrig mcp` server, callable from the shell):

          devrig prompt <uri> [--project_name <key>]
                                         fetch a mcp-steroid:// guide by URI (steroid_fetch_resource).
                                         Works without a running IDE for bundled docs; pass
                                         --project_name for IDE-specific content.
          devrig fetch_resource --uri=<uri> [--project_name <key>]
                                         canonical form of `devrig prompt`.

          devrig execute_code --project_name=<key> --code-file=<path> --task_id=<id> --reason=<text>
                              [--code=<inline>] [--modal=<mode>] [--timeout=<sec>] [--json]
                                         run a Kotlin script in the IDE (steroid_execute_code).
                                         --code-file=- reads the script from stdin (blocks until EOF).

          devrig list_projects [--json]  list open projects (steroid_list_projects; shares
                                         output with `devrig project`). Exposes project_name,
                                         the routing key for the other commands.
          devrig list_windows  [--json]  list IDE windows + readiness + background tasks
                                         (steroid_list_windows).

          devrig open_project --project_path=<abs> --task_id=<id> --reason=<text>
                              [--backend_name=<id>] [--trust_project] [--wait] [--json]
                                         open a project (steroid_open_project); --wait polls until ready.

          devrig take_screenshot --project_name=<key> --task_id=<id> --reason=<text>
                              [--window_id=<win>] [--out=<file.png>] [--json]
                                         capture a screenshot (steroid_take_screenshot).
          devrig input --project_name=<key> --window_id=<win> --task_id=<id> --reason=<text>
                              --sequence=<steps> [--json]
                                         send keyboard/mouse input (steroid_input).
          devrig execute_feedback --project_name=<key> --task_id=<id> --success_rating=<0..1>
                              --explanation=<text> [--execution_id=<id>] [--code-file=<path>] [--json]
                                         rate an execution (steroid_execute_feedback).

          devrig --version | -v          print the devrig version and exit
          devrig --help    | -h          print this help and exit

        Options applicable to every mode:
          --debug                        enable verbose stderr logging (DEBUG)

        Environment variables:
          DEVRIG_JVM_OPTS                extra JVM options for the devrig launch (for example "-Xmx512m").


        """.trimIndent() + "\n"
    )
    return 0
}

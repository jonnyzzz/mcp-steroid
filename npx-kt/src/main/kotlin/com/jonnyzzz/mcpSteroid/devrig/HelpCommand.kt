package com.jonnyzzz.mcpSteroid.devrig

import java.io.PrintStream

fun printVersion(out: PrintStream) : Int {
    out.println(DevrigVersionMetadata.getDevrigVersion())
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

          devrig install claude|codex|gemini
                                         register this devrig binary as the
                                         mcp-steroid stdio MCP server in the
                                         selected coding agent.

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
          devrig --version | -v          print the devrig version and exit
          devrig --help    | -h          print this help and exit

        Options applicable to every mode:
          --debug                        enable verbose stderr logging (DEBUG)

        Environment variables:
          DEVRIG_JAVA_HOME               JDK/JRE home used to launch devrig — devrig needs Java 25.
                                         Overrides JAVA_HOME for the devrig process only (not the caller's).
          DEVRIG_JVM_OPTS                extra JVM options for the devrig launch (for example "-Xmx512m").


        """.trimIndent() + "\n"
    )
    return 0
}

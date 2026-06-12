Attach to a Remote JVM (host:port) for Debugging

Programmatically attach IntelliJ's debugger to an already-running JVM that was started with the JDWP agent (server=y), e.g. an IDE/app inside Docker on a mapped host port.

Use this when a JVM is already running with the JDWP agent open and you want to attach
the IDE's debugger to it at a known `host:port` — for example a Dockerized IntelliJ or a
`devrig` (npx-kt) process whose in-container debug port (`5005`/`5006`) is Docker-mapped to a
host port. The target was launched with:

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:<PORT>
```

`server=y` means the target **listens**; the IDE is the **client** and *attaches*. The
matching attach config therefore uses `SERVER_MODE = false`.

This recipe runs inside `steroid_execute_code` in your controlling IntelliJ IDEA (the Java
debugger plugin must be present — IDEA bundles it; PyCharm/GoLand/WebStorm/Rider differ).

## High-level path — a Remote run configuration

Create a `RemoteConfiguration`, point it at `host:port`, and launch it under the Debug
executor. This reuses the exact code path the "Remote JVM Debug" run config uses, so it shows
up in the Debug tool window and supports detach/re-attach.

```
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder

// host/port of the LISTENING target JVM (e.g. localhost + Docker-mapped host port)
val host = "localhost"
val port = "55004"

val type = RemoteConfigurationType.getInstance()
val cfg = RemoteConfiguration(project, type).apply {
    USE_SOCKET_TRANSPORT = true   // dt_socket
    SERVER_MODE = false           // IDE attaches; the target has server=y
    HOST = host
    PORT = port
}

// Launching a debug session touches the Debug tool window → run on EDT.
withContext(Dispatchers.EDT) {
    ExecutionEnvironmentBuilder
        .create(DefaultDebugExecutor.getDebugExecutorInstance(), cfg)
        .buildAndExecute()
}
println("Requested attach to $host:$port")
```

Notes:
- The public fields are `USE_SOCKET_TRANSPORT`, `SERVER_MODE`, `HOST`, `PORT` (serialized
  verbatim by the configuration). Do NOT use the deprecated `RemoteConnection.getHostName()`
  / `getAddress()` — prefer `getApplicationHostName()` / `getApplicationAddress()` when you
  read a `RemoteConnection` back.
- `buildAndExecute()` is fire-and-forget; the attach happens asynchronously. Poll for the
  session (below) instead of assuming it connected.

## Low-level path — attach directly

When you don't need the run-config plumbing, attach via `DebuggerManagerEx`. This is closer
to the metal and returns the `DebuggerSession` (or `null` on failure) synchronously.

```
import com.intellij.debugger.DefaultDebugEnvironment
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder

val host = "localhost"
val port = "55004"

// useSockets=true, hostName, address=port, serverMode=false (attach to a server=y target)
val connection = RemoteConnection(true, host, port, false)

val cfg = RemoteConfiguration(project, RemoteConfigurationType.getInstance())
val env = ExecutionEnvironmentBuilder
    .create(DefaultDebugExecutor.getDebugExecutorInstance(), cfg)
    .build()
val state = cfg.getState(env.executor, env)   // RemoteStateState (RunProfileState)

// pollTimeout=0 → attempt the attach immediately (no waiting for the target to come up)
val debugEnv = DefaultDebugEnvironment(env, state, connection, 0L)

val session = withContext(Dispatchers.EDT) {
    DebuggerManagerEx.getInstanceEx(project).attachVirtualMachine(debugEnv)
}
println(if (session != null) "Attached to $host:$port" else "Attach FAILED (connection refused?)")
```

## Verify the attach succeeded

A null `DebuggerSession`, or zero `XDebugSession`s after a moment, means the connection was
refused (wrong port, target not listening, firewall). Poll briefly:

```
import com.intellij.xdebugger.XDebuggerManager

var attached = false
repeat(20) {
    if (XDebuggerManager.getInstance(project).debugSessions.isNotEmpty()) {
        attached = true
        return@repeat
    }
    delay(250)
}
val sessions = XDebuggerManager.getInstance(project).debugSessions
println("Debug sessions: ${sessions.size}")
sessions.forEach { println("  ${it.sessionName} — stopped=${it.isStopped} paused=${it.isPaused}") }
if (!attached) println("No session — check the target is listening on the port (server=y, suspend=n)")
```

## Set breakpoints

Set breakpoints before or after attach. The IDE maps source lines to the classes loaded in
the remote target over JDWP. Line numbers are 0-based.

```
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil

val file = LocalFileSystem.getInstance()
    .findFileByPath("${project.basePath}/ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/DialogWindowsLookup.kt")
    ?: error("source file not found")

withContext(Dispatchers.EDT) {
    // 0-based line; toggle adds it if absent
    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, 84)
}
println("Breakpoint toggled")
```

## Gotchas

- **Threading.** Launching/attaching a session touches the Debug tool window — do it on
  `Dispatchers.EDT`. `XDebuggerManager.newSessionBuilder(...)` is `@RequiresEdt`.
- **`SERVER_MODE` is the whole ballgame.** Target `server=y` ⇒ IDE attaches ⇒
  `SERVER_MODE=false`. If you flip it, the IDE tries to listen and nothing connects.
- **`suspend=n` on the target** means it never waits for you — attach/detach any time while
  it's alive. (`suspend=y` would block the target's `main` until you attach.)
- **Wrong/closed port = silent-ish failure**: `attachVirtualMachine` returns `null` or no
  session appears. Confirm the host-mapped port (for Docker: the harness prints
  `Listening for transport dt_socket at address: <host-port>` and writes
  `IDE_DEBUG_PORT` / `DEVRIG_DEBUG_PORT` to `session-info.txt`).
- **Two targets at once** (IDE + devrig): attach two separate Remote configs to the two
  different host ports; both sessions coexist in the Debug tool window.

# See also

- [Debug another IDE instance (launch + control)](mcp-steroid://skill/debug-remote-ide-skill)
- [Create a debug run configuration](mcp-steroid://debugger/debug-run-configuration)
- [Control a debug session](mcp-steroid://debugger/debug-session-control)
- [Debugger skill overview](mcp-steroid://prompt/debugger-skill)

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.platform.ide.productMode.IdeProductMode

/**
 * Classification of the environment the IDE process runs in, logged once on MCP server startup.
 *
 * Headless (plain, i.e. not a remote-development backend and not a test process) is an
 * unsupported (best-effort) way to run MCP Steroid — the platform behaves differently without
 * a UI and long blocking waits/deadlocks in platform code have been observed
 * (https://github.com/jonnyzzz/mcp-steroid/issues/177).
 */
enum class IdeRunMode(val displayName: String) {
    NORMAL_UI("normal UI"),
    REMOTE_DEV_BACKEND("remote development (backend)"),
    HEADLESS("headless"),
    UNIT_TEST("unit-test"),
}

/**
 * Pure classifier, first match wins. The order matters: the AWT headless flag is also set for
 * test processes and can be set for remote-dev backends (e.g. the `rdserver-headless` command),
 * so the more specific modes must be checked before the plain headless flag.
 */
fun classifyIdeRunMode(isUnitTest: Boolean, isRemoteDevBackend: Boolean, isHeadless: Boolean): IdeRunMode = when {
    isUnitTest -> IdeRunMode.UNIT_TEST
    isRemoteDevBackend -> IdeRunMode.REMOTE_DEV_BACKEND
    isHeadless -> IdeRunMode.HEADLESS
    else -> IdeRunMode.NORMAL_UI
}

/**
 * Detects the run mode of the current IDE process.
 */
fun detectIdeRunMode(): IdeRunMode {
    val application = ApplicationManager.getApplication()
    return classifyIdeRunMode(
        isUnitTest = isUnitTestProcess(),
        isRemoteDevBackend = isRemoteDevBackend(),
        isHeadless = application.isHeadlessEnvironment,
    )
}

/**
 * `IDE run mode: ...` line with the raw flags, so eval-log forensics is one grep away.
 */
fun ideRunModeLogLine(mode: IdeRunMode): String {
    val application = ApplicationManager.getApplication()
    return "IDE run mode: ${mode.displayName} " +
        "(headless=${application.isHeadlessEnvironment}, unitTest=${isUnitTestProcess()}, " +
        "commandLine=${application.isCommandLine}, remoteDevBackend=${isRemoteDevBackend()})"
}

/**
 * True when this process is a test process, read from the Application accessor. This file is the
 * single allowlisted reader in `NoTestModeBranchingTest`: the accessor is used for
 * diagnostics-only run-mode classification (the startup log line and the headless warning),
 * never for behavior branching. The accessor — not the `idea.is.unit.test` system property — is
 * required because the test framework sets the Application flag directly in its constructor
 * without the property (production `ApplicationImpl` initializes it from the property; the
 * test-framework path does not).
 */
fun isUnitTestProcess(): Boolean = ApplicationManager.getApplication().isUnitTestMode

/**
 * True when the IDE runs as a remote-development backend. `IdeProductMode.isBackend` is the
 * platform-documented public replacement for the internal `AppMode.isRemoteDevHost()`; the
 * service lookup is guarded so a failure can never break server startup.
 */
fun isRemoteDevBackend(): Boolean = runCatching { IdeProductMode.isBackend }.getOrDefault(false)

/**
 * WARN logged once on startup when the detected mode is plain [IdeRunMode.HEADLESS].
 */
const val HEADLESS_UNSUPPORTED_WARNING: String =
    "MCP Steroid is running in a headless IDE. Headless mode is unsupported (best-effort): " +
        "the IDE platform behaves differently without a UI and long blocking waits/deadlocks in platform code " +
        "have been observed (see mcp-steroid#177). Prefer a normal desktop IDE or a remote development backend."

/**
 * One-line notice appended to the MCP server instructions when the detected mode is plain
 * [IdeRunMode.HEADLESS], so the connected agent knows the IDE is in an unsupported mode.
 */
const val HEADLESS_MCP_CLIENT_NOTICE: String =
    "Note: this IDE is running headless, which is unsupported (best-effort) for MCP Steroid — " +
        "long blocking waits/deadlocks in IDE platform code have been observed (see mcp-steroid#177), " +
        "so prefer your built-in tools for risky long-running operations."

/**
 * The WARN to log for the given mode, or null when the mode needs no warning.
 * Only plain [IdeRunMode.HEADLESS] warns — normal UI, remote-dev backends, and test
 * processes are supported environments.
 */
fun headlessWarningFor(mode: IdeRunMode): String? =
    if (mode == IdeRunMode.HEADLESS) HEADLESS_UNSUPPORTED_WARNING else null

/**
 * The MCP server instructions for the given mode: [baseInstructions] as-is for every
 * supported mode; plain [IdeRunMode.HEADLESS] appends [HEADLESS_MCP_CLIENT_NOTICE] so the
 * connected agent knows the environment is unsupported (see mcp-steroid#177).
 */
fun serverInstructionsFor(mode: IdeRunMode, baseInstructions: String): String =
    if (mode == IdeRunMode.HEADLESS) baseInstructions + "\n\n" + HEADLESS_MCP_CLIENT_NOTICE
    else baseInstructions


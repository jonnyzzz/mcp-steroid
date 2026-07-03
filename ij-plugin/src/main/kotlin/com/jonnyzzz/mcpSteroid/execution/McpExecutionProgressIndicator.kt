/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.progress.EmptyProgressIndicatorBase
import com.intellij.openapi.progress.StandardProgressIndicator

/**
 * The per-execution cancellable indicator behind [McpScriptContext.progressIndicator] (#213).
 *
 * A sticky-cancel indicator: once [cancel] is called, [isCanceled] stays `true` forever.
 *
 * Why not `EmptyProgressIndicator`: its `start()` CLEARS the cancellation flag
 * (`EmptyProgressIndicator.java`), so any consumer that runs
 * `ProgressManager.runProcess(..., indicator)` would silently un-cancel an already-cancelled
 * execution. The platform hit the same trap and solved it with its internal
 * `BridgeJobIndicatorBase` (`platform/core-api/.../progress/BridgeJobIndicatorBase.kt`);
 * that class is `@ApiStatus.Internal`, so we replicate the ~10 lines here on top of the
 * public [EmptyProgressIndicatorBase], whose `start()` keeps the cancellation state.
 *
 * [EmptyProgressIndicatorBase.checkCanceled] is `final` and consults our overridden
 * [isCanceled], so the sticky flag is honored by every `ProgressManager.checkCanceled()`
 * poll once this indicator is installed (e.g. by `InspectionEngine.inspectEx` worker threads).
 * [StandardProgressIndicator] marks the standard cancel semantics; Kotlin members are final
 * by default, satisfying its contract.
 *
 * The `EmptyProgressIndicatorBase` constructor is `@ApiStatus.Obsolete` — accepted as the
 * single documented exception: there is no non-obsolete public way to construct an indicator,
 * and bridging coroutine cancellation into indicator-driven blocking APIs is exactly the use
 * the platform docs sanction (see issue #213, "API verification").
 */
class McpExecutionProgressIndicator : EmptyProgressIndicatorBase(), StandardProgressIndicator {
    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun isCanceled(): Boolean = cancelled
}

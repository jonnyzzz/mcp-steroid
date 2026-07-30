/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.ImageUtil
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Point
import java.awt.Dimension
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

@Serializable
data class ScreenshotMeta(
    val system: String,
    val imageFile: String,
    val treeFiles: List<String>,
    val metaFile: String,
    val componentClass: String,
    val componentName: String?,
    val componentSize: Size,
    val imageSize: Size,
    val locationOnScreen: PointInfo?,
    val windowId: String? = null,
    val windowTitle: String? = null,
    val windowBounds: Rect? = null,
    val projectName: String? = null,
    val projectPath: String? = null,
    val capturedAt: String,
)

@Serializable
data class Size(val width: Int, val height: Int)

@Serializable
data class PointInfo(val x: Int, val y: Int)

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

data class ScreenshotArtifacts(
    //TODO: replace with load method and handle in the custom way when serializing
    //TODO: include content-type
    val imageBytes: ByteArray,
    //TODO: use imports
    val imagePath: Path,
    val treePath: Path,
    val metaPath: Path,
    val meta: ScreenshotMeta,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotArtifacts) return false
        return imageBytes.contentEquals(other.imageBytes) &&
                imagePath == other.imagePath &&
                treePath == other.treePath &&
                metaPath == other.metaPath &&
                meta == other.meta
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + imagePath.hashCode()
        result = 31 * result + treePath.hashCode()
        result = 31 * result + metaPath.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }

    fun logMessages() = buildList {
        add("window_id: ${meta.windowId}")
        add("Screenshot saved to $imagePath")
        add("Component tree saved to $treePath")
        add("Screenshot metadata saved to $metaPath")
    }
}

@Service(Service.Level.PROJECT)
class VisionService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val log = thisLogger()
    private val screenshotCounter = AtomicLong(0)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    companion object {
        private const val META_FILE = "screenshot-meta.json"

        fun getInstance(project: Project): VisionService = project.service()
    }

    suspend fun capture(executionId: ExecutionId, windowId: String? = null): ScreenshotArtifacts {
        return withContext(Dispatchers.IO + CoroutineName("VisionService")) {
            captureImpl(executionId, windowId)
        }
    }

    private suspend fun captureImpl(executionId: ExecutionId, windowId: String? = null): ScreenshotArtifacts {
        val storage = project.executionStorage
        val executionDir = storage.resolveExecutionDir(executionId)

        // Create a unique screenshot subdirectory to prevent filename collisions
        // when multiple screenshots are captured within the same execution.
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val counter = screenshotCounter.incrementAndGet()
        val screenshotDir = withContext(Dispatchers.IO) {
            val dir = executionDir.resolve("screenshot-$timestamp-$counter")
            Files.createDirectories(dir)
            dir
        }

        // Capture component info on EDT (use ModalityState.any() so this works even when modal dialogs
        // are showing). Resolve the component ONCE and reuse it for the metadata providers below —
        // resolving again in a second hop could describe a different component than the captured image
        // (e.g. a modal dialog closing in between).
        val (capture, component) = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            val component = resolveComponent(windowId)
            captureOnEdt(component) to component
        }

        val initialContext = ScreenCaptureContext(
            project = project,
            component = component,
            executionDir = screenshotDir,
        )

        // Collect metadata from all providers (including screenshot image)
        val collectedMetadata = collectMetadataFromProviders(initialContext, screenshotDir)

        // Find screenshot image from collected metadata
        val screenshotMetadata = collectedMetadata.find { it.type == ScreenshotImageProvider.TYPE }
        val imageBytes = screenshotMetadata?.binaryContent
            ?: throw IllegalStateException("No screenshot image provider available")
        val imageFileName = screenshotMetadata.fileName

        // Collect non-image files for treeFiles
        val treeFiles = collectedMetadata
            .filter { !it.isImage() }
            .map { it.fileName }

        // Load image to get dimensions
        val imageSize = withContext(Dispatchers.IO) {
            val image = javax.imageio.ImageIO.read(ByteArrayInputStream(imageBytes))
            Size(image.width, image.height)
        }

        val meta = ScreenshotMeta(
            system = "swing",
            imageFile = imageFileName,
            treeFiles = treeFiles,
            metaFile = META_FILE,
            componentClass = capture.componentClass,
            componentName = capture.componentName,
            componentSize = Size(capture.componentSize.width, capture.componentSize.height),
            imageSize = imageSize,
            locationOnScreen = capture.locationOnScreen?.let { PointInfo(it.x, it.y) },
            windowId = capture.windowId,
            windowTitle = capture.windowTitle,
            windowBounds = capture.windowBounds,
            projectName = capture.projectName,
            projectPath = capture.projectPath,
            capturedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        )

        val metaPath = withContext(Dispatchers.IO) {
            val path = screenshotDir.resolve(META_FILE)
            path.toFile().writeText(json.encodeToString(ScreenshotMeta.serializer(), meta))
            path
        }

        val imagePathInCaptureDir = screenshotDir.resolve(imageFileName)
        val treePathInCaptureDir = if (treeFiles.isNotEmpty()) {
            screenshotDir.resolve(treeFiles.first())
        } else {
            screenshotDir.resolve("screenshot-tree.md")
        }

        // Keep compatibility with existing API/docs/tests:
        // expose fixed filenames in execution root while still keeping timestamped snapshots.
        val (imagePath, treePath, rootMetaPath) = mirrorLatestArtifactsToExecutionRoot(
            executionDir = executionDir,
            imagePathInCaptureDir = imagePathInCaptureDir,
            treePathInCaptureDir = treePathInCaptureDir,
            metaPathInCaptureDir = metaPath,
        )

        return ScreenshotArtifacts(
            imageBytes = imageBytes,
            imagePath = imagePath,
            treePath = treePath,
            metaPath = rootMetaPath,
            meta = meta,
        )
    }

    private suspend fun mirrorLatestArtifactsToExecutionRoot(
        executionDir: Path,
        imagePathInCaptureDir: Path,
        treePathInCaptureDir: Path,
        metaPathInCaptureDir: Path,
    ): Triple<Path, Path, Path> {
        return withContext(Dispatchers.IO) {
            val rootImagePath = executionDir.resolve(ScreenshotImageProvider.FILE_NAME)
            val rootTreePath = executionDir.resolve(SwingComponentTreeProvider.FILE_NAME)
            val rootMetaPath = executionDir.resolve(META_FILE)

            Files.copy(imagePathInCaptureDir, rootImagePath, StandardCopyOption.REPLACE_EXISTING)
            if (Files.exists(treePathInCaptureDir)) {
                Files.copy(treePathInCaptureDir, rootTreePath, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.copy(metaPathInCaptureDir, rootMetaPath, StandardCopyOption.REPLACE_EXISTING)

            Triple(rootImagePath, rootTreePath, rootMetaPath)
        }
    }

    /**
     * Collects metadata from all registered providers.
     * Providers are called iteratively until all return Success or Skip.
     * Providers returning DependsOnOthers are retried after others complete.
     * The context is updated with collected metadata after each provider completes.
     * Files are written to the screenshot directory as each provider completes.
     */
    private suspend fun collectMetadataFromProviders(
        initialContext: ScreenCaptureContext,
        screenshotDir: Path,
    ): List<ScreenshotMetadata> {
        val providers = ScreenshotMetadataProvider.EP_NAME.extensionList
        if (providers.isEmpty()) {
            return emptyList()
        }

        // Run only the fast, in-line providers synchronously (the screenshot image + the
        // component tree). DEFERRED providers (e.g. OCR / Tesseract — a heavy external
        // process) are postponed: they are queued on the service [scope] and run AFTER
        // capture() returns, so capturing a screenshot — including the dialog the
        // DialogKiller is about to close — never blocks on them. This keeps the killer's
        // critical path (capture → close → restore non-modal) fast and unblocked.
        val inlineProviders = providers.filter { !it.deferred }
        val deferredProviders = providers.filter { it.deferred }

        val inlineResults = runProviderLoop(inlineProviders, initialContext, screenshotDir)

        if (deferredProviders.isNotEmpty()) {
            // Context already carries the inline metadata (incl. the written image), so a
            // deferred provider that DependsOnOthers (OCR needs the image) resolves it from
            // disk. Fire-and-forget on the service scope; failures are logged, never fatal.
            val deferredContext = initialContext.withMetadata(inlineResults)
            scope.launch(CoroutineName("VisionService-deferred")) {
                try {
                    runProviderLoop(deferredProviders, deferredContext, screenshotDir)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log.warn("Deferred screenshot metadata provider(s) failed: ${t.message}", t)
                }
            }
        }

        return inlineResults
    }

    /**
     * Runs [providers] iteratively until all return Success/Skip, retrying any that return
     * DependsOnOthers after others complete; writes each provider's files as it succeeds.
     */
    private suspend fun runProviderLoop(
        providers: List<ScreenshotMetadataProvider>,
        initialContext: ScreenCaptureContext,
        screenshotDir: Path,
    ): List<ScreenshotMetadata> {
        if (providers.isEmpty()) return emptyList()

        val results = mutableListOf<ScreenshotMetadata>()
        val pending = providers.toMutableList()
        var previousPendingCount = pending.size + 1
        var context = initialContext

        // Iterate until all providers complete or no progress is made
        while (pending.isNotEmpty() && pending.size < previousPendingCount) {
            previousPendingCount = pending.size
            val iterator = pending.iterator()

            while (iterator.hasNext()) {
                val provider = iterator.next()
                when (val result = provider.provide(context)) {
                    is ProviderResult.Success -> {
                        // Write files to screenshot directory immediately so dependent providers can access them
                        for (metadata in result.metadata) {
                            writeMetadataToDir(screenshotDir, metadata)
                        }
                        results.addAll(result.metadata)
                        // Update context with the new metadata for subsequent providers
                        context = context.withMetadata(result.metadata)
                        iterator.remove()
                    }
                    is ProviderResult.Skip -> {
                        iterator.remove()
                    }
                    is ProviderResult.DependsOnOthers -> {
                        // Keep in pending list for next iteration
                    }
                }
            }
        }

        return results
    }

    /**
     * Write metadata content directly to the screenshot directory.
     */
    private suspend fun writeMetadataToDir(
        screenshotDir: Path,
        metadata: ScreenshotMetadata,
    ) {
        withContext(Dispatchers.IO) {
            val path = screenshotDir.resolve(metadata.fileName)
            if (metadata.content != null) {
                path.toFile().writeText(metadata.content)
            } else if (metadata.binaryContent != null) {
                Files.write(path, metadata.binaryContent)
            }
        }
    }

    suspend fun executeInput(
        windowId: String,
        steps: List<InputStep>,
    ) {
        val executor = SwingInputExecutor(windowId)
        executor.execute(steps)
    }

    private data class CaptureInfo(
        val image: BufferedImage,
        val componentClass: String,
        val componentName: String?,
        val componentSize: Dimension,
        val locationOnScreen: Point?,
        val windowId: String,
        val windowTitle: String?,
        val windowBounds: Rect?,
        val projectName: String?,
        val projectPath: String?,
    )

    private fun captureOnEdt(component: Component): CaptureInfo {
        val size = component.size
        val preferred = component.preferredSize
        val width = size.width.takeIf { it > 0 } ?: preferred.width.takeIf { it > 0 } ?: 1024
        val height = size.height.takeIf { it > 0 } ?: preferred.height.takeIf { it > 0 } ?: 768

        val image = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.printAll(graphics)
        } finally {
            graphics.dispose()
        }

        val location = runCatching { component.locationOnScreen }.getOrNull()
        // A captured dialog IS a Window: getWindowAncestor walks from getParent(), which for a Window
        // is its OWNER — the response would echo the owner frame's windowId/bounds/title instead of
        // the dialog's (issue #309, problem 3). Same pattern as ensureFocus.
        val window = component as? Window ?: SwingUtilities.getWindowAncestor(component)
        val windowIdValue = WindowIdUtil.compute(window, component)
        val windowBounds = window?.bounds?.let { Rect(it.x, it.y, it.width, it.height) }
        val windowTitle = when (window) {
            is java.awt.Frame -> window.title
            is java.awt.Dialog -> window.title
            else -> null
        }

        return CaptureInfo(
            image = image,
            componentClass = component.javaClass.name,
            componentName = component.name,
            componentSize = Dimension(component.width, component.height),
            locationOnScreen = location,
            windowId = windowIdValue,
            windowTitle = windowTitle,
            windowBounds = windowBounds,
            projectName = project.name,
            projectPath = project.basePath,
        )
    }

    private fun resolveComponent(windowId: String?): Component {
        if (windowId != null) {
            return findComponentByWindowId(windowId)
                ?: throw IllegalStateException("Window not found for window_id: $windowId")
        }

        // If a modal dialog is active, capture it instead of the IDE frame
        val activeWindow = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        if (activeWindow is java.awt.Dialog && activeWindow.isModal && activeWindow.isVisible) {
            return activeWindow
        }

        return WindowManager.getInstance().getIdeFrame(project)?.component
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.component
            ?: throw IllegalStateException("No IDE frame or editor component available for screenshot")
    }

    /**
     * Find a component by window ID. Searches both project frames and all displayable windows.
     * @return The component if found, null otherwise
     */
    private fun findComponentByWindowId(windowId: String): Component? {
        // Search project frames first
        for (frame in WindowManager.getInstance().allProjectFrames) {
            val component = frame.component
            val window = SwingUtilities.getWindowAncestor(component)
            if (WindowIdUtil.compute(window, component) == windowId) {
                return component
            }
        }
        // Fall back to all displayable windows
        for (window in Window.getWindows()) {
            if (!window.isDisplayable) continue
            if (WindowIdUtil.compute(window, window) == windowId) {
                return window
            }
        }
        return null
    }

    private inner class SwingInputExecutor(
        private val windowId: String,
    ) {
        private val stuckKeys = LinkedHashSet<Int>()

        suspend fun execute(steps: List<InputStep>) {
            // ModalityState.any(): bare Dispatchers.EDT dispatches with NON_MODAL modality, which the
            // platform withholds while any modal dialog is open — the first hop would park forever and
            // the whole tool call hangs (issue #309, problem 1). Input must reach dialogs too; this is
            // the same fix capture() received in e4ebf791.
            val edtAny = Dispatchers.EDT + ModalityState.any().asContextElement()
            val rootComponent = withContext(edtAny) {
                resolveComponentForInput()
            }

            try {
                withContext(edtAny) {
                    // Activate the target window ONLY — do not requestFocus() on the root component.
                    // ensureFocus(root) would call IdeFocusManager.requestFocus(window, true), which
                    // displaces the focus owner a prior click established, so a following press:/type:
                    // step (which must reach the focused component) loses its target (issue #309,
                    // problem 2 — the press:SPACE case).
                    activateWindow(rootComponent)
                }
                for (step in steps) {
                    when (step) {
                        is InputStep.Delay -> delay(step.ms)
                        is InputStep.StickKey -> withContext(edtAny) { stickKey(rootComponent, step) }
                        is InputStep.PressKey -> withContext(edtAny) { pressKey(rootComponent, step) }
                        is InputStep.TypeText -> withContext(edtAny) { typeText(rootComponent, step) }
                        is InputStep.Click -> withContext(edtAny) { click(rootComponent, step) }
                    }
                }
            } finally {
                // NonCancellable: on cancellation a plain withContext throws before running the block,
                // leaking stuck modifier keys into the IDE.
                withContext(NonCancellable + edtAny) {
                    releaseAll(rootComponent)
                }
            }
        }

        private fun resolveComponentForInput(): Component {
            return findComponentByWindowId(windowId)
                ?: throw IllegalStateException("No IDE window found for window_id: $windowId")
        }

        private fun stickKey(component: Component, step: InputStep.StickKey) {
            // Read the focus owner BEFORE ensuring focus: ensureFocus(focus) re-asserts focus on the
            // current owner, but ensureFocus(root) would displace it (issue #309, problem 2).
            val focus = focusOwner(component)
            ensureFocus(focus)
            if (stuckKeys.add(step.keyCode)) {
                dispatchKey(focus, KeyEvent.KEY_PRESSED, step.keyCode, '\u0000', currentModifiers())
            }
        }

        private fun pressKey(component: Component, step: InputStep.PressKey) {
            // Directly-dispatched KeyEvents are never retargeted to the focus owner (the
            // KeyboardFocusManager only retargets POSTED events), so a key sent to the root window
            // cannot reach the focused component's WHEN_FOCUSED bindings (issue #309, problem 2) —
            // dispatch to the focus owner, as typeText already does. Read the owner FIRST, then
            // ensureFocus(focus) re-asserts it; ensureFocus(root) would displace it before dispatch.
            val focus = focusOwner(component)
            ensureFocus(focus)
            val tempModifiers = step.modifiers.mapNotNull { modifierKeyCode(it) }
                .filterNot { stuckKeys.contains(it) }
            tempModifiers.forEach { dispatchKey(focus, KeyEvent.KEY_PRESSED, it, '\u0000', currentModifiers()) }

            dispatchKey(focus, KeyEvent.KEY_PRESSED, step.keyCode, '\u0000', currentModifiers(step.modifiers))
            dispatchKey(focus, KeyEvent.KEY_RELEASED, step.keyCode, '\u0000', currentModifiers(step.modifiers))

            tempModifiers.reversed().forEach { dispatchKey(focus, KeyEvent.KEY_RELEASED, it, '\u0000', currentModifiers()) }
        }

        private fun typeText(component: Component, step: InputStep.TypeText) {
            val focus = focusOwner(component)
            ensureFocus(focus)
            focus.requestFocusInWindow()
            step.text.forEach { ch ->
                dispatchKey(focus, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ch, currentModifiers())
            }
        }

        private fun click(component: Component, step: InputStep.Click) {
            val targetComponent = when (val target = step.target) {
                is InputTarget.ScreenshotPixel -> {
                    val point = mapScreenshotPoint(component, target.x, target.y)
                    SwingUtilities.getDeepestComponentAt(component, point.x, point.y) ?: component
                }
                is InputTarget.ScreenPixel -> {
                    val point = Point(target.x, target.y)
                    SwingUtilities.convertPointFromScreen(point, component)
                    SwingUtilities.getDeepestComponentAt(component, point.x, point.y) ?: component
                }
                is InputTarget.Unsupported -> throw IllegalStateException("Unsupported target: ${target.raw}")
            }

            val point = when (val target = step.target) {
                is InputTarget.ScreenshotPixel -> mapScreenshotPoint(component, target.x, target.y)
                is InputTarget.ScreenPixel -> Point(target.x, target.y).also {
                    SwingUtilities.convertPointFromScreen(it, component)
                }
                is InputTarget.Unsupported -> throw IllegalStateException("Unsupported target: ${target.raw}")
            }

            ensureFocus(targetComponent)
            targetComponent.requestFocusInWindow()

            val modifiers = currentModifiers(step.modifiers)
            val button = when (step.button) {
                MouseButton.LEFT -> MouseEvent.BUTTON1
                MouseButton.RIGHT -> MouseEvent.BUTTON3
                MouseButton.MIDDLE -> MouseEvent.BUTTON2
            }

            // The MouseEvent's x/y must be in the TARGET's local space: Swing button listeners gate on
            // component.contains(e.x, e.y), so window-space coordinates never arm the model — focus
            // moves, state does not change (issue #309, problem 2).
            val localPoint = SwingUtilities.convertPoint(component, point, targetComponent)

            dispatchMouse(targetComponent, MouseEvent.MOUSE_PRESSED, localPoint, button, modifiers)
            dispatchMouse(targetComponent, MouseEvent.MOUSE_RELEASED, localPoint, button, modifiers)
            dispatchMouse(targetComponent, MouseEvent.MOUSE_CLICKED, localPoint, button, modifiers)
        }

        private fun mapScreenshotPoint(component: Component, x: Int, y: Int): Point {
            require(component.width > 0 && component.height > 0) {
                "Target component has empty size"
            }
            // Coordinates are reported relative to the window (steroid_list_windows /
            // steroid_take_screenshot render at the component's logical size), so they map
            // directly onto the live component; clamp to its current bounds.
            return Point(x.coerceIn(0, component.width - 1), y.coerceIn(0, component.height - 1))
        }

        private fun focusOwner(component: Component): Component {
            val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            return focus ?: component
        }

        private fun ensureFocus(component: Component) {
            activateWindow(component)
            IdeFocusManager.findInstanceByComponent(component).requestFocus(component, true)
        }

        /**
         * Bring the component's window to the front and make it active — WITHOUT the
         * `IdeFocusManager.requestFocus(component, true)` step that [ensureFocus] performs. Requesting
         * focus on the root window displaces whatever component currently owns focus, which breaks a
         * key step that must reach a previously-focused component (issue #309, problem 2).
         */
        private fun activateWindow(component: Component) {
            val window = component as? Window ?: SwingUtilities.getWindowAncestor(component)
            if (window != null && !window.isActive) {
                window.toFront()
                window.requestFocus()
            }
        }

        private fun releaseAll(component: Component) {
            val focus = focusOwner(component)
            stuckKeys.reversed().forEach { code ->
                dispatchKey(focus, KeyEvent.KEY_RELEASED, code, '\u0000', currentModifiers())
            }
            stuckKeys.clear()
        }

        private fun currentModifiers(extra: Set<InputModifier> = emptySet()): Int {
            val all = stuckKeys.mapNotNull { modifierFromKeyCode(it) }.toSet() + extra
            var mask = 0
            if (InputModifier.SHIFT in all) mask = mask or InputEvent.SHIFT_DOWN_MASK
            if (InputModifier.CTRL in all) mask = mask or InputEvent.CTRL_DOWN_MASK
            if (InputModifier.ALT in all) mask = mask or InputEvent.ALT_DOWN_MASK
            if (InputModifier.META in all) mask = mask or InputEvent.META_DOWN_MASK
            return mask
        }

        private fun modifierFromKeyCode(code: Int): InputModifier? {
            return when (code) {
                KeyEvent.VK_SHIFT -> InputModifier.SHIFT
                KeyEvent.VK_CONTROL -> InputModifier.CTRL
                KeyEvent.VK_ALT -> InputModifier.ALT
                KeyEvent.VK_META -> InputModifier.META
                else -> null
            }
        }

        private fun modifierKeyCode(modifier: InputModifier): Int? {
            return when (modifier) {
                InputModifier.SHIFT -> KeyEvent.VK_SHIFT
                InputModifier.CTRL -> KeyEvent.VK_CONTROL
                InputModifier.ALT -> KeyEvent.VK_ALT
                InputModifier.META -> KeyEvent.VK_META
            }
        }

        private fun dispatchKey(component: Component, id: Int, keyCode: Int, char: Char, modifiers: Int) {
            val event = KeyEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                keyCode,
                char
            )
            component.dispatchEvent(event)
        }

        private fun dispatchMouse(component: Component, id: Int, point: Point, button: Int, modifiers: Int) {
            val event = MouseEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                point.x,
                point.y,
                1,
                button == MouseEvent.BUTTON3,
                button
            )
            component.dispatchEvent(event)
        }
    }
}

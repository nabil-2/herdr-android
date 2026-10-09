package dev.herdr.android.ui

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A phone-sized viewport over a remote grid. Font/IME/rotation changes only the local
 * viewport: they never resize, refocus, or otherwise modify the user's remote pane.
 *
 * The display emulator MUST be created with an empty onKeyboardInput callback.
 * Native terminal query replies and remote OSC commands have no path to [onInput].
 */
@Composable
internal fun TerminalSurface(
    emulator: TerminalEmulator,
    rows: Int,
    cols: Int,
    enabled: Boolean,
    showSoftKeyboard: Boolean,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    foreground: Color,
    background: Color,
    onInput: (ByteArray) -> Boolean,
    modifier: Modifier = Modifier,
    ctrlPressed: Boolean = false,
    altPressed: Boolean = false,
    onModifierConsumed: () -> Unit = {},
    onKeyInput: ((List<String>) -> Boolean)? = null,
    onPasteRequest: () -> Unit = {},
    onPasteTextRequest: ((String) -> Unit)? = null,
    onKeyboardRequested: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    fitToWidth: Boolean = false,
    viewportResetKey: Int = 0,
    inputModes: TerminalInputModes = remember { TerminalInputModes() },
    inputSessionKey: Any? = null,
    onInputControllerAvailable: (TerminalInputController) -> Unit = {},
    onSelectionControllerAvailable: (SelectionController) -> Unit = {},
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    var viewport by remember(emulator) { mutableStateOf(IntSize.Zero) }
    var pan by remember(emulator) { mutableStateOf(Offset.Zero) }
    var inputView by remember(emulator) { mutableStateOf<TerminalInputView?>(null) }
    var composing by remember(emulator) { mutableStateOf("") }
    var selectionController by remember(emulator) { mutableStateOf<SelectionController?>(null) }
    val selectionActive by remember(emulator) { derivedStateOf { selectionController?.isSelectionActive == true } }
    val currentInput by rememberUpdatedState(onInput)
    val currentKeyInput by rememberUpdatedState(onKeyInput)
    val currentConsume by rememberUpdatedState(onModifierConsumed)
    val currentFontChange by rememberUpdatedState(onFontSizeChange)
    val currentKeyboardRequest by rememberUpdatedState(onKeyboardRequested)
    val currentPasteRequest by rememberUpdatedState(onPasteRequest)
    val currentPasteTextRequest by rememberUpdatedState(onPasteTextRequest)
    val currentEnabled by rememberUpdatedState(enabled)

    val requestedSize = fontSize.takeIf { it.isFinite() }?.coerceIn(8f, 28f) ?: 14f
    val actualSize = remember(requestedSize, fitToWidth, viewport.width, cols, density) {
        if (fitToWidth && viewport.width > 0) {
            val paint = TextPaint().apply { typeface = Typeface.MONOSPACE; textSize = with(density) { 1.sp.toPx() } }
            (viewport.width / (cols.coerceAtLeast(1) * paint.measureText("M"))).coerceIn(4f, 28f)
        } else requestedSize
    }
    val grid = remember(actualSize, cols, rows, density) {
        val paint = TextPaint().apply { typeface = Typeface.MONOSPACE; textSize = with(density) { actualSize.sp.toPx() } }
        val charHeight = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
        IntSize(ceil(paint.measureText("M") * cols).toInt().coerceAtLeast(1), (charHeight * rows).roundToInt().coerceAtLeast(1))
    }
    val currentGrid by rememberUpdatedState(grid)
    val currentViewport by rememberUpdatedState(viewport)
    val currentFont by rememberUpdatedState(actualSize)

    LaunchedEffect(emulator, foreground, background) {
        emulator.setDefaultColors(foreground.toArgb(), background.toArgb())
    }
    // A shrinking IME viewport follows the lower edge where shell prompts usually
    // live. A user's explicit pan remains stable until they reset the viewport.
    var previousGrid by remember(emulator) { mutableStateOf(IntSize.Zero) }
    var previousViewport by remember(emulator) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(grid, viewport, viewportResetKey) {
        val oldBottom = -(previousGrid.height - previousViewport.height).coerceAtLeast(0).toFloat()
        val followBottom = previousGrid == IntSize.Zero || kotlin.math.abs(pan.y - oldBottom) < 2f
        val clamped = clampTerminalPan(pan, grid, viewport)
        pan = if (followBottom) clamped.copy(y = -(grid.height - viewport.height).coerceAtLeast(0).toFloat()) else clamped
        previousGrid = grid
        previousViewport = viewport
    }
    LaunchedEffect(viewportResetKey) {
        pan = Offset(0f, -(grid.height - viewport.height).coerceAtLeast(0).toFloat())
        selectionController?.clearSelection()
    }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) { onImeVisibilityChanged(imeVisible) }
    DisposableEffect(emulator) {
        onDispose { inputView?.dispose() }
    }

    Box(
        modifier.fillMaxSize().background(background).clipToBounds()
            .onSizeChanged { viewport = it }
            .semantics {
                contentDescription = "Live terminal output. Tap to type; pinch to zoom; drag to pan; hold to select text."
                stateDescription = "Remote grid $cols columns by $rows rows. ${actualSize.roundToInt()} point text."
                onClick(label = "Open terminal keyboard") {
                    if (currentEnabled) {
                        currentKeyboardRequest()
                        inputView?.requestKeyboard()
                        true
                    } else false
                }
            }
            .testTag("terminal-surface")
            .pointerInput(emulator) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = down.position
                    var panning = false
                    var pinching = false
                    var zoomSize = currentFont
                    val outsideGrid = down.position.x !in pan.x..(pan.x + currentGrid.width) ||
                        down.position.y !in pan.y..(pan.y + currentGrid.height)
                    var releasedAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) {
                            releasedAt = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                            break
                        }
                        if (pressed >= 2) {
                            val factor = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val move = event.calculatePan()
                            val oldSize = zoomSize
                            zoomSize = (zoomSize * factor).coerceIn(8f, 28f)
                            val appliedFactor = zoomSize / oldSize
                            pan = clampTerminalPan((pan - centroid) * appliedFactor + centroid + move, currentGrid, currentViewport)
                            currentFontChange(zoomSize)
                            pinching = true
                            event.changes.forEach { it.consume() }
                        } else if (!pinching && selectionController?.isSelectionActive != true) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val distance = change.position - start
                            val beforeLongPress = change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                            if (!panning && beforeLongPress && distance.getDistance() > viewConfiguration.touchSlop) panning = true
                            if (panning) {
                                pan = clampTerminalPan(pan + (change.position - change.previousPosition), currentGrid, currentViewport)
                                change.consume()
                            }
                        } else if (pinching) {
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            pan = clampTerminalPan(pan + (change.position - change.previousPosition), currentGrid, currentViewport)
                            change.consume()
                        }
                    }
                    if (outsideGrid && !panning && !pinching && currentEnabled &&
                        releasedAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        currentKeyboardRequest()
                        inputView?.requestKeyboard()
                    }
                }
            },
    ) {
        // wrapContentSize(unbounded=true) permits the remote grid to exceed the
        // phone's constraints. It is clipped and panned, never reflowed remotely.
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
                .size(with(density) { grid.width.toDp() }, with(density) { grid.height.toDp() }),
        ) {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.fillMaxSize(),
                forcedSize = rows to cols,
                initialFontSize = actualSize.sp,
                minFontSize = actualSize.sp,
                maxFontSize = actualSize.sp,
                backgroundColor = background,
                foregroundColor = foreground,
                // The dedicated input view below handles all local typing. This
                // keeps libvterm's output-query responses permanently isolated.
                keyboardEnabled = false,
                showSoftKeyboard = false,
                onTerminalTap = {
                    if (currentEnabled) {
                        currentKeyboardRequest()
                        inputView?.requestKeyboard()
                    }
                },
                onSelectionControllerAvailable = {
                    selectionController = it
                    onSelectionControllerAvailable(it)
                },
                onPasteRequest = { if (currentEnabled) currentPasteRequest() },
                onHyperlinkClick = { url ->
                    if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) }
                },
            )
        }
        key(emulator, inputSessionKey) {
            AndroidView(
                factory = { context -> TerminalInputView(context).also { inputView = it } },
                update = { view ->
                    view.onInput = { currentInput(it) }
                    view.onKeyInput = currentKeyInput
                    view.onModifierConsumed = { currentConsume() }
                    view.onComposingChanged = { composing = it }
                    view.onPasteRequest = { if (currentEnabled) currentPasteRequest() }
                    view.onPasteTextRequest = currentPasteTextRequest
                    view.ctrlPressed = ctrlPressed
                    view.altPressed = altPressed
                    view.inputModes = inputModes
                    view.configure(enabled, showSoftKeyboard)
                    onInputControllerAvailable(view)
                },
                onReset = null,
                onRelease = { it.dispose() },
                modifier = Modifier.align(Alignment.BottomStart).size(1.dp).testTag("terminal-input-view"),
            )
        }
        // The library's floating copy menu can land beyond the phone's clipped
        // viewport for a wide selection. These controls always stay reachable.
        if (selectionActive) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(CanvasColor.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { selectionController?.copySelection() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-copy-selection"),
                ) { Text("Copy") }
                TextButton(
                    onClick = { selectionController?.clearSelection() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-clear-selection"),
                ) { Text("Cancel selection") }
            }
        }
        if (composing.isNotEmpty()) {
            Text(
                composing,
                Modifier.align(Alignment.BottomStart).background(background.copy(alpha = 0.95f)).padding(horizontal = 12.dp, vertical = 6.dp),
                color = foreground,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
    }
}

internal fun clampTerminalPan(offset: Offset, grid: IntSize, viewport: IntSize): Offset = Offset(
    if (grid.width <= viewport.width) 0f else offset.x.coerceIn(-(grid.width - viewport.width).toFloat(), 0f),
    if (grid.height <= viewport.height) 0f else offset.y.coerceIn(-(grid.height - viewport.height).toFloat(), 0f),
)

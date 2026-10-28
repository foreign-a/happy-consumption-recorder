package com.happy.consumption.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot

val LocalEditRegions = staticCompositionLocalOf<MutableMap<String, Rect>> { mutableMapOf() }
private data class ModeBackground(val fromSubtract: Boolean = false, val subtract: Boolean = false,
    val center: Offset = Offset.Zero, val radius: Float = 0f)
private val LocalModeBackground = staticCompositionLocalOf { ModeBackground() }
val SubtractBackground = Color(0xFFFFCDD2)

/** Each surface draws the same root-space circular reveal behind its own text and controls. */
@Composable fun Modifier.modeBackground(base: Color): Modifier {
    val reveal = LocalModeBackground.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        drawRect(if (reveal.fromSubtract) SubtractBackground else base)
        clipRect { drawCircle(if (reveal.subtract) SubtractBackground else base, reveal.radius, reveal.center - origin) }
    }
}

@Composable fun ModeInteractionRoot(model: AllRecordsViewModel, regions: MutableMap<String, Rect>, home: Boolean,
    content: @Composable () -> Unit) {
    val event by model.modeReveal.collectAsState()
    val active by rememberUpdatedState(home)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fraction = remember { Animatable(1f) }
    var lastEvent by remember { mutableLongStateOf(event.id) }
    LaunchedEffect(event.id, home) {
        if (home && event.id != lastEvent) {
            lastEvent = event.id; fraction.snapTo(0f)
            fraction.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        } else fraction.snapTo(1f)
    }
    val center = Offset(event.x, event.y)
    val radius = maxOf(hypot(center.x, center.y), hypot(size.width - center.x, center.y),
        hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y))
    val visual = if (home) ModeBackground(event.fromSubtract, event.subtract, center, (radius + 1f) * fraction.value)
        else ModeBackground()
    CompositionLocalProvider(LocalModeBackground provides visual) {
        Box(Modifier.fillMaxSize().onSizeChanged { size = it }.modeBackground(MaterialTheme.colorScheme.surface)
            .pointerInput(model, regions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val outside = regions.values.none { it.contains(down.position) }
                    var moved = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (event.changes.size > 1) moved = true
                        val finished = change == null || !change.pressed
                        if (finished && !moved && outside && active && model.inline.value?.changed == true) model.leave()
                    } while (event.changes.any { it.pressed })
                }
            }) { content() }
    }
}

package com.fixlens.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fixlens.guide.GuideView
import com.fixlens.kb.AnimDir
import com.fixlens.kb.AnimKind
import com.fixlens.tracking.MarkerState
import com.fixlens.ui.fx.easeInOutCubic
import com.fixlens.ui.fx.easeOutBack
import com.fixlens.ui.fx.easeOutCubic
import com.fixlens.ui.fx.lerp
import com.fixlens.ui.fx.window
import com.fixlens.ui.remote.reducedMotion
import com.fixlens.vision.BoxMapper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// How to do the current guided step, drawn small (docs/step-animations-plan.md): a how-to card in the top corner
// with a line pictogram, and for a part the marker tracks, a cue drawn on the part itself (an arrow turning round the
// cap, chevrons along the way a dipstick comes out, drops falling in). One amber motion per pictogram, a slow loop
// with a long rest, and still when the system has animations off. What to show comes from the KB, never the VLM.

private const val TAG = "FixLens"
private val TechRed = Color(0xFFFF5A5F)
private val Ok = Online
private val Line = Paper.copy(alpha = 0.92f)

/** One loop of every pictogram: act (~0.15–1.25 s), rest, fade, repeat. */
private const val LOOP_MS = 2600
/** Where a still (reduced-motion) pictogram is frozen: the action done, fully visible. */
private const val STILL_MS = 1700f
/** The on-part cue waits for the marker's lock-on to finish. */
private const val CUE_AFTER_IMPACT_MS = 900f

private val CARD_WIDTH = 112.dp
private val GLYPH_SIZE = 72.dp
/** Below the top bar and a one- or two-line hint chip. */
private val CARD_TOP = 136.dp
private val CARD_SIDE = 12.dp

private enum class Glyph { Turn, Pull, Push, Level, Pour, Unplug, SwitchOff, EngineOff, Technician }

private data class HowTo(val glyph: Glyph, val dir: AnimDir?) {
    val label: String
        get() = when (glyph) {
            Glyph.Turn -> if (dir == AnimDir.Cw) "Turn clockwise" else "Turn anticlockwise"
            Glyph.Pull -> "Pull out"
            Glyph.Push -> "Push back in"
            Glyph.Level -> "Between the marks"
            Glyph.Pour -> "Pour a little"
            Glyph.Unplug -> "Unplug first"
            Glyph.SwitchOff -> "Switch off at the wall"
            Glyph.EngineOff -> "Engine off"
            Glyph.Technician -> "Call a technician"
        }

    /** Pictograms that also have a cue on the tracked part. */
    val onPart: Boolean
        get() = glyph == Glyph.Turn || glyph == Glyph.Pull || glyph == Glyph.Push || glyph == Glyph.Pour || glyph == Glyph.Level
}

private fun howTo(guide: GuideView?): HowTo? {
    guide ?: return null
    if (guide.technician) return HowTo(Glyph.Technician, null)
    val a = guide.anim ?: return null
    val glyph = when (a.kind) {
        AnimKind.Turn -> Glyph.Turn
        AnimKind.Pull -> Glyph.Pull
        AnimKind.Push -> Glyph.Push
        AnimKind.Level -> Glyph.Level
        AnimKind.Pour -> Glyph.Pour
        AnimKind.Unplug -> Glyph.Unplug
        AnimKind.SwitchOff -> Glyph.SwitchOff
        AnimKind.EngineOff -> Glyph.EngineOff
    }
    return HowTo(glyph, a.dir)
}

/**
 * The how-to card: top right, or top left when the tracked part is under it. The cue on the part itself is
 * [StepPartCueLayer], drawn above the cards so a part low on screen doesn't hide it.
 */
@Composable
fun StepCueLayer(guide: GuideView?, marker: State<MarkerState?>, motion: MarkerMotion, modifier: Modifier = Modifier) {
    val cue = remember(guide) { howTo(guide) }
    val still = reducedMotion()
    val loop = rememberInfiniteTransition(label = "stepCue")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "stepCueLoop")
    var layer by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    // The card moves to the left when the part it's about sits under it.
    val onLeft by remember(layer, topInset) {
        derivedStateOf {
            val m = marker.value ?: return@derivedStateOf false
            val t = m.targets.firstOrNull() ?: return@derivedStateOf false
            if (layer.width == 0 || m.status == MarkerState.Status.Lost) return@derivedStateOf false
            val v = BoxMapper.fillCenter(m.frameWidth, m.frameHeight, layer.width.toFloat(), layer.height.toFloat()).map(t.box)
            with(density) {
                val zone = Rect(
                    layer.width - (CARD_SIDE + CARD_WIDTH + 16.dp).toPx(), topInset + (CARD_TOP - 16.dp).toPx(),
                    layer.width.toFloat(), topInset + (CARD_TOP + 150.dp).toPx(),
                )
                zone.overlaps(Rect(v.left, v.top, v.right, v.bottom))
            }
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { layer = it }) {
        AnimatedVisibility(
            visible = cue != null,
            enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.9f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f),
            modifier = Modifier
                .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = CARD_TOP, start = CARD_SIDE, end = CARD_SIDE),
        ) {
            // Keeps the last card while it fades out.
            var shown by remember { mutableStateOf(cue) }
            if (cue != null) shown = cue
            shown?.let { HowToCard(it, loop, still) }
        }
    }
}

/**
 * The cue on the tracked part (arrows round a cap, chevrons along a dipstick, drops, a MIN–MAX bracket). Put it
 * over the cards: the marker's part is often low on screen, under the step and answer cards. [marker] and the loop
 * are read only while drawing, so tracking and animation redraw without recomposing.
 */
@Composable
fun StepPartCueLayer(guide: GuideView?, marker: State<MarkerState?>, motion: MarkerMotion, modifier: Modifier = Modifier) {
    val cue = remember(guide) { howTo(guide) }?.takeIf { it.onPart } ?: return
    val still = reducedMotion()
    val loop = rememberInfiniteTransition(label = "partCue")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "partCueLoop")
    LaunchedEffect(cue) { Log.i(TAG, "Step cue: ${cue.glyph} ${cue.dir ?: ""} (card + on the part)") }
    Canvas(modifier.fillMaxSize()) {
        val m = marker.value ?: return@Canvas
        val t = m.targets.firstOrNull() ?: return@Canvas
        if (m.status == MarkerState.Status.Lost) return@Canvas
        val since = motion.sinceImpact(m.seedId, 0)
        if (since < CUE_AFTER_IMPACT_MS) return@Canvas
        val appear = easeOutCubic(window(since, CUE_AFTER_IMPACT_MS, 320f))
        val a = appear * (if (m.status == MarkerState.Status.Holding) 0.6f else 1f)
        val v = BoxMapper.fillCenter(m.frameWidth, m.frameHeight, size.width, size.height).map(t.box)
        val box = Rect(v.left, v.top, v.right, v.bottom)
        val p = if (still) STILL_MS / LOOP_MS else loop.value
        when (cue.glyph) {
            Glyph.Turn -> drawTurnCue(box, cue.dir, p, a)
            Glyph.Pull, Glyph.Push -> drawSlideCue(box, cue, p, a)
            Glyph.Pour -> drawPourCue(box, p, a)
            Glyph.Level -> drawLevelCue(box, p, a)
            else -> Unit
        }
    }
}

@Composable
private fun HowToCard(howTo: HowTo, loop: State<Float>, still: Boolean) {
    Crossfade(targetState = howTo, animationSpec = tween(200), label = "howTo") { h ->
        val accent = if (h.glyph == Glyph.Technician) TechRed else Amber
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .width(CARD_WIDTH)
                .clip(shape)
                .background(Ink.copy(alpha = 0.93f))
                .border(1.dp, accent.copy(alpha = 0.4f), shape)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(GLYPH_SIZE)) {
                val ms = if (still) STILL_MS else loop.value * LOOP_MS
                drawGlyph(h, ms, accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                h.label,
                color = Paper,
                maxLines = 2,
                style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            )
        }
    }
}

// ---- Pictograms: unit coordinates (0..1 of the canvas), one stroke weight, round caps ----

private fun DrawScope.drawGlyph(h: HowTo, ms: Float, accent: Color) {
    // Every loop fades in, acts, rests, and fades out before it starts again.
    val vis = window(ms, 0f, 160f) * (1f - window(ms, LOOP_MS - 380f, 330f))
    val act = easeInOutCubic(window(ms, 160f, 1050f))
    when (h.glyph) {
        Glyph.Turn -> turnGlyph(act, h.dir, vis, accent)
        Glyph.Pull -> slideGlyph(act, pull = true, h.dir, vis, accent)
        Glyph.Push -> slideGlyph(act, pull = false, h.dir, vis, accent)
        Glyph.Level -> levelGlyph(ms, vis, accent)
        Glyph.Pour -> pourGlyph(ms, vis, accent)
        Glyph.Unplug -> unplugGlyph(act, vis, accent)
        Glyph.SwitchOff -> switchOffGlyph(act, vis, accent)
        Glyph.EngineOff -> engineOffGlyph(act, vis, accent)
        Glyph.Technician -> technicianGlyph(ms, accent)
    }
}

private val DrawScope.u get() = size.minDimension
private val DrawScope.sw get() = size.minDimension * 0.05f
private fun DrawScope.pt(x: Float, y: Float) = Offset(x * u, y * u)
private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(c.x + r * cos(a), c.y + r * sin(a))
}

/** An arrowhead at [tip], pointing along [dirDeg] (screen angle of travel). */
private fun DrawScope.arrowHead(tip: Offset, dirDeg: Float, len: Float, color: Color, width: Float) {
    for (side in listOf(-1f, 1f)) drawLine(color, tip, polar(tip, len, dirDeg + 180f + side * 38f), width, StrokeCap.Round)
}

/** An arc of [sweep] degrees from [start] (positive = clockwise on screen) with a head at its leading end. */
private fun DrawScope.arrowArc(c: Offset, r: Float, start: Float, sweep: Float, color: Color, width: Float, head: Float) {
    if (abs(sweep) < 1f) return
    drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = StrokeCap.Round))
    // The head waits until the arc has some length, or it reads as a stray mark.
    if (abs(sweep) < 12f) return
    val end = start + sweep
    arrowHead(polar(c, r, end), end + if (sweep > 0) 90f else -90f, head, color, width)
}

/** Top view of a cap: grip ridges round its rim and a dot on its face turn a quarter while an arrow draws round it. */
private fun DrawScope.turnGlyph(act: Float, dir: AnimDir?, vis: Float, accent: Color) {
    val sign = if (dir == AnimDir.Cw) 1f else -1f
    val c = center
    val r = u * 0.24f
    val angle = sign * 80f * act
    drawCircle(Line.copy(alpha = Line.alpha * vis), r, c, style = Stroke(sw))
    drawCircle(Line.copy(alpha = 0.35f * vis), r * 0.62f, c, style = Stroke(sw * 0.6f))
    for (k in 0 until 16) {
        val deg = k * 22.5f + angle
        drawLine(Line.copy(alpha = 0.7f * vis), polar(c, r * 1.02f, deg), polar(c, r * 1.2f, deg), sw * 0.7f, StrokeCap.Round)
    }
    drawCircle(accent.copy(alpha = vis), u * 0.045f, polar(c, r * 0.62f, -90f + angle))
    arrowArc(c, u * 0.43f, -90f - sign * 110f, sign * 220f * act, accent.copy(alpha = vis), sw, u * 0.09f)
}

/** A rod in a tube (a dipstick, a filter): it slides out of or back into the tube; a chevron shows the way. */
private fun DrawScope.slideGlyph(act: Float, pull: Boolean, dir: AnimDir?, vis: Float, accent: Color) {
    // Drawn with "out of the tube" pointing up, then turned to the way the part comes out on screen.
    val out = outDir(pull, dir)
    val turn = when (out) { AnimDir.Right -> 90f; AnimDir.Down -> 180f; AnimDir.Left -> 270f; else -> 0f }
    val line = Line.copy(alpha = Line.alpha * vis)
    rotate(turn, center) {
        val outAmount = if (pull) act else 1f - act
        val dy = -0.2f * outAmount
        // Tube, open at the top.
        drawLine(line, pt(0.40f, 0.56f), pt(0.40f, 0.92f), sw, StrokeCap.Round)
        drawLine(line, pt(0.60f, 0.56f), pt(0.60f, 0.92f), sw, StrokeCap.Round)
        drawLine(line, pt(0.40f, 0.92f), pt(0.60f, 0.92f), sw, StrokeCap.Round)
        // Rod with a ring handle.
        drawLine(line, pt(0.5f, 0.42f + dy), pt(0.5f, 0.86f + dy), sw, StrokeCap.Round)
        drawCircle(accent.copy(alpha = vis), u * 0.07f, pt(0.5f, 0.33f + dy), style = Stroke(sw))
        // One chevron beside it, travelling the same way.
        val k = if (pull) act else 1f - act
        val cy = lerp(0.62f, 0.34f, k)
        val alpha = vis * (1f - abs(act - 0.5f) * 1.2f).coerceIn(0.25f, 1f)
        val tipDown = !pull
        val y0 = if (tipDown) cy - 0.05f else cy + 0.05f
        drawLine(accent.copy(alpha = alpha), pt(0.73f, y0), pt(0.80f, cy), sw, StrokeCap.Round)
        drawLine(accent.copy(alpha = alpha), pt(0.87f, y0), pt(0.80f, cy), sw, StrokeCap.Round)
    }
}

/** The way the part moves out of its hole on screen: a pull goes [dir] (up by default), a push comes in along it. */
private fun outDir(pull: Boolean, dir: AnimDir?): AnimDir = when {
    pull -> dir ?: AnimDir.Up
    else -> when (dir ?: AnimDir.Down) { AnimDir.Up -> AnimDir.Down; AnimDir.Left -> AnimDir.Right; AnimDir.Right -> AnimDir.Left; else -> AnimDir.Up }
}

/** A gauge with MAX and MIN marks: the level rises and settles between them, then a tick. */
private fun DrawScope.levelGlyph(ms: Float, vis: Float, accent: Color) {
    val line = Line.copy(alpha = Line.alpha * vis)
    val left = 0.38f; val right = 0.62f; val top = 0.10f; val bottom = 0.90f
    val max = 0.34f; val min = 0.60f
    // The band where the level should be.
    drawRect(Ok.copy(alpha = 0.18f * vis), pt(left, max), Size((right - left) * u, (min - max) * u))
    val rise = easeOutBack(window(ms, 160f, 1100f), 1.1f)
    val level = lerp(bottom - 0.02f, 0.47f, rise)
    clipRect(left * u + sw / 2, top * u, right * u - sw / 2, bottom * u - sw / 2) {
        drawRect(accent.copy(alpha = 0.9f * vis), pt(0f, level), Size(u, (bottom - level) * u))
    }
    drawRoundRect(line, pt(left, top), Size((right - left) * u, (bottom - top) * u), CornerRadius(u * 0.06f), style = Stroke(sw))
    for (y in listOf(max, min)) drawLine(line, pt(right - 0.06f, y), pt(right + 0.12f, y), sw * 0.8f, StrokeCap.Round)
    // A tick once it has settled.
    val done = window(ms, 1250f, 220f) * vis
    if (done > 0f) {
        val path = Path().apply { moveTo(0.14f * u, 0.47f * u); lineTo(0.20f * u, 0.53f * u); lineTo(0.29f * u, 0.41f * u) }
        drawPath(path, Ok.copy(alpha = done), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** A can with a tapered spout tips over a funnel and three drops fall in: a little at a time. */
private fun DrawScope.pourGlyph(ms: Float, vis: Float, accent: Color) {
    val line = Line.copy(alpha = Line.alpha * vis)
    val tilt = lerp(-6f, -34f, easeInOutCubic(window(ms, 120f, 480f)))
    val pivot = pt(0.66f, 0.28f)
    rotate(tilt, pivot) {
        drawRoundRect(line, pt(0.50f, 0.16f), Size(0.34f * u, 0.26f * u), CornerRadius(u * 0.05f), style = Stroke(sw))
        drawLine(line, pt(0.50f, 0.20f), pt(0.33f, 0.24f), sw, StrokeCap.Round)
        drawLine(line, pt(0.50f, 0.29f), pt(0.33f, 0.265f), sw, StrokeCap.Round)
        drawLine(line, pt(0.62f, 0.16f), pt(0.74f, 0.16f), sw * 1.5f, StrokeCap.Round)
    }
    // Where the spout ends up once tipped: the drops leave from there.
    val a = tilt * PI.toFloat() / 180f
    val sx = 0.33f - 0.66f; val sy = 0.2525f - 0.28f
    val tip = Offset(pivot.x + (sx * cos(a) - sy * sin(a)) * u, pivot.y + (sx * sin(a) + sy * cos(a)) * u)
    // Funnel under the spout: a rim, two sides and a neck.
    val fx = tip.x / u
    drawLine(line, pt(fx - 0.15f, 0.64f), pt(fx + 0.15f, 0.64f), sw, StrokeCap.Round)
    drawLine(line, pt(fx - 0.15f, 0.64f), pt(fx - 0.035f, 0.80f), sw, StrokeCap.Round)
    drawLine(line, pt(fx + 0.15f, 0.64f), pt(fx + 0.035f, 0.80f), sw, StrokeCap.Round)
    drawLine(line, pt(fx - 0.035f, 0.80f), pt(fx - 0.035f, 0.92f), sw, StrokeCap.Round)
    drawLine(line, pt(fx + 0.035f, 0.80f), pt(fx + 0.035f, 0.92f), sw, StrokeCap.Round)
    for (i in 0 until 3) {
        val f = window(ms, 560f + i * 240f, 420f)
        if (f <= 0f || f >= 1f) continue
        val y = lerp(tip.y + 0.05f * u, 0.70f * u, f * f)
        drawCircle(accent.copy(alpha = vis * (1f - window(f, 0.8f, 0.2f))), u * 0.037f, Offset(tip.x, y))
    }
}

/** A plug slides out of a wall socket; an arrow shows the way. */
private fun DrawScope.unplugGlyph(act: Float, vis: Float, accent: Color) {
    val line = Line.copy(alpha = Line.alpha * vis)
    val bx = lerp(0.35f, 0.12f, act)
    // Cord, body and pins.
    val cord = Path().apply {
        moveTo(bx * u, 0.5f * u)
        quadraticTo((bx - 0.08f) * u, 0.5f * u, maxOf(bx - 0.12f, 0.02f) * u, 0.84f * u)
    }
    drawPath(cord, line, style = Stroke(sw, cap = StrokeCap.Round))
    drawRoundRect(line, pt(bx, 0.36f), Size(0.18f * u, 0.28f * u), CornerRadius(u * 0.04f), style = Stroke(sw))
    for (y in listOf(0.44f, 0.56f)) drawLine(line, pt(bx + 0.18f, y), pt(bx + 0.28f, y), sw * 0.8f, StrokeCap.Round)
    // Socket plate on top, so the pins hide inside it.
    drawRoundRect(Ink, pt(0.58f, 0.26f), Size(0.32f * u, 0.48f * u), CornerRadius(u * 0.07f), style = Fill)
    drawRoundRect(line, pt(0.58f, 0.26f), Size(0.32f * u, 0.48f * u), CornerRadius(u * 0.07f), style = Stroke(sw))
    for (y in listOf(0.44f, 0.56f)) drawCircle(line, u * 0.028f, pt(0.72f, y))
    val arrow = window(act, 0.35f, 0.4f) * vis
    if (arrow > 0f) {
        drawLine(accent.copy(alpha = arrow), pt(0.50f, 0.86f), pt(0.20f, 0.86f), sw, StrokeCap.Round)
        arrowHead(pt(0.20f, 0.86f), 180f, u * 0.07f, accent.copy(alpha = arrow), sw)
    }
}

/** A wall switch with its indicator light: the switch presses and the light goes out. */
private fun DrawScope.switchOffGlyph(act: Float, vis: Float, accent: Color) {
    val line = Line.copy(alpha = Line.alpha * vis)
    drawRoundRect(line, pt(0.22f, 0.14f), Size(0.56f * u, 0.72f * u), CornerRadius(u * 0.08f), style = Stroke(sw))
    // The rocker dips at the press, then settles.
    val press = 1f - abs(act - 0.5f) * 2f
    val inset = 0.012f * press
    drawRoundRect(line, pt(0.38f + inset, 0.40f + inset), Size((0.24f - 2 * inset) * u, (0.36f - 2 * inset) * u), CornerRadius(u * 0.04f), style = Stroke(sw))
    // Power mark on the rocker.
    val c = pt(0.5f, 0.60f)
    drawArc(line, -60f, 300f, false, Offset(c.x - u * 0.07f, c.y - u * 0.07f), Size(u * 0.14f, u * 0.14f), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
    drawLine(line, Offset(c.x, c.y - u * 0.10f), Offset(c.x, c.y - u * 0.01f), sw * 0.8f, StrokeCap.Round)
    // Indicator light: on, then off.
    val on = 1f - act
    val led = pt(0.5f, 0.27f)
    if (on > 0f) drawCircle(accent.copy(alpha = 0.3f * on * vis), u * 0.07f, led)
    drawCircle(accent.copy(alpha = vis * lerp(0.25f, 1f, on)), u * 0.032f, led)
}

/** An ignition with its key: the key turns back from ON to OFF; the ON mark dims. */
private fun DrawScope.engineOffGlyph(act: Float, vis: Float, accent: Color) {
    val line = Line.copy(alpha = Line.alpha * vis)
    val c = center
    val r = u * 0.30f
    drawCircle(line, r, c, style = Stroke(sw))
    // OFF mark at the top, ON mark 60° clockwise.
    drawLine(line, polar(c, r * 1.12f, -90f), polar(c, r * 1.32f, -90f), sw, StrokeCap.Round)
    drawCircle(accent.copy(alpha = vis * lerp(1f, 0.3f, act)), u * 0.028f, polar(c, r * 1.22f, -30f))
    // The key: a long bow through the centre, pointing at the mark it's on.
    val angle = 60f * (1f - act)
    rotate(angle, c) {
        drawRoundRect(line, Offset(c.x - u * 0.07f, c.y - u * 0.22f), Size(u * 0.14f, u * 0.44f), CornerRadius(u * 0.07f), style = Stroke(sw))
        drawCircle(line, u * 0.026f, Offset(c.x, c.y - u * 0.12f))
    }
    arrowArc(c, u * 0.44f, -32f, -54f * act, accent.copy(alpha = vis), sw, u * 0.08f)
}

/** A spanner in a red ring; the ring sends out one soft pulse per loop. Nothing else moves. */
private fun DrawScope.technicianGlyph(ms: Float, accent: Color) {
    val c = center
    val pulse = window(ms, 200f, 1200f)
    if (pulse in 0.001f..0.999f) drawCircle(accent.copy(alpha = 0.4f * (1f - pulse)), lerp(u * 0.40f, u * 0.50f, easeOutCubic(pulse)), c, style = Stroke(sw * 0.7f))
    drawCircle(accent, u * 0.40f, c, style = Stroke(sw))
    rotate(45f, c) {
        drawLine(Line, Offset(c.x, c.y - u * 0.02f), Offset(c.x, c.y + u * 0.24f), sw * 1.5f, StrokeCap.Round)
        val jaw = Offset(c.x, c.y - u * 0.12f)
        drawArc(Line, -50f, 280f, false, Offset(jaw.x - u * 0.09f, jaw.y - u * 0.09f), Size(u * 0.18f, u * 0.18f), style = Stroke(sw * 1.3f, cap = StrokeCap.Round))
    }
}

// ---- Cues on the tracked part (screen space) ----

/** Dark under-stroke, so amber reads on any background. */
private val HALO = Ink.copy(alpha = 0.55f)

/** Two arrows circle the part in the turning direction, half a turn per loop. */
private fun DrawScope.drawTurnCue(box: Rect, dir: AnimDir?, p: Float, a: Float) {
    val sign = if (dir == AnimDir.Cw) 1f else -1f
    val big = box.minDimension > size.width * 0.5f
    val c = box.center
    val r = if (big) 44.dp.toPx() else (hypot(box.width, box.height) / 2f + 12.dp.toPx()).coerceAtMost(120.dp.toPx())
    val base = sign * 180f * p
    val w = 3.5.dp.toPx()
    for (half in 0..1) {
        val start = base + half * 180f - sign * 20f
        arrowArc(c, r, start, sign * 96f, HALO.copy(alpha = HALO.alpha * a), w + 3.dp.toPx(), 11.dp.toPx())
        arrowArc(c, r, start, sign * 96f, Amber.copy(alpha = a), w, 11.dp.toPx())
    }
}

/**
 * Chevrons streaming the way the part moves: out of its hole for a pull, into it for a push. Up and down run beside
 * the part (its name chip sits above it), on the side with room; left and right run off its edge.
 */
private fun DrawScope.drawSlideCue(box: Rect, cue: HowTo, p: Float, a: Float) {
    val pull = cue.glyph == Glyph.Pull
    val out = when (outDir(pull, cue.dir)) {
        AnimDir.Down -> Offset(0f, 1f); AnimDir.Left -> Offset(-1f, 0f); AnimDir.Right -> Offset(1f, 0f); else -> Offset(0f, -1f)
    }
    val move = if (pull) out else -out
    val vertical = out.x == 0f
    val half = 10.dp.toPx()
    val w = 3.5.dp.toPx()
    for (k in 0 until 3) {
        val phase = (p + k / 3f) % 1f
        val alpha = a * (1f - abs(phase - 0.5f) * 2f)
        val tip = if (vertical) {
            val gap = 22.dp.toPx()
            val x = if (box.right + gap + half * 2 < size.width) box.right + gap else box.left - gap
            val start = if (move.y < 0f) box.bottom - 4.dp.toPx() else box.top + 4.dp.toPx()
            Offset(x, start) + move * ((box.height + 40.dp.toPx()) * phase)
        } else {
            val edge = Offset(if (out.x > 0f) box.right else box.left, box.center.y)
            val reach = 12.dp.toPx() + 46.dp.toPx() * (if (pull) phase else 1f - phase)
            edge + out * reach
        }
        val headDeg = atan2Deg(move)
        for ((color, width) in listOf(HALO.copy(alpha = HALO.alpha * alpha) to w + 3.dp.toPx(), Amber.copy(alpha = alpha) to w)) {
            drawLine(color, tip, polar(tip, half * 1.4f, headDeg + 180f - 45f), width, StrokeCap.Round)
            drawLine(color, tip, polar(tip, half * 1.4f, headDeg + 180f + 45f), width, StrokeCap.Round)
        }
    }
}

private fun atan2Deg(v: Offset) = (atan2(v.y, v.x) * 180f / PI.toFloat())

/** Drops fall into the top of the part: pour here, a little at a time. */
private fun DrawScope.drawPourCue(box: Rect, p: Float, a: Float) {
    val x = box.center.x
    for (k in 0 until 3) {
        val f = (p + k / 3f) % 1f
        val y = lerp(box.top - 48.dp.toPx(), box.top - 2.dp.toPx(), f * f)
        val alpha = a * (1f - window(f, 0.7f, 0.3f)) * window(f, 0f, 0.15f)
        drawCircle(HALO.copy(alpha = HALO.alpha * alpha), 6.5.dp.toPx(), Offset(x, y))
        drawCircle(Amber.copy(alpha = alpha), 4.5.dp.toPx(), Offset(x, y))
    }
}

/** A MIN–MAX gauge beside the part: the level rises into the green band between the marks and holds there. */
private fun DrawScope.drawLevelCue(box: Rect, p: Float, a: Float) {
    val gap = 18.dp.toPx()
    val w = 14.dp.toPx()
    val h = (box.height * 0.9f).coerceIn(64.dp.toPx(), 150.dp.toPx())
    val left = if (box.right + gap + w + 8.dp.toPx() < size.width) box.right + gap else box.left - gap - w
    val top = box.center.y - h / 2f
    val max = top + h * 0.3f
    val min = top + h * 0.62f
    val stroke = 2.5.dp.toPx()
    drawRoundRect(HALO.copy(alpha = 0.8f * a), Offset(left - 3.dp.toPx(), top - 3.dp.toPx()), Size(w + 6.dp.toPx(), h + 6.dp.toPx()), CornerRadius(w / 2 + 3.dp.toPx()))
    drawRect(Ok.copy(alpha = 0.35f * a), Offset(left, max), Size(w, min - max))
    val ms = p * LOOP_MS
    val rise = easeOutBack(window(ms, 160f, 1100f), 1.1f) * (1f - window(ms, LOOP_MS - 380f, 330f))
    val level = lerp(top + h - 3.dp.toPx(), (max + min) / 2f, rise)
    clipRect(left, top, left + w, top + h) { drawRect(Amber.copy(alpha = 0.95f * a), Offset(left, level), Size(w, top + h - level)) }
    drawRoundRect(Paper.copy(alpha = a), Offset(left, top), Size(w, h), CornerRadius(w / 2), style = Stroke(stroke))
    for (y in listOf(max, min)) drawLine(Paper.copy(alpha = a), Offset(left - 5.dp.toPx(), y), Offset(left + w + 5.dp.toPx(), y), stroke, StrokeCap.Round)
}

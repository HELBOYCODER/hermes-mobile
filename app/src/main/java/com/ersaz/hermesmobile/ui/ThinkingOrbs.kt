package com.ersaz.hermesmobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the agent is doing right now; the orbs change choreography to show it. */
enum class OrbMode(
    /** Orbit speed multiplier. */ val speed: Float,
    /** Orbit radius as a fraction of the box. */ val spread: Float,
    /** 0 = free orbit, 1 = a horizontal "typing" wave. */ val wave: Float,
    /** Size pulse amplitude. */ val pulse: Float,
    /** Glow strength. */ val glow: Float,
) {
    Idle(0.25f, 0.20f, 0f, 0.05f, 0.55f),
    Thinking(1.0f, 0.34f, 0f, 0.12f, 0.9f),
    Tool(2.1f, 0.22f, 0f, 0.20f, 1.0f),     // tight, fast ring: work in progress
    Writing(1.6f, 0.38f, 1f, 0.25f, 1.0f),  // a travelling wave, like keystrokes
    Waiting(0.45f, 0.10f, 0f, 0.30f, 0.8f), // gathered and breathing: needs the user
    Error(3.2f, 0.28f, 0f, 0.35f, 1.0f),
}

private const val ORBS = 5

/**
 * Animated thinking orbs: a handful of glowing, additively blended spheres on Lissajous orbits.
 * The motion morphs smoothly between [OrbMode]s (speed, spread, wave, pulse are all animated),
 * and colors follow the active skin — warn/error tint for Waiting/Error.
 */
@Composable
fun ThinkingOrbs(mode: OrbMode, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val spec = tween<Float>(650, easing = FastOutSlowInEasing)
    val speed by animateFloatAsState(mode.speed, spec, label = "speed")
    val spread by animateFloatAsState(mode.spread, spec, label = "spread")
    val wave by animateFloatAsState(mode.wave, spec, label = "wave")
    val pulse by animateFloatAsState(mode.pulse, spec, label = "pulse")
    val glow by animateFloatAsState(mode.glow, spec, label = "glow")
    val tint by animateFloatAsState(when (mode) { OrbMode.Waiting -> 1f; OrbMode.Error -> 2f; else -> 0f }, spec, label = "tint")

    // Integrate speed over time so changing speed never makes the orbs jump.
    var phase by remember { mutableFloatStateOf(0f) }
    var last by remember { mutableLongStateOf(0L) }
    val speedNow by androidx.compose.runtime.rememberUpdatedState(speed)
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) phase += ((now - last) / 1_000_000_000f) * speedNow
                last = now
            }
        }
    }

    val palette = listOf(skin.accent, skin.title, skin.label, skin.ok, skin.border)
    val warn = skin.warn; val err = skin.error
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = this.size.minDimension
        val c = Offset(this.size.width / 2, this.size.height / 2)
        for (i in 0 until ORBS) {
            val f = i / ORBS.toFloat()
            val a = phase * 2f * PI.toFloat()
            // Free orbit: each orb has its own frequency pair, so the cluster never repeats exactly.
            val ox = sin(a * (0.55f + 0.17f * i) + f * 6.28f) * spread * w
            val oy = cos(a * (0.43f + 0.21f * i) + f * 4.1f) * spread * w * 0.85f
            // Wave: orbs line up horizontally and bob in sequence.
            val wx = (f - 0.4f) * 1.0f * w * 0.9f
            val wy = sin(a * 1.4f - f * 5f) * w * 0.14f
            val px = c.x + ox * (1 - wave) + wx * wave
            val py = c.y + oy * (1 - wave) + wy * wave
            val r = w * (0.17f + 0.04f * (i % 3)) * (1f + pulse * sin(a * 1.1f + i * 1.7f))
            val base = palette[i % palette.size]
            val col = when {
                tint > 1f -> lerp(warn, err, tint - 1f)
                tint > 0f -> lerp(base, warn, tint)
                else -> base
            }
            drawCircle(
                Brush.radialGradient(
                    0f to col.copy(alpha = 0.95f * glow), 0.45f to col.copy(alpha = 0.40f * glow), 1f to Color.Transparent,
                    center = Offset(px, py), radius = r * 2.4f,
                ),
                radius = r * 2.4f, center = Offset(px, py), blendMode = BlendMode.Plus,
            )
            drawCircle(Color.White.copy(alpha = 0.55f * glow), r * 0.28f, Offset(px - r * 0.15f, py - r * 0.18f), blendMode = BlendMode.Plus)
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    a.red + (b.red - a.red) * t.coerceIn(0f, 1f), a.green + (b.green - a.green) * t.coerceIn(0f, 1f),
    a.blue + (b.blue - a.blue) * t.coerceIn(0f, 1f), a.alpha + (b.alpha - a.alpha) * t.coerceIn(0f, 1f),
)

/**
 * The chat's "thinking" indicator: orbs plus a live caption — a rotating skin verb while thinking,
 * the tool being run (with its argument preview) during tool calls, and a seconds counter.
 */
@Composable
fun ThinkingIndicator(mode: OrbMode, detail: String = "", modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    var tick by remember { mutableFloatStateOf(0f) }
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds += 1f; if (seconds.toInt() % 3 == 0) tick++ } }
    val verb = skin.thinkingVerbs[tick.toInt() % skin.thinkingVerbs.size]
    val title = when (mode) {
        OrbMode.Tool -> "working"
        OrbMode.Writing -> "writing"
        OrbMode.Waiting -> "waiting for you"
        OrbMode.Error -> "something went wrong"
        else -> verb
    }
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThinkingOrbs(mode, 52.dp)
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title, label = "caption",
                transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 2 }) },
            ) { t ->
                Text("$t…", color = if (mode == OrbMode.Error) skin.error else if (mode == OrbMode.Waiting) skin.warn else skin.accent,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Text(
                (if (detail.isNotBlank()) detail else "") + (if (seconds >= 3f) (if (detail.isNotBlank()) " · " else "") + "${seconds.toInt()}s" else ""),
                color = skin.dim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

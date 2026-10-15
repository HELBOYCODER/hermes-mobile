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
    val color = when (mode) {
        OrbMode.Error -> skin.error
        OrbMode.Waiting -> skin.warn
        else -> skin.accent
    }
    androidx.compose.foundation.layout.Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (mode == OrbMode.Idle || mode == OrbMode.Waiting || mode == OrbMode.Error) {
            Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        } else {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size((size.value * 0.45f).coerceIn(12f, 22f).dp),
                color = color, strokeWidth = 2.dp)
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

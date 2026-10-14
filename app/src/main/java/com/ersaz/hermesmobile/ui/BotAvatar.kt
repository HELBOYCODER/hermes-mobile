package com.ersaz.hermesmobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ersaz.hermesmobile.bots.Bot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val BotColors = listOf(
    Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF06B6D4), Color(0xFF10B981), Color(0xFF84CC16),
    Color(0xFFF59E0B), Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFEC4899), Color(0xFF64748B),
)

/**
 * Geometric bot face: 7 shapes × 10 colors with eyes that blink — and scan left/right while the bot is working.
 */
@Composable
fun BotAvatar(bot: Bot, size: Dp = 44.dp, working: Boolean = false, animate: Boolean = false, modifier: Modifier = Modifier) =
    BotAvatar(bot.shape, bot.color, size, working, animate, modifier)

@Composable
fun BotAvatar(shape: Int, color: Int, size: Dp = 44.dp, working: Boolean = false, animate: Boolean = false, modifier: Modifier = Modifier) {
    val fill = BotColors[color.mod(BotColors.size)]
    // One transition drives both the scan (working) and the blink (animate).
    val tr = rememberInfiniteTransition(label = "avatar")
    val scan by tr.animateFloat(-1f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "scan")
    val blink by tr.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4200; 1f at 0; 1f at 3900; 0.1f at 4000; 1f at 4100
    }), label = "blink")
    Canvas(modifier.size(size)) {
        drawShape(shape.mod(Bot.SHAPES), fill)
        val w = this.size.width
        val eyeY = this.size.height * 0.46f
        val dx = if (working) scan * w * 0.07f else 0f
        val ry = w * 0.065f * (if (animate || working) blink else 1f)
        for (side in listOf(-1f, 1f)) {
            val cx = w / 2 + side * w * 0.17f + dx
            drawOval(Color.White, Offset(cx - w * 0.065f, eyeY - ry), Size(w * 0.13f, ry * 2))
            drawCircle(Color(0xFF111827), w * 0.03f, Offset(cx + dx * 0.4f, eyeY))
        }
    }
}

private fun DrawScope.drawShape(shape: Int, c: Color) {
    val w = size.width; val h = size.height
    when (shape) {
        0 -> drawCircle(c, w / 2)
        1 -> drawRoundRect(c, Offset(w * 0.05f, h * 0.05f), Size(w * 0.9f, h * 0.9f), CornerRadius(w * 0.22f))
        2 -> drawPath(poly(w, h, 4, -PI / 2), c)             // diamond
        3 -> drawPath(poly(w, h, 6, 0.0), c)                 // hexagon
        4 -> drawPath(poly(w, h, 3, -PI / 2), c)             // triangle
        5 -> drawRoundRect(c, Offset(0f, h * 0.15f), Size(w, h * 0.7f), CornerRadius(h * 0.35f)) // pill
        else -> drawPath(poly(w, h, 8, PI / 8), c)           // octagon
    }
}

private fun poly(w: Float, h: Float, sides: Int, rotation: Double): Path {
    val p = Path()
    val r = minOf(w, h) / 2 * 0.98f
    for (i in 0 until sides) {
        val a = rotation + 2 * PI * i / sides
        val x = w / 2 + (r * cos(a)).toFloat()
        val y = h / 2 + (r * sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

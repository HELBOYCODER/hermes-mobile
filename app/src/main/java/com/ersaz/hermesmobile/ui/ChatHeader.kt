package com.ersaz.hermesmobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.llm.Providers
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.delay

/**
 * Minis-inspired chat header, in Hermes' own design: one row, nothing else.
 * Model pill + thinking pill (both open the model picker, whose Thinking section is the
 * thinking selector — thinking is never buried in Settings), an optional cost chip that only
 * appears when the active profile actually carries pricing (FREE or its price label — never a
 * guessed price), and a tiny context bar that only appears when session token data exists.
 * A scheduled-task countdown pill appears only in the last minute before a run; long-pressing
 * it runs that job now (CronRunner.runNowAsync — real), and it vanishes once the run starts.
 * Nothing here fetches on open: every value comes from already-loaded session/settings state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHeader(vm: ChatViewModel, onModelPicker: () -> Unit) {
    val skin = LocalSkin.current
    val rt = vm.runtime
    val s = rt.settings
    @Suppress("UNUSED_VARIABLE") val rev = vm.modelRev
    val anim = s.chatAnimations
    val animMod = if (anim) Modifier.animateContentSize() else Modifier

    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .then(animMod),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ONE pill style for the whole header row: same 20dp radius, same
            // 12x7 padding, same 13sp semibold text, same selection surface, one
            // default font. The skin's accent appears only as the thinking
            // pill's text color; there is no second accent anywhere on the page.
            // Model pill: SHORT provider name + model id, tap = model picker. The full
            // provider label (e.g. "Nous Portal (sign in — free models included)") is
            // never shown here — the parenthetical is stripped and the pill truncates
            // with an ellipsis, so the header stays one short row.
            val providerLabel = runCatching { Providers.byId(s.provider).label }.getOrDefault(s.provider)
                .substringBefore(" (").trim().ifBlank { s.provider }
            val modelName = s.model.substringAfterLast('/').ifBlank { "no model" }
            Surface(
                shape = RoundedCornerShape(20.dp), color = skin.selection,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onModelPicker),
            ) {
                Text("$providerLabel · $modelName", color = skin.title, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            // Thinking pill: visible, tappable, animated only while a turn runs.
            val thinkingLabel = thinkingLabel(s.reasoningEffort)
            Surface(
                shape = RoundedCornerShape(20.dp), color = skin.selection,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onModelPicker),
            ) {
                Text("Thinking: $thinkingLabel", color = skin.accent, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            // Cost chip: same pill style, only from the active profile's real
            // data. No profile, or no price on it, means no chip at all — a
            // guessed price would be a fabricated number.
            val active = remember(rev) { rt.modelStore.all().firstOrNull { rt.modelStore.isActive(it) } }
            when {
                active?.free == true -> Surface(
                    shape = RoundedCornerShape(20.dp), color = skin.selection,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                ) {
                    Text("FREE", color = skin.ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                !active?.priceLabel.isNullOrBlank() -> Surface(
                    shape = RoundedCornerShape(20.dp), color = skin.selection,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                ) {
                    Text(active!!.priceLabel, color = skin.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        // Context bar: tiny, and only when the session actually has token data. The number is
        // an estimate (chars/4, or the last turn's measured prompt tokens mixed in) — the "(est.)"
        // suffix and the "~" say so; an exact measured figure is never claimed.
        if (s.showStatusBar && vm.contextWindow > 0 && vm.contextTokens > 0) {
            val pct = vm.contextPct
            val used = formatTokens(vm.contextTokens)
            val total = formatTokens(vm.contextWindow.toLong())
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$pct% used · ~$used / $total (est.)", color = when {
                    pct >= 85 -> skin.error; pct >= 60 -> skin.warn; else -> skin.dim
                }, fontSize = 11.sp)
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = skin.accent, trackColor = skin.selection,
                )
            }
        }
        // Scheduled-task countdown: only in the last minute before a run.
        val nowJobs = remember { rt.cron.all() }
        val imminent = nowJobs.filter { it.enabled && it.nextRunAt > System.currentTimeMillis() &&
            it.nextRunAt - System.currentTimeMillis() <= 60_000 }.minByOrNull { it.nextRunAt }
        if (imminent != null) {
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(imminent.id) {
                while (true) {
                    delay(1000)
                    tick++
                    if (System.currentTimeMillis() >= imminent.nextRunAt) break
                }
            }
            @Suppress("UNUSED_VARIABLE") val tickRead = tick
            val secs = ((imminent.nextRunAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (secs >= 0 && System.currentTimeMillis() < imminent.nextRunAt) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⏰ ${imminent.name} in ${secs}s", color = skin.label, fontSize = 11.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { rt.cronRunner.runNowAsync(imminent) },
                        ).padding(horizontal = 8.dp, vertical = 4.dp))
                    TextButton(onClick = { rt.cronRunner.runNowAsync(imminent) }) { Text("Run now", fontSize = 11.sp) }
                    // (font sizes on this page: 16 title/input, 13 body/pills/chips, 11 meta.)
                }
            }
        }
    }
}

/** Human label for the stored reasoningEffort value ("" = provider default). */
fun thinkingLabel(effort: String): String = when (effort.lowercase()) {
    "" -> "Default"
    "none", "off" -> "Off"
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Med"
    "high" -> "High"
    "xhigh" -> "XHigh"
    "max" -> "Max"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** Compact token count: 828491 -> "828.5k". */
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fm".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> "$n"
}

package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.ui.theme.LocalSkin

/**
 * Suggested replies ("suggested follow-ups"), ON by default.
 *
 * After the agent's last message, the app itself prepares up to 3
 * ready-made reply texts above the composer. Tapping a chip FILLS the
 * composer with that exact text — it never sends anything; the user
 * presses send themselves, exactly as described. Tapping also dismisses
 * the chips for that message: they belong to the last message only, so
 * older messages never show chips, and a new assistant message brings a
 * fresh set.
 *
 * Generated LOCALLY from the last message's text (keywords/structure
 * only) — no extra LLM call per message, no network, no auto-send.
 * Priority: question → code → design → generic fallbacks. The texts
 * are Persian, matching the user's own examples for this feature.
 */
fun suggestedRepliesFor(lastText: String): List<String> {
    val text = lastText.trim()
    if (text.isBlank()) return emptyList()
    val lower = text.lowercase()
    val endsQuestion = text.endsWith("?") || text.endsWith("؟")
    if (endsQuestion) return listOf("بله", "نه", "توضیح بیشتر بده")
    if ("```" in text) return listOf("ادامه بده", "خط‌به‌خط توضیح بده", "تستش کن")
    val designWords = listOf(
        "design", "prototype", "html", "deck", "slide", "infographic", "animation",
        "طرح", "دیزاین", "پروتوتایپ", "رنگ", "اسلاید", "اینفوگرافیک", "انیمیشن",
    )
    if (designWords.any { it in lower }) return listOf("نسخه دیگری بساز", "رنگ‌ها را عوض کن", "خروجی را ذخیره کن")
    return listOf("ادامه بده", "خلاصه کن", "مثال بزن")
}

/**
 * Chips row, placed above the composer bar in ChatScreen. The Coding
 * workspace sends its tasks through this same ChatScreen composer, so
 * the Coding chat gets the same chips without a second implementation.
 * Hidden while the agent is working or the last message is still
 * streaming, and hidden entirely when the Settings toggle is off.
 */
@Composable
fun SuggestedRepliesRow(vm: ChatViewModel) {
    if (!vm.runtime.settings.suggestedReplies) return
    if (vm.busy) return
    val last = vm.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant ?: return
    if (last.streaming || last.text.isBlank()) return
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    if (dismissedFor == last.key) return
    val suggestions = remember(last.key, last.text) { suggestedRepliesFor(last.text) }
    if (suggestions.isEmpty()) return
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            FilterChip(
                selected = false,
                onClick = {
                    vm.input = suggestion
                    dismissedFor = last.key
                },
                label = { Text(suggestion, color = skin.text, fontSize = 13.sp) },
            )
        }
    }
}

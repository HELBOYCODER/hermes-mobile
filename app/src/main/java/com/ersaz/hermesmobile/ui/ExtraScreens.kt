package com.ersaz.hermesmobile.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.Request

/** Token usage per model over the last week / month. */
@Composable
fun UsageScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    var days by remember { mutableIntStateOf(7) }
    val rows = remember(days) { rt.usage.rows(days) }
    val byModel = rows.groupBy { it.model }.map { (m, r) -> Triple(m, r.sumOf { it.calls }, r.sumOf { it.prompt } to r.sumOf { it.completion }) }.sortedByDescending { it.third.first + it.third.second }
    val byDay = rows.groupBy { it.day }.mapValues { (_, r) -> r.sumOf { it.prompt + it.completion } }
    val maxDay = byDay.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { d -> if (days == d) Button(onClick = { days = d }) { Text("$d days") } else OutlinedButton(onClick = { days = d }) { Text("$d days") } }
        }
        SectionTitle("Totals")
        Card {
            Text("${"%,d".format(rows.sumOf { it.calls })} API calls", color = skin.text)
            Text("${"%,d".format(rows.sumOf { it.prompt })} prompt tokens · ${"%,d".format(rows.sumOf { it.completion })} completion tokens", color = skin.dim, fontSize = 12.sp)
        }
        SectionTitle("By day")
        byDay.toSortedMap().forEach { (day, tokens) ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(day.drop(5), color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(10.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth(tokens.toFloat() / maxDay).height(10.dp)
                            .background(skin.accent, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)),
                    )
                }
                Text(" ${"%,d".format(tokens)}", color = skin.text, fontSize = 11.sp)
            }
        }
        SectionTitle("By model")
        byModel.forEach { (m, calls, tokens) ->
            Card {
                Text(m, color = skin.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("$calls calls · ${"%,d".format(tokens.first)} in · ${"%,d".format(tokens.second)} out", color = skin.dim, fontSize = 12.sp)
            }
        }
        if (rows.isEmpty()) Text("No usage recorded yet.", color = skin.dim)
        Spacer(Modifier.height(40.dp))
    }
}

/** Latest GitHub release of this app, for the "Check for updates" button. */
suspend fun checkForUpdate(rt: HermesRuntime): String = withContext(Dispatchers.IO) {
    try {
        rt.http.newCall(Request.Builder().url("https://api.github.com/repos/HELBOYCODER/hermes-mobile/releases/latest").header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (r.code == 404) return@use "No releases published yet."
            if (!r.isSuccessful) return@use "Couldn't check (HTTP ${r.code})."
            val j = JSONObject(r.body?.string().orEmpty())
            val tag = j.optString("tag_name")
            "Latest release: $tag — ${j.optString("html_url")}\nInstalled: ${HermesRuntime.VERSION}"
        }
    } catch (e: Exception) { "Couldn't check: ${e.message}" }
}

val APK_MIRRORS = listOf(
    "" to "Official (dl-cdn.alpinelinux.org)",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine" to "TUNA (Tsinghua)",
    "https://mirrors.ustc.edu.cn/alpine" to "USTC",
    "https://mirrors.aliyun.com/alpine" to "Aliyun",
    "https://mirror.yandex.ru/mirrors/alpine" to "Yandex",
)

/** Points apk at [base] (or the official CDN when blank) for the installed Alpine release. */
suspend fun applyApkMirror(rt: HermesRuntime, base: String): String {
    val b = base.ifBlank { "https://dl-cdn.alpinelinux.org/alpine" }.trimEnd('/')
    val r = rt.sandbox.exec("V=\$(cut -d. -f1,2 /etc/alpine-release); printf '%s\\n' \"$b/v\$V/main\" \"$b/v\$V/community\" > /etc/apk/repositories && cat /etc/apk/repositories", timeoutSec = 30)
    return if (r.exitCode == 0) "Package mirror set:\n${r.output.trim()}" else "Failed: ${r.output.take(200)}"
}

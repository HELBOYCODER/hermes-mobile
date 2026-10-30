package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.SkillStore
import com.ersaz.hermesmobile.sandbox.Sandbox
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import java.io.File
import kotlinx.coroutines.launch

/**
 * "Download style samples" for the huashu-design skill only (shown in
 * the skill's detail/editing view in the Skills screen).
 *
 * The heavy Huashu assets (~40 MB: style specimens, showcases, sfx/bgm)
 * are deliberately NOT in the APK. This action fetches them on demand,
 * user-initiated, from the repo's raw files into the sandbox at
 * /root/huashu-samples/<repo path>, using the exact file list in the
 * bundled HERMES-ASSET-MANIFEST.txt (2026-10-30 snapshot — more
 * accurate than parsing design-styles.md for sheet names at runtime).
 * Progress is the live command tail plus a final downloaded/failed
 * count; failures are listed honestly, never hidden. A re-run resumes
 * only the missing files (curl --fail, existing files skipped only if
 * their size already matches the manifest — otherwise re-downloaded).
 */
@Composable
fun HuashuDownloadRow(rt: HermesRuntime, skill: SkillStore.Skill) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tail by remember { mutableStateOf("") }

    val manifest = remember(skill) {
        val f = File(skill.dir, "HERMES-ASSET-MANIFEST.txt")
        if (!f.isFile) emptyList()
        else f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
            }
    }
    val samples = manifest.filter { it.first.startsWith("assets/style-specimens/") || it.first.startsWith("assets/showcases/") }
    val audio = manifest.filter { it.first.startsWith("assets/sfx/") || it.first.startsWith("assets/bgm-") }

    fun download(files: List<Pair<String, Long>>, label: String) {
        if (files.isEmpty()) { status = "No $label files listed in the manifest."; return }
        busy = true
        status = "Downloading $label (${files.size} files) into /root/huashu-samples/…"
        scope.launch {
            // One sandbox script: ensure curl, then loop. Each file is
            // skipped only when its size already matches the manifest.
            val script = buildString {
                append("command -v curl >/dev/null 2>&1 || apk add --no-cache curl >/dev/null 2>&1; ")
                append("mkdir -p /root/huashu-samples; ok=0; fail=0; ")
                for ((path, size) in files) {
                    val q = Sandbox.shQuote(path)
                    append("if [ -f /root/huashu-samples/$q ] && [ \$(stat -c %s /root/huashu-samples/$q 2>/dev/null || echo -1) -eq $size ]; then ok=\$((ok+1)); ")
                    append("else mkdir -p /root/huashu-samples/\$(dirname $q); ")
                    append("if curl -sSL --fail -o /root/huashu-samples/$q https://raw.githubusercontent.com/alchaincyf/huashu-design/main/$q; then ok=\$((ok+1)); else echo FAILED $q; fail=\$((fail+1)); fi; fi; ")
                }
                append("echo DOWNLOAD-DONE ok=\$ok fail=\$fail")
            }
            val r = rt.sandbox.exec(script, timeoutSec = 900) { chunk -> tail = (tail + chunk).takeLast(1500) }
            val failed = r.output.lines().filter { it.startsWith("FAILED ") }
            status = "Download $label finished (exit ${r.exitCode}): " +
                (r.output.lines().firstOrNull { it.startsWith("DOWNLOAD-DONE") } ?: "no summary") +
                if (failed.isNotEmpty()) "\nFailed (${failed.size}):\n" + failed.take(20).joinToString("\n") else "\nAll files downloaded."
            busy = false
        }
    }

    Column {
        Text("On-demand assets (not in the APK)", color = skin.title, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Style specimens, showcases and audio live in the repo, not the app. Download them into the sandbox when you need them; the agent then references /root/huashu-samples/. Source: https://github.com/alchaincyf/huashu-design",
            color = skin.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(enabled = !busy, onClick = { download(samples, "style samples") }) { Text("Download style samples (${samples.size})") }
            OutlinedButton(enabled = !busy, onClick = { download(audio, "audio sfx/bgm") }) { Text("Download audio (${audio.size})") }
        }
        if (manifest.isEmpty()) Text("Manifest missing: the asset list (HERMES-ASSET-MANIFEST.txt) was not installed with this skill. Browse the repo directly: https://github.com/alchaincyf/huashu-design",
            color = skin.label, fontSize = 12.5.sp)
        if (status.isNotBlank()) Text(status, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
        if (tail.isNotBlank()) Text(tail, color = skin.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

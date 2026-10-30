package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.ReaMode
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reverse engineering workspace — Hermes' own UI over the on-device
 * Alpine sandbox. ON by default: it sits in Coding, and in chat the
 * agent switches into REA mode when the user says "reverse engineer
 * this" (see Agent prompt routing + the bundled rea skill).
 *
 * Local-only: every command below runs inside the sandbox on this
 * phone. No binary, sample, or analysis output is uploaded anywhere.
 *
 * Honest desktop limits, stated in the UI, never blurred: Ghidra
 * headless needs a desktop Java install and gigabytes of disk — it is
 * NOT bundled and NOT claimed to run on-phone. Hopper is a macOS
 * commercial tool and cannot run on Android at all. "Export analysis
 * package" produces the metadata + evidence + REA-style report as a
 * ZIP so the user can continue in Ghidra/Hopper on desktop.
 */
@Composable
fun ReaPanel(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var targetPath by remember { mutableStateOf("") }
    var identified by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<ReaMode.Target?>(null) }
    var steps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    SectionTitle("Reverse engineering")
    Hint("Drop a file into chat (it lands in /root/uploads) or type its sandbox path below. The workspace identifies it by magic bytes first, extension second — never by name alone — then runs the on-phone toolchain in the Alpine sandbox. Local-only: nothing is uploaded. Desktop steps (Ghidra headless, Hopper, full APK decompilation) are NOT on-phone; use Export analysis package to continue there.")
    OutlinedTextField(targetPath, { targetPath = it }, singleLine = true,
        label = { Text("Target file (sandbox path)") }, placeholder = { Text("/root/uploads/app.apk") },
        modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && targetPath.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                status = try {
                    val host = withContext(Dispatchers.IO) { rt.sandbox.hostPath(targetPath.trim()) }
                    if (!host.isFile) "File not found in the sandbox: ${targetPath.trim()}"
                    else {
                        val header = withContext(Dispatchers.IO) {
                            host.inputStream().use { it.readNBytes(16) }
                        }
                        val t = ReaMode.identify(host.name, header)
                        target = t
                        identified = "Identified: ${t.label} (magic bytes checked, extension ${host.extension.ifBlank { "(none)" }})"
                        "Identified: ${t.label}"
                    }
                } catch (e: Exception) { "Identify failed: ${e.message}" }
                busy = false
            }
        }) { Text("Identify") }
        OutlinedButton(enabled = !busy && target != null, onClick = {
            val t = target ?: return@OutlinedButton
            busy = true; status = "Running ${t.label} analysis…"
            scope.launch {
                val q = ReaMode.q(targetPath.trim())
                val out = mutableListOf<Pair<String, String>>()
                for ((label, cmd) in ReaMode.analysisSteps(t, q)) {
                    val r = rt.sandbox.exec(cmd)
                    out += label to (if (r.exitCode == 0) r.output else "exit ${r.exitCode}:\n${r.output}")
                }
                steps = out
                status = "Analysis done: ${out.size} steps ran on-phone. Unverified claims stay hypotheses — see the report format in chat."
                busy = false
            }
        }) { Text(if (busy) "Running…" else "Run analysis") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val r = rt.sandbox.exec(ReaMode.ensureBinutilsCommand())
                status = "Binutils install attempt (on-phone, verbatim):\n${r.output.take(2000)}"
                busy = false
            }
        }) { Text("Install binutils (phone)") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val r = rt.sandbox.exec(ReaMode.tryDecompilersCommand())
                status = "Decompiler attempt (on-phone, verbatim):\n${r.output.take(2000)}\nIf apktool/jadx are still missing, full decompilation needs a desktop run — it is not faked here."
                busy = false
            }
        }) { Text("Try apktool / jadx") }
        OutlinedButton(enabled = !busy && target != null, onClick = {
            busy = true
            scope.launch {
                status = try {
                    val dir = withContext(Dispatchers.IO) {
                        val d = File(ctx.cacheDir, "rea-package").also { it.mkdirs() }
                        File(d, "metadata.txt").writeText(
                            "Target: ${targetPath.trim()}\nIdentified: ${target?.label}\nRan on-phone: ${steps.joinToString { it.first }}\nLocal-only: this package was produced inside the on-device sandbox; nothing was uploaded.\n")
                        File(d, "evidence.txt").writeText(steps.joinToString("\n\n") { "== ${it.first} ==\n${it.second}" })
                        File(d, "report.md").writeText(
                            ReaMode.reportSkeleton(targetPath.trim(), target?.label ?: "unknown") +
                                "\n\n## Evidence collected on-phone\nSee evidence.txt in this package.\n")
                        File(d, "README.txt").writeText(
                            "REA analysis package (phone equivalent).\nRan on-phone: ${steps.joinToString { it.first }}.\nNeeds desktop: Ghidra headless (desktop Java + gigabytes of disk), Hopper (macOS commercial, cannot run on Android), full APK decompilation (apktool/jadx) if the on-phone attempt above failed.\n")
                        val tmp = File(ctx.cacheDir, "rea-analysis-package.zip")
                        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                            d.walkTopDown().filter { it.isFile }.forEach { f ->
                                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(d).invariantSeparatorsPath))
                                f.inputStream().use { it.copyTo(z) }
                                z.closeEntry()
                            }
                        }
                        tmp
                    }
                    val uri = com.ersaz.hermesmobile.files.DownloadsExporter.save(ctx, dir)
                    "Exported rea-analysis-package.zip to Downloads/Hermes ($uri)."
                } catch (e: Exception) { "Export failed: ${e.message}" }
                busy = false
            }
        }) { Text("Export analysis package") }
    }
    if (identified.isNotBlank()) Text(identified, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    if (status.isNotBlank()) Text(status, color = skin.label, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
    if (steps.isNotEmpty()) {
        Text("Per-target tool matrix (what ran on-phone):", color = skin.title, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())) {
            steps.forEach { (label, out) ->
                Text("== $label ==", color = skin.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(out.take(4000), color = skin.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
            }
        }
        Text("Desktop-only (not on-phone): Ghidra headless, Hopper, full APK decompilation if the attempt above failed. The export package carries this analysis to desktop.", color = skin.dim, fontSize = 12.sp)
    }
}

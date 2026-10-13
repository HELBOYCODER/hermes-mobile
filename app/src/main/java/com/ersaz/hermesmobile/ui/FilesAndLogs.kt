package com.ersaz.hermesmobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val TEXT_LIMIT = 200_000

/** Browse and manage the Linux sandbox's files: view, share, import from the phone, export, delete. */
@Composable
fun FilesScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = rt.sandbox.rootfs
    var dir by remember { mutableStateOf(File(root, "root")) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    val entries = remember(dir, refresh) { (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            uris.forEach { u ->
                val name = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                ctx.contentResolver.openInputStream(u)?.use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
            }
            msg = "Imported ${uris.size} file(s)"; refresh++
        }
    }
    var exportSource by remember { mutableStateOf<File?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val src = exportSource ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }; msg = "Exported ${src.name}" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = dir.canonicalPath != root.canonicalPath, onClick = { dir = dir.parentFile ?: dir }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "up", tint = skin.accent) }
            Text(rt.sandbox.guestPath(dir).ifBlank { "/" }, color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { newFolder = "" }) { Icon(Icons.Default.CreateNewFolder, "new folder", tint = skin.label) }
            IconButton(onClick = { importer.launch("*/*") }) { Icon(Icons.Default.UploadFile, "import", tint = skin.label) }
        }
        if (msg.isNotBlank()) Text(msg, color = skin.ok, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(color = skin.border.copy(alpha = 0.4f))
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f else selected = f }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (f.isDirectory) "📁" else if (f.extension.lowercase() in IMAGE_EXT) "🖼️" else "📄", fontSize = 18.sp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(f.name, color = skin.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (f.isFile) humanSize(f.length()) + " · " else "") + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())), color = skin.dim, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = skin.border.copy(alpha = 0.15f))
            }
        }
    }

    selected?.let { f ->
        val isImage = f.extension.lowercase() in IMAGE_EXT
        val text = remember(f) { if (isImage || f.length() > 5_000_000) null else runCatching { f.inputStream().use { it.readNBytes(TEXT_LIMIT) }.toString(Charsets.UTF_8) }.getOrNull() }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(rt.sandbox.guestPath(f) + " · " + humanSize(f.length()), color = skin.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (isImage) MarkdownText("![${f.name}](${rt.sandbox.guestPath(f)})")
                    else if (text != null) Text(text, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
                    else Text("No preview for this file type.", color = skin.dim)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val shared = File(ctx.cacheDir, "shared").apply { mkdirs() }; val c = File(shared, f.name).also { f.copyTo(it, true) }
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", c)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                    }) { Text("Share") }
                    TextButton(onClick = { exportSource = f; exporter.launch(f.name) }) { Text("Save…") }
                    TextButton(onClick = { f.deleteRecursively(); selected = null; refresh++ }) { Text("Delete", color = skin.error) }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    newFolder?.let { name ->
        var n by remember { mutableStateOf(name) }
        AlertDialog(onDismissRequest = { newFolder = null }, title = { Text("New folder") }, text = { Field("Name", n) { n = it } },
            confirmButton = { TextButton(onClick = { if (n.isNotBlank() && '/' !in n) File(dir, n.trim()).mkdirs(); newFolder = null; refresh++ }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } })
    }
}

private fun humanSize(b: Long) = when { b < 1024 -> "$b B"; b < 1_048_576 -> "${b / 1024} KB"; else -> "%.1f MB".format(b / 1_048_576.0) }

/** In-app log of the things that tend to fail quietly: MCP connections, sign-ins, sub-agents, the browser. */
@Composable
fun LogsScreen(rt: HermesRuntime) {
    val skin = LocalSkin.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val lines = remember(tick) { rt.log.lines() }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")), "Share log")) }) { Text("Share") }
            TextButton(onClick = { rt.log.clear(); tick++ }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(lines.asReversed()) { Text(it, color = if (" E/" in it) skin.error else skin.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp)) }
        }
    }
}

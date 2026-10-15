package com.ersaz.hermesmobile.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.files.DownloadsExporter
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GeneratedFileCard(path: String) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val rt = remember(context) { HermesRuntime.get(context) }
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun download() {
        if (busy) return
        busy = true
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val snapshot = if (rt.generatedFiles.isDelivered(file)) file else rt.generatedFiles.capture(file)
                    DownloadsExporter.save(context, snapshot)
                }
                "Saved to Downloads/Hermes"
            } catch (error: Exception) { "Download failed: ${error.message}" }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) download() else message = "Storage permission is required on this Android version."
    }
    fun open(share: Boolean) {
        runCatching {
            require(file.isFile) { "File unavailable" }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(DownloadsExporter.mime(file)).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, DownloadsExporter.mime(file))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            context.startActivity(if (share) Intent.createChooser(intent, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        }.onFailure { message = it.message ?: "No app can open this file" }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = skin.accent, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(file.name, color = skin.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (file.isFile) formatSize(file.length()) else "File unavailable", color = skin.dim, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && file.isFile, onClick = {
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else download()
            }) { Text(if (busy) "Saving…" else "Download") }
            TextButton(enabled = file.isFile, onClick = { open(false) }) { Text("Open") }
            TextButton(enabled = file.isFile, onClick = { open(true) }) { Text("Share") }
        }
        if (message.isNotBlank()) Text(message, color = skin.label, fontSize = 12.sp)
    }
}

package com.ersaz.hermesmobile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.media.WhisperModelStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Composable
fun MediaModelPanel(rt: HermesRuntime) {
    val scope = rememberCoroutineScope()
    val directory = remember { File(rt.app.filesDir, "media-models") }
    val model = remember { File(directory, "ggml-tiny.bin") }
    var installed by remember { mutableStateOf(model.isFile) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val activeCall = remember { java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null) }
    DisposableEffect(Unit) { onDispose { activeCall.getAndSet(null)?.cancel(); job?.cancel() } }
    SectionTitle("Offline transcription")
    Text("Optional Whisper tiny multilingual · 77.7 MB. Runs on this phone; speed and accuracy depend on the device. Captions are preferred when available.")
    TextButton(enabled = !downloading, onClick = {
        if (installed) { installed = !model.delete(); status = if (installed) "Could not remove model" else "Model removed" }
        else confirm = true
    }) { Text(if (installed) "Delete model" else "Download multilingual model") }
    if (downloading) {
        LinearProgressIndicator()
        TextButton(onClick = { activeCall.getAndSet(null)?.cancel(); job?.cancel() }) { Text("Cancel") }
    }
    if (status.isNotBlank()) Text(status)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Download 77.7 MB?") },
        text = { Text("Download the official multilingual tiny model from Hugging Face. Mobile data charges may apply. SHA-256 is verified before installation.") },
        confirmButton = { TextButton(onClick = {
            confirm = false; downloading = true; status = "Downloading…"
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val call = OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build().newCall(Request.Builder()
                            .url("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin").build())
                        activeCall.set(call)
                        call.execute().use { response ->
                            check(response.isSuccessful) { "Download refused" }
                            val body = response.body ?: error("Empty download")
                            WhisperModelStore(directory).install(body.byteStream(), "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", 77_691_713)
                        }
                    }
                    installed = true; status = "Verified model installed"
                } catch (e: CancellationException) { status = "Download cancelled"; throw e }
                catch (e: Exception) { status = "Download failed; the previous verified model was preserved" }
                finally { activeCall.set(null); downloading = false }
            }
        }) { Text("Download") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

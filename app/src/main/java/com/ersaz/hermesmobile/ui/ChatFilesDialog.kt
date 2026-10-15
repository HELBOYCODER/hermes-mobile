package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ersaz.hermesmobile.HermesRuntime

@Composable
fun ChatFilesDialog(rt: HermesRuntime, vm: ChatViewModel, dismiss: () -> Unit) {
    val files = vm.agent.session.messages.flatMap { it.generatedFiles }.distinct()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Chat files") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (files.isEmpty()) Text("Files generated in this conversation will appear here.")
            files.forEach { path ->
                val file = runCatching { rt.sandbox.hostPath(path) }.getOrNull()
                if (file?.isFile == true) GeneratedFileCard(file.absolutePath)
                else Text("Unavailable: ${path.substringAfterLast('/')}")
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

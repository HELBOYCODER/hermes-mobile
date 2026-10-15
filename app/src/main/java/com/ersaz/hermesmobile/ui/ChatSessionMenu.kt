package com.ersaz.hermesmobile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*

enum class SessionAction(val label: String) {
    NewChat("New Chat"), ClearChat("Clear Chat"), Terminal("Open Terminal"), Browser("Open Browser"),
    Files("Browse Chat Files"), Skills("Skills library"), Mcp("MCP servers"), Memory("Memory"),
    Usage("Token Usage"), AutoCompact("Auto Compact")
}
@Composable
fun ChatSessionMenu(onAction: (SessionAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("Chat tools") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SessionAction.entries.forEach { action ->
            DropdownMenuItem(text = { Text(action.label) }, onClick = { expanded = false; onAction(action) })
        }
    }
}

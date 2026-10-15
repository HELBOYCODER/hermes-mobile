package com.ersaz.hermesmobile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ersaz.hermesmobile.HermesRuntime
import org.json.JSONArray

@Composable
fun IntegrationPacksPanel(rt: HermesRuntime, onNavigate: (String) -> Unit) {
    val packs = remember { runCatching { rt.app.assets.open("coding/packs.json").bufferedReader().use { JSONArray(it.readText()) } }.getOrDefault(JSONArray()) }
    var expanded by remember { mutableStateOf(false) }
    SectionTitle("Integration packs")
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide pack details" else "Sources, requirements & availability") }
    if (expanded) for (i in 0 until packs.length()) {
        val pack = packs.getJSONObject(i)
        ListItem(headlineContent = { Text(pack.getString("name")) }, supportingContent = {
            Text("${pack.getString("runtime")}\n${pack.getString("license")}\n${pack.getString("source")}")
        })
        if (pack.optString("state") in setOf("remote", "requires_host")) TextButton(onClick = { onNavigate("Mcp") }) { Text("Open MCP setup") }
    }
}

package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.agent.FilmRender
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Design workspace: Components flow + Film mode, ON by default.
 * Hermes' own UI. uberdesigner.ai is a proprietary commercial service:
 * its library/prompts are NEVER copied, scraped, or bundled here —
 * "Browse" only opens the site in Hermes' Browser screen under the
 * user's own free/Pro account, and the 3/day free limit + Pro paywall
 * are that service's own. Pasted prompts are the user's own input.
 * Starter prompts below are Hermes' own wording.
 */
@Composable
fun DesignWorkspacePanel(rt: HermesRuntime, vm: ChatViewModel, onNavigate: (String) -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val project = rt.settings.codingProject.ifBlank { "/root/projects" }

    SectionTitle("Components")
    Text("Browse the component library in Hermes' Browser (your own uberdesigner.ai free/Pro account — Hermes never stores those credentials and never bypasses the service's 3 templates/day free limit or its Pro paywall), copy a prompt, and paste it below. Hermes implements it with its own design pipeline (Huashu flow, React Bits web components, GSAP, UI UX Pro Max palettes/fonts).",
        color = skin.dim, fontSize = 12.sp)
    OutlinedButton(onClick = {
        scope.launch { rt.browser.newTab("https://uberdesigner.ai") }
        onNavigate("Browser")
    }) { Text("Browse component library (uberdesigner.ai)") }
    var pasted by remember { mutableStateOf("") }
    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Paste a component prompt") },
        modifier = Modifier.fillMaxWidth(), minLines = 2)
    Row {
        OutlinedButton(enabled = pasted.isNotBlank(), onClick = {
            vm.input = pasted.trim()
            output = "Prompt placed in the composer — press send in chat to build it."
        }) { Text("Use this style in chat") }
    }
    Text("Hermes' own starters (tap to fill the composer):", color = skin.title, fontSize = 12.sp)
    val starters = listOf(
        "button" to "Design a primary button with hover and pressed states, rounded corners, and a clear label.",
        "card" to "Design a content card with an image area, title, short text, and one action button.",
        "navbar" to "Design a top navigation bar with a logo, links, search, and a mobile menu.",
        "hero" to "Design a hero section with a headline, supporting text, two calls to action, and an illustration area.",
        "pricing table" to "Design a three-tier pricing table with a highlighted popular plan and feature lists.",
        "modal" to "Design a modal dialog with a title, body text, and confirm/cancel actions.",
        "form" to "Design a sign-in form with email and password fields, validation messages, and a submit button.",
        "dashboard sidebar" to "Design a dashboard sidebar with navigation items, icons, and a user profile row at the bottom.",
    )
    starters.forEach { (name, prompt) ->
        TextButton(onClick = { vm.input = prompt }) { Text(name) }
    }

    SectionTitle("Film mode (code-based, no video model)")
    Text("Pick one of 66 styles (repo count — the circulating post said 59) or describe your own. Story + style become scene-by-scene HTML/GSAP code; rendering to video is attempted in the sandbox (Chromium + ffmpeg) and, if it cannot run, the complete coded film is still delivered via ZIP export with the exact desktop step named.",
        color = skin.dim, fontSize = 12.sp)
    val styles = remember { loadFilmStyles(rt) }
    var style by remember { mutableStateOf(styles.firstOrNull()?.second ?: "watercolor") }
    var styleQuery by remember { mutableStateOf("") }
    OutlinedTextField(value = styleQuery, onValueChange = { styleQuery = it }, label = { Text("Search styles") },
        modifier = Modifier.fillMaxWidth())
    val shown = styles.filter { styleQuery.isBlank() || it.first.contains(styleQuery, ignoreCase = true) || it.second.contains(styleQuery, ignoreCase = true) }.take(12)
    shown.forEach { (name, folder) ->
        TextButton(onClick = { style = folder }) { Text(if (folder == style) "✓ $name" else name) }
    }
    Text("Selected style: $style", color = skin.title, fontSize = 12.sp)
    var story by remember { mutableStateOf("") }
    OutlinedTextField(value = story, onValueChange = { story = it }, label = { Text("Story (one or two sentences)") },
        modifier = Modifier.fillMaxWidth(), minLines = 2)
    var customName by remember { mutableStateOf("") }
    var customDesc by remember { mutableStateOf("") }
    OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("Custom style name (optional)") },
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = customDesc, onValueChange = { customDesc = it }, label = { Text("Custom style description") },
        modifier = Modifier.fillMaxWidth())
    Row {
        OutlinedButton(enabled = !busy && customName.isNotBlank(), onClick = {
            output = FilmRender.saveCustomStyle(rt, customName.trim(), customDesc.trim())
        }) { Text("Save custom style (MY-STYLES)") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                output = withContext(Dispatchers.IO) { FilmRender.fetchStylePrompt(rt, project, style) }
                busy = false
            }
        }) { Text("Download full style prompt") }
    }
    Row {
        OutlinedButton(enabled = !busy && story.isNotBlank(), onClick = {
            busy = true; scope.launch {
                val scenes = story.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(8)
                    .ifEmpty { listOf(story) }
                val filmDir = "$project/film-${style}"
                output = withContext(Dispatchers.IO) {
                    FilmRender.writeCodedFilm(rt, filmDir, "Film ($style)", style, story, scenes)
                }
                busy = false
            }
        }) { Text("Create coded film in project") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                val filmDir = "$project/film-$style"
                output = withContext(Dispatchers.IO) { FilmRender.renderAttempt(rt, filmDir, null) }
                busy = false
            }
        }) { Text("Attempt video render") }
    }
    OutlinedButton(enabled = !busy, onClick = {
        busy = true; scope.launch {
            output = try {
                val filmDir = "$project/film-$style"
                val host = withContext(Dispatchers.IO) { rt.sandbox.hostPath(filmDir) }
                if (!host.isDirectory) "No coded film yet for style '$style' — create it first."
                else {
                    val zip = withContext(Dispatchers.IO) {
                        val tmp = File(ctx.cacheDir, "film-$style.zip")
                        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                            host.walkTopDown().filter { it.isFile }.forEach { f ->
                                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(host).invariantSeparatorsPath))
                                f.inputStream().use { it.copyTo(z) }
                                z.closeEntry()
                            }
                        }
                        tmp
                    }
                    val uri = com.ersaz.hermesmobile.files.DownloadsExporter.save(ctx, zip)
                    "Film package exported to Downloads/Hermes ($uri)."
                }
            } catch (e: Exception) { "Export failed: ${e.message}" }
            busy = false
        }
    }) { Text("Export film package (ZIP)") }
    if (output.isNotBlank()) {
        Text(output, color = skin.label, fontSize = 11.5.sp,
            modifier = Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState()))
    }
}

/** (display name, folder) from the bundled lemo-film style index. */
private fun loadFilmStyles(rt: HermesRuntime): List<Pair<String, String>> {
    val text = runCatching { rt.app.assets.open("skills/creative/lemo-film/styles-index.md").bufferedReader().use { it.readText() } }
        .getOrElse {
            runCatching { File(rt.skills.dir, "creative/lemo-film/styles-index.md").readText() }.getOrNull()
        }.orEmpty()
    val rx = Regex("\\| ([^|]+?) \\| [^|]+? \\| \\[`([^`]+)`\\]")
    val parsed = rx.findAll(text).map { it.groupValues[1].trim() to it.groupValues[2].trim() }.distinct().toList()
    if (parsed.size >= 10) return parsed
    // Fallback: the 66 verified style folders (repo listing 2026-10-30).
    return listOf("archive-lighttable", "art-deco", "ascii-crt", "backrooms", "blueprint", "brick-toy",
        "briefing-board", "caption-news", "cel-anime-80s", "chalkboard", "circuit-board", "city-symphony",
        "countdown", "crayon-book", "dark-keynote", "data-physical", "dataviz", "desktop-flythrough",
        "engraving", "game-show", "glass-product", "halftone-dossier", "hd-2d", "hologram-hud", "impasto",
        "ink-wash", "iso-infographic", "isometric-picture-book", "kino-montage", "lidar-survey",
        "living-screencast", "lowpoly-island", "memphis-diorama", "microgame", "microscope",
        "midcentury-toon", "news-package", "newsreel", "one-line", "paper-lantern", "paper-popup",
        "papercut-red", "pictogram-motion", "pixel-rpg", "printed-long-take", "risograph", "rubber-hose",
        "satellite-briefing", "scifi-toon", "shadow-puppet", "silent-film", "silkscreen-poster",
        "spy-titles", "stained-glass", "swiss-motion", "teardown", "telestrator", "tilt-shift",
        "transit-map", "ukiyoe", "urban-sketch", "visual-investigation", "watercolor", "white-model",
        "whiteboard", "woodcut").map { it.replace('-', ' ') to it }
}

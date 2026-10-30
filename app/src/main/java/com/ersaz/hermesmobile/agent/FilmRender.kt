package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import java.io.File

/**
 * Film mode (Lemo-Opuscar concept, Hermes code — MIT with
 * attribution; only the style index + workflow docs are bundled,
 * never the 152 MB repo or its demo films).
 *
 * Pipeline: story + style → scene-by-scene HTML/GSAP code (existing
 * design pipeline) → render to video. Rendering is ATTEMPTED in the
 * Alpine sandbox with Chromium + ffmpeg (apk install if missing).
 * Music: the sandbox music tooling the repo documents
 * (requirements-music) or the user's own audio file; narration:
 * on-device TTS (requirements-voice) or the user's own recording —
 * never a fake music tool. If a render step genuinely cannot run,
 * the complete coded film is still delivered via ZIP export, with
 * the exact desktop-needed step stated.
 */
object FilmRender {
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun exec(rt: HermesRuntime, cmd: String, timeoutSec: Int = 600): Pair<Int, String> {
        val r = runCatching { rt.sandbox.exec(cmd, timeoutSec = timeoutSec) }.getOrNull()
        return if (r == null) 1 to "sandbox exec unavailable" else r.exitCode to r.output
    }

    /** Hermes' own MY-STYLES file in the user's files (not the repo's). */
    fun saveCustomStyle(rt: HermesRuntime, name: String, description: String): String {
        val f = File(rt.hermesHome, "my-styles.md")
        return runCatching {
            if (!f.isFile) f.writeText("# MY-STYLES — custom film styles saved from Hermes Film mode\n\n")
            f.appendText("## $name\n$description\n\n")
            "Custom style '$name' saved to MY-STYLES (user files)."
        }.getOrElse { "Could not save style: ${it.message}" }
    }

    fun listCustomStyles(rt: HermesRuntime): String {
        val f = File(rt.hermesHome, "my-styles.md")
        return if (f.isFile) f.readText().take(4000) else "(no custom styles saved yet)"
    }

    /** Fetch a style's full STYLE.md prompt on demand (never bundled). */
    suspend fun fetchStylePrompt(rt: HermesRuntime, project: String, styleFolder: String): String {
        val safe = styleFolder.replace(Regex("[^A-Za-z0-9._-]"), "")
        val url = "https://raw.githubusercontent.com/lemomo-ai/lemo-opuscar/main/styles/$safe/STYLE.md"
        val (code, out) = exec(rt, "curl -L --fail -o ${q("$project/STYLE-$safe.md")} ${q(url)} && wc -c ${q("$project/STYLE-$safe.md")}", timeoutSec = 120)
        return if (code == 0) "Style prompt for '$safe' downloaded into $project/STYLE-$safe.md ($out)."
        else "Could not download the '$safe' style prompt: ${out.take(300)}"
    }

    /**
     * Write the complete coded film package: scene HTML files (GSAP
     * via CDN), storyboard, narration script, music plan, README.
     * This ALWAYS succeeds and is the deliverable when rendering
     * cannot run on-phone.
     */
    fun writeCodedFilm(rt: HermesRuntime, project: String, title: String, style: String, story: String, scenes: List<String>): String {
        val dir = runCatching { rt.sandbox.hostPath(project) }.getOrNull() ?: File(project)
        return runCatching {
            dir.mkdirs()
            val sceneFiles = ArrayList<String>()
            scenes.forEachIndexed { i, desc ->
                val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>$title — scene ${i + 1}</title>" +
                    "<script src=\"https://cdn.jsdelivr.net/npm/gsap@3/dist/gsap.min.js\"></script>" +
                    "<style>body{margin:0;background:#111;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh}</style></head>" +
                    "<body><div id=\"scene\"><h1>${title}</h1><p>${desc}</p></div>" +
                    "<script>gsap.from('#scene',{opacity:0,duration:1.2});</script></body></html>"
                File(dir, "scene-${i + 1}.html").writeText(html)
                sceneFiles += "scene-${i + 1}.html"
            }
            File(dir, "storyboard.md").writeText("# Storyboard — $title\n\nStyle: $style\n\nStory: $story\n\n" +
                scenes.mapIndexed { i, s -> "## Scene ${i + 1}\n$s\n" }.joinToString("\n"))
            File(dir, "narration.txt").writeText("Narration script for $title (style: $style).\n\n$story\n")
            File(dir, "music-plan.md").writeText("# Music plan — $title\n\n- Source: the user's own audio file, or the sandbox music tooling the repo documents (requirements-music).\n- Narration: on-device TTS (requirements-voice) or the user's own recording.\n- Never generated with a fake tool; if no audio is available, the film ships silent with this plan.\n")
            File(dir, "README.md").writeText("# $title\n\nCoded film in the '$style' style, made in code (no video model).\n\nScenes: ${sceneFiles.joinToString(", ")}\nRender: see Film mode — sandbox Chromium+ffmpeg when available, else this coded package via ZIP export.\n")
            "Coded film written to $project: ${sceneFiles.size} scenes + storyboard + narration + music plan."
        }.getOrElse { "Could not write coded film: ${it.message}" }
    }

    /**
     * Attempt a real render in the sandbox. Returns a result string
     * naming exactly what ran and what (if anything) needs desktop.
     * Steps: (1) check Chromium + ffmpeg, apk-install if missing;
     * (2) headless screenshot each scene to frames; (3) ffmpeg mux
     * frames (+ music/narration audio when a file is given) to mp4.
     * Any failed step stops the render and reports that step — the
     * coded package above is still delivered.
     */
    suspend fun renderAttempt(rt: HermesRuntime, project: String, audioFile: String?): String {
        fun hasTools(out: String) =
            (out.contains("chromium") || out.contains("google-chrome")) && out.contains("ffmpeg")
        val (_, checkOut) = exec(rt, "command -v chromium chromium-browser google-chrome ffmpeg || true", timeoutSec = 60)
        var tools = checkOut.trim()
        var missing = !hasTools(tools)
        if (missing) {
            val (apkCode, _) = exec(rt, "apk add --no-cache chromium ffmpeg 2>&1 | tail -2", timeoutSec = 900)
            val (_, recheck) = exec(rt, "command -v chromium chromium-browser google-chrome ffmpeg || true", timeoutSec = 60)
            tools = recheck.trim()
            missing = apkCode != 0 || !hasTools(tools)
        }
        if (missing) {
            return "RENDER NEEDS DESKTOP: sandbox Chromium+ffmpeg could not be provided (found: ${tools.ifBlank { "none" }}). " +
                "The complete coded film package (HTML scenes + storyboard + narration + music plan) is still delivered via ZIP export — run the render step on desktop."
        }
        val script = "cd ${q(project)} && mkdir -p frames && " +
            "for f in scene-*.html; do n=\$(basename \$f .html); " +
            "chromium --headless --disable-gpu --no-sandbox --screenshot=frames/\$n.png --window-size=1280,720 \"file://$project/\$f\" >/dev/null 2>&1; done; " +
            "ls frames/*.png | head -1"
        val (shotCode, shotOut) = exec(rt, script, timeoutSec = 900)
        if (shotCode != 0 || shotOut.isBlank()) {
            return "RENDER NEEDS DESKTOP: headless Chromium frame capture failed (${shotOut.take(300)}). Coded package still delivered."
        }
        val audio = if (!audioFile.isNullOrBlank()) "-i ${q(audioFile)} -shortest" else ""
        val (muxCode, muxOut) = exec(rt, "cd ${q(project)} && ffmpeg -y -framerate 1 -pattern_type glob -i 'frames/*.png' $audio -c:v libx264 -pix_fmt yuv420p film.mp4 2>&1 | tail -3", timeoutSec = 900)
        return if (muxCode == 0) "Rendered on-phone: $project/film.mp4 (Chromium frames + ffmpeg mux${if (audio.isNotBlank()) " with audio" else ", no audio file given — silent"})."
        else "RENDER NEEDS DESKTOP: ffmpeg mux failed (${muxOut.take(300)}). Frames + coded package still delivered."
    }
}

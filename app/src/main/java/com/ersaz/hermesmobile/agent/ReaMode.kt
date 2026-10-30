package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.sandbox.Sandbox

/**
 * REA (reverse-engineer-anything) phone equivalent — Hermes' own code.
 * The bundled REA skill text (MIT, see LICENSE-REA.txt next to it) is
 * the workflow; this file is only the phone-side routing: chat trigger,
 * file-type identification, and the sandbox commands that actually run.
 * No REA desktop bridge, MCP server, or native binary is bundled or run.
 *
 * Core REA rule, enforced in the prompt routing and the panel UI: EVERY
 * finding carries its evidence (tool output, offset, string, symbol) AND
 * its limitation/confidence. Analysis is never presented as a guess;
 * unverified claims are labelled hypotheses.
 */
object ReaMode {
    /** Chat trigger: the user saying "reverse engineer this" (or the
     *  Persian equivalent) switches the agent into REA mode. */
    fun isTrigger(text: String): Boolean {
        val t = text.lowercase()
        return "reverse engineer" in t || "reverse-engineer" in t || "مهندسی معکوس" in t
    }

    enum class Target(val label: String) {
        ELF("Native ELF binary"),
        APK("Android APK"),
        PE_DOTNET("PE / .NET assembly (candidate — confirm via metadata)"),
        JS_BUNDLE("JavaScript bundle"),
        ELECTRON_ASAR("Electron app (asar bundle)"),
        ARCHIVE_ZIP("ZIP archive"),
        UNKNOWN("Unknown file type"),
    }

    /** Identification by magic bytes first, extension second — never by
     *  name alone. `header` = the file's first bytes (may be short). */
    fun identify(fileName: String, header: ByteArray): Target {
        val name = fileName.lowercase()
        val elf = header.size >= 4 && header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        if (elf) return Target.ELF
        val mz = header.size >= 2 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()
        if (mz) return Target.PE_DOTNET
        val zip = header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        if (zip) return if (name.endsWith(".apk")) Target.APK else Target.ARCHIVE_ZIP
        if (name.endsWith(".asar")) return Target.ELECTRON_ASAR
        if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) return Target.JS_BUNDLE
        if (name.endsWith(".apk")) return Target.APK // extension-only fallback, labelled as such
        return Target.UNKNOWN
    }

    /** (label, command) pairs that run inside the Alpine sandbox. Every
     *  command is capped so a huge binary cannot flood the panel. `q`
     *  is the shell-quoted target path. Missing tools are reported by
     *  the command output itself (command -v checks), never faked. */
    fun analysisSteps(target: Target, q: String): List<Pair<String, String>> = when (target) {
        Target.ELF -> listOf(
            "Tool availability" to "command -v file strings readelf objdump nm || echo MISSING-TOOLS",
            "File type" to "file $q 2>&1 | head -20",
            "Strings (first 200)" to "strings $q 2>&1 | head -200",
            "ELF headers + sections" to "readelf -h -S $q 2>&1 | head -120",
            "Dynamic symbols" to "nm -D $q 2>&1 | head -200",
            "Disassembly (first 300 lines, .text)" to "objdump -d $q 2>&1 | head -300",
        )
        Target.APK -> listOf(
            "Tool availability" to "command -v unzip file strings || echo MISSING-TOOLS",
            "Archive listing" to "unzip -l $q 2>&1 | head -120",
            "Manifest strings" to "unzip -p $q AndroidManifest.xml 2>/dev/null | strings | head -120",
            "DEX files" to "unzip -l $q 2>/dev/null | grep -i '\\.dex' | head -20",
            "DEX strings (first 200)" to "for d in \$(unzip -l $q 2>/dev/null | awk '/\\.dex$/ {print \$4}'); do echo \"== \$d ==\"; unzip -p $q \"\$d\" 2>/dev/null | strings | head -100; done",
            "Native libraries" to "unzip -l $q 2>/dev/null | grep -i '\\.so$' | head -40",
        )
        Target.PE_DOTNET -> listOf(
            "Tool availability" to "command -v file strings || echo MISSING-TOOLS",
            "File type" to "file $q 2>&1 | head -20",
            "Strings (first 200)" to "strings $q 2>&1 | head -200",
            ".NET metadata markers" to "strings $q 2>/dev/null | grep -i -m 100 'mscorlib\\|System\\.\\|_CorDllMain\\|BSJB\\|Metadata' | head -100",
        )
        Target.JS_BUNDLE -> listOf(
            "Tool availability" to "command -v node strings grep || echo MISSING-TOOLS",
            "Size + head" to "wc -c $q; head -c 2000 $q",
            "Imports / requires" to "grep -o -m 200 -E \"(require\\([^)]+\\)|from ['\\\"][^'\\\"]+['\\\"]|import\\([^)]+\\))\" $q | head -200",
            "Strings (first 200)" to "strings $q 2>&1 | head -200",
        )
        Target.ELECTRON_ASAR -> listOf(
            "Tool availability" to "command -v node npx strings || echo MISSING-TOOLS",
            "Asar listing (via npx asar, if installable)" to "npx -y asar list $q 2>&1 | head -120",
            "Strings (first 200)" to "strings $q 2>&1 | head -200",
        )
        Target.ARCHIVE_ZIP -> listOf(
            "Tool availability" to "command -v unzip file || echo MISSING-TOOLS",
            "Archive listing" to "unzip -l $q 2>&1 | head -200",
        )
        Target.UNKNOWN -> listOf(
            "File type" to "file $q 2>&1 | head -20",
            "Strings (first 200)" to "strings $q 2>&1 | head -200",
        )
    }

    /** Attempt to install the on-phone analysis toolchain (binutils +
     *  file). Returns the raw installer output so the panel can show
     *  honestly whether it worked. Never claimed before it runs. */
    fun ensureBinutilsCommand(): String =
        "apk add --no-cache binutils file 2>&1 | tail -8; command -v file strings readelf objdump nm"

    /** Genuine attempt at APK decompilers (standing rule: try before
     *  writing off). apktool is not in Alpine main and jadx needs a
     *  desktop Java install; the output says so verbatim when the
     *  attempt fails, and then decompilation stays a desktop step. */
    fun tryDecompilersCommand(): String =
        "command -v apktool jadx java || echo NO-DECOMPILERS; apk add --no-cache apktool 2>&1 | tail -4; command -v apktool jadx || echo STILL-MISSING"

    /** Attempt at the HyperFrames-adjacent video toolchain is separate
     *  (see the design report); REA needs no video tools. */

    /** Report skeleton: findings grouped behavior → strings/symbols →
     *  code structure → data, each with evidence + limitation +
     *  confidence, plus a final unknowns section. The agent fills it;
     *  the panel exports it. */
    fun reportSkeleton(targetName: String, targetLabel: String): String = """
        # REA report — $targetName ($targetLabel)
        _All analysis ran locally inside the on-device Alpine sandbox. Nothing was uploaded._
        ## 1. Behavior (what the target does)
        - Finding: (evidence: tool output / offset / string; limitation: …; confidence: high/medium/low)
        ## 2. Strings / symbols
        - Finding: (evidence: …; limitation: …; confidence: …)
        ## 3. Code structure
        - Finding: (evidence: …; limitation: …; confidence: …)
        ## 4. Data (formats, embedded resources, keys/URLs found as strings)
        - Finding: (evidence: …; limitation: …; confidence: …)
        ## 5. Unknowns (what could NOT be determined on-phone)
        - (e.g. decompiled logic — needs desktop apktool/jadx, Ghidra, or Hopper; see export package)
        ## 6. Desktop next steps
        - Ghidra headless: desktop Java install + gigabytes of disk — NOT on-phone. Hopper: macOS commercial tool — cannot run on Android.
        - Continue with the exported analysis package (metadata + evidence + this report) in Ghidra/Hopper on desktop.
    """.trimIndent()

    fun q(path: String): String = Sandbox.shQuote(path)
}

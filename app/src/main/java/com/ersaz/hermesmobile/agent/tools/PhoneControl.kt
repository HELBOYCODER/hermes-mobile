package com.ersaz.hermesmobile.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Adb-level shell on the phone through Shizuku (the user starts Shizuku once and grants this app). */
object PhoneShell {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    fun status(context: Context): Status = try {
        when {
            Shizuku.pingBinder() -> if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Status.READY else Status.NEEDS_PERMISSION
            runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess -> Status.NOT_RUNNING
            else -> Status.NOT_INSTALLED
        }
    } catch (_: Throwable) { Status.NOT_INSTALLED }

    fun requestPermission() { runCatching { Shizuku.requestPermission(4242) } }

    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    class Result(val stdout: ByteArray, val stderr: String, val exit: Int) {
        val text get() = String(stdout)
    }

    // Shizuku.newProcess is not part of the public API surface anymore; the method is still there.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    /** Run [command] as the shell user. [stdin] is streamed to the process when given (used for `pm install -S`). */
    suspend fun run(command: String, timeoutSec: Long = 60, stdin: File? = null): Result = withContext(Dispatchers.IO) {
        val p = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val tOut = Thread { runCatching { p.inputStream.copyTo(out) } }.apply { isDaemon = true; start() }
        val tErr = Thread { runCatching { p.errorStream.copyTo(err) } }.apply { isDaemon = true; start() }
        runCatching {
            if (stdin != null) stdin.inputStream().use { it.copyTo(p.outputStream) }
            p.outputStream.close()
        }
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        tOut.join(2000); tErr.join(2000)
        Result(out.toByteArray(), String(err.toByteArray()), if (done) p.exitValue() else 124)
    }
}

class PhoneControlTool : Tool(
    "phone_control", "phone",
    "Control the phone itself like a person would, via Shizuku (adb-level shell): look at the screen (screenshot, ui_dump), " +
        "tap, swipe, type, press keys, launch apps, install an APK, or run any shell command (am, pm, settings, dumpsys, input…). " +
        "Typical loop: ui_dump or screenshot → decide → tap/swipe/text → look again. Use action=status first if unsure it is available.",
    "🤖",
) {
    override val parameters = obj(
        "action" to str("What to do", listOf("status", "shell", "screenshot", "ui_dump", "tap", "swipe", "text", "key", "launch", "install_apk")),
        "command" to str("Shell command for action=shell"),
        "x" to int("X pixel for tap / swipe start"), "y" to int("Y pixel for tap / swipe start"),
        "x2" to int("Swipe end X"), "y2" to int("Swipe end Y"), "duration_ms" to int("Swipe duration (default 300)"),
        "text" to str("Text to type for action=text (spaces allowed)"),
        "keycode" to str("Android keycode name or number, e.g. HOME, BACK, ENTER, 66"),
        "package" to str("Package for action=launch"),
        "path" to str("APK path (sandbox path) for action=install_apk"),
        required = listOf("action"),
    )

    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val c = ctx.runtime.app
        val st = PhoneShell.status(c)
        val action = args.optString("action")
        if (action == "status") return ok("shizuku" to st.name, "hint" to hint(st))
        if (st != PhoneShell.Status.READY) return fail("Phone control is not ready (${st.name}). ${hint(st)}")
        return try {
            when (action) {
                "shell" -> sh(args.optString("command").ifBlank { return fail("command is required") })
                "tap" -> sh("input tap ${args.optInt("x")} ${args.optInt("y")}")
                "swipe" -> sh("input swipe ${args.optInt("x")} ${args.optInt("y")} ${args.optInt("x2")} ${args.optInt("y2")} ${args.optInt("duration_ms", 300)}")
                "text" -> sh("input text " + Shell.quote(args.optString("text").replace(" ", "%s")))
                "key" -> sh("input keyevent " + Shell.quote(args.optString("keycode").ifBlank { return fail("keycode is required") }))
                "launch" -> sh("monkey -p " + Shell.quote(args.optString("package")) + " -c android.intent.category.LAUNCHER 1")
                "ui_dump" -> {
                    val r = PhoneShell.run("uiautomator dump /dev/stdout", 30)
                    ok("xml" to r.text.substringBefore("UI hierchary dumped").take(24_000), "exit" to r.exit)
                }
                "screenshot" -> {
                    val r = PhoneShell.run("screencap -p", 30)
                    if (r.exit != 0 || r.stdout.size < 100) return fail("screencap failed: ${r.stderr.take(200)}")
                    val dir = File(ctx.runtime.sandbox.rootfs, "root/uploads").apply { mkdirs() }
                    val f = File(dir, "screenshot_${System.currentTimeMillis()}.png")
                    f.writeBytes(r.stdout)
                    ok("path" to "/root/uploads/${f.name}", "bytes" to r.stdout.size,
                        "hint" to "Open it with the vision tool or read it via the file tools.")
                }
                "install_apk" -> {
                    val f = ctx.runtime.sandbox.hostPath(args.optString("path"), ctx.agent.cwd)
                    if (!f.exists()) return fail("APK not found: ${args.optString("path")}")
                    val r = PhoneShell.run("pm install -r -d -S ${f.length()}", 300, stdin = f)
                    ok("output" to (r.text + r.stderr).trim(), "exit" to r.exit)
                }
                else -> fail("Unknown action")
            }
        } catch (e: Throwable) {
            fail("Phone control failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun sh(cmd: String): String {
        val r = PhoneShell.run(cmd, 60)
        return ok("output" to (r.text + r.stderr).trim().take(20_000), "exit" to r.exit)
    }

    private fun hint(st: PhoneShell.Status) = when (st) {
        PhoneShell.Status.READY -> "Ready."
        PhoneShell.Status.NEEDS_PERMISSION -> "Ask the user to open Settings → Phone control in Hermes and tap Grant permission."
        PhoneShell.Status.NOT_RUNNING -> "Shizuku is installed but not running. Ask the user to open Shizuku and start it (wireless debugging)."
        PhoneShell.Status.NOT_INSTALLED -> "Shizuku is not installed. Ask the user to install it from https://shizuku.rikka.app/download/ and start it."
    }
}

private object Shell {
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

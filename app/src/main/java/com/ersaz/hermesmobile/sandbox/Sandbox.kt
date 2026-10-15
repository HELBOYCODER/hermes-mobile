package com.ersaz.hermesmobile.sandbox

import android.content.Context
import android.os.Environment
import android.system.Os
import com.ersaz.hermesmobile.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * On-device Linux sandbox: Alpine Linux minirootfs run through PRoot — no Termux app,
 * no root. Same approach as OpenMinis on Android:
 *
 *  - `libproot.so` / `libproot-loader.so` live in nativeLibraryDir (the only exec-able
 *    location for targetSdk >= 29); the loader maps guest ELFs itself.
 *  - The rootfs lives in filesDir/sandbox/rootfs; the agent's HERMES_HOME is
 *    /root/.hermes inside it, so the shell and the native tools see the same files.
 */
class Sandbox(private val context: Context, private val settings: Settings) {

    sealed class State {
        data object NotInstalled : State()
        data class Installing(val progress: String) : State()
        data object Ready : State()
        data class Failed(val error: String) : State()
    }

    data class ExecResult(val output: String, val exitCode: Int, val cwd: String, val timedOut: Boolean)

    val baseDir = File(context.filesDir, "sandbox")
    val rootfs = File(baseDir, "rootfs")
    private val marker = File(baseDir, ".installed")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val prootBin = File(nativeDir, "libproot.so")
    private val tmpDir = File(context.cacheDir, "proot-tmp")

    private val _state = MutableStateFlow<State>(if (isInstalled()) State.Ready else State.NotInstalled)
    val state: StateFlow<State> = _state
    private val installLock = Mutex()

    fun isInstalled(): Boolean = marker.exists() && File(rootfs, "bin/busybox").exists()

    fun payloadAvailable(): Boolean = prootBin.exists() &&
        runCatching { context.assets.open(ROOTFS_ASSET).close(); true }.getOrDefault(false)

    /** Host path of a guest path (e.g. "/root/x" -> files/sandbox/rootfs/root/x). */
    fun hostPath(guestPath: String, cwd: String = "/root"): File {
        val abs = if (guestPath.startsWith("/")) guestPath
        else if (guestPath.startsWith("~")) "/root" + guestPath.removePrefix("~")
        else "${cwd.trimEnd('/')}/$guestPath"
        val normalized = File(abs).normalize().path
        if (normalized.startsWith("/sdcard") || normalized.startsWith("/storage")) {
            val ext = Environment.getExternalStorageDirectory()
            return File(ext, normalized.removePrefix("/sdcard").removePrefix("/storage/emulated/0"))
        }
        return File(rootfs, normalized.removePrefix("/"))
    }

    fun guestPath(host: File): String {
        val rel = host.absolutePath.removePrefix(rootfs.absolutePath)
        return if (rel.startsWith("/")) rel else "/$rel"
    }

    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        installLock.withLock {
            if (isInstalled()) { postInstall(); _state.value = State.Ready; return@withLock true }
            if (!payloadAvailable()) {
                _state.value = State.Failed(
                    "Sandbox payload missing from this build (run scripts/fetch_sandbox.sh before building)."
                )
                return@withLock false
            }
            try {
                _state.value = State.Installing("Extracting Alpine Linux…")
                // HERMES_HOME (/root/.hermes: sessions, memory, skills, cron) lives inside the rootfs —
                // move it aside so a fresh install or "Reinstall rootfs" never destroys user data.
                val home = File(rootfs, "root/.hermes")
                val kept = File(baseDir, ".hermes_keep")
                // A leftover .hermes_keep means a previous install died mid-way: it IS the user's data, keep it.
                if (!kept.exists() && home.exists() && !home.renameTo(kept)) {
                    home.copyRecursively(kept, overwrite = true)
                }
                rootfs.deleteRecursively()
                rootfs.mkdirs()
                var count = 0
                context.assets.open(ROOTFS_ASSET).use { input ->
                    TarExtractor.extract(input, rootfs) {
                        if (++count % 50 == 0) _state.value = State.Installing("Extracting… $count files")
                    }
                }
                if (kept.exists()) {
                    home.deleteRecursively()
                    home.parentFile?.mkdirs()
                    if (!kept.renameTo(home)) { kept.copyRecursively(home, overwrite = true); kept.deleteRecursively() }
                }
                postInstall()
                marker.writeText(System.currentTimeMillis().toString())
                _state.value = State.Ready
                true
            } catch (e: Exception) {
                _state.value = State.Failed("Install failed: ${e.message}")
                false
            }
        }
    }

    /** Upgrade bundled router code/runtime in place; leave the user's router database alone. */
    suspend fun installBundledRouter(): Boolean = withContext(Dispatchers.IO) {
        installLock.withLock {
            val stamp = File(baseDir, ".router-build")
            val version = com.ersaz.hermesmobile.BuildConfig.VERSION_CODE.toString()
            val server = File(rootfs, "usr/lib/node_modules/9router/app/custom-server.js")
            if (stamp.exists() && stamp.readText() == version && server.exists()) return@withLock true
            try {
                context.assets.open("sandbox/router-runtime.bin").use { TarExtractor.extract(it, rootfs) }
                check(server.exists()) { "Bundled 9Router server missing" }
                stamp.writeText(version)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Add media dependencies on upgrades without replacing the user's package database. */
    suspend fun installBundledMedia(): Boolean = withContext(Dispatchers.IO) {
        installLock.withLock {
            val stamp = File(baseDir, ".media-build")
            val version = com.ersaz.hermesmobile.BuildConfig.VERSION_CODE.toString()
            if (stamp.exists() && stamp.readText() == version && File(rootfs, "usr/bin/ffmpeg").exists()) return@withLock true
            try {
                context.assets.open("sandbox/media-runtime.bin").use { TarExtractor.extract(it, rootfs) }
                check(File(rootfs, "usr/bin/ffmpeg").exists())
                stamp.writeText(version)
                true
            } catch (e: Exception) { false }
        }
    }

    suspend fun reinstall(): Boolean {
        marker.delete()
        return ensureInstalled()
    }

    /** Files we (re)write on every start so updates to the app reach the rootfs. */
    private fun postInstall() {
        tmpDir.mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); runCatching { Os.chmod(absolutePath, "1777".toInt(8)) } }
        File(rootfs, "root/.hermes").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 2001:4860:4860::8888\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n127.0.1.1 hermes\n")
        File(rootfs, "etc/hostname").writeText("hermes\n")
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/hermes.sh").writeText(PROFILE)
        File(rootfs, "etc/motd").writeText(MOTD)
        File(rootfs, "usr/local/bin").mkdirs()
        // Official OpenCode CLI: install and authenticate interactively inside the existing terminal.
        // No paid model entitlement is granted by this launcher.
        val cliScript = File(rootfs, "root/.hermes/coding/hermes-opencode.sh")
        cliScript.parentFile?.mkdirs()
        context.assets.open("coding/hermes-opencode.sh").use { input ->
            cliScript.outputStream().use { output -> input.copyTo(output) }
        }
        File(rootfs, "usr/local/bin/hermes-opencode").apply {
            writeText("#!/bin/sh\nexec /bin/sh /root/.hermes/coding/hermes-opencode.sh \"\\$@\"\n")
            runCatching { Os.chmod(absolutePath, "755".toInt(8)) }
        }
        File(rootfs, "usr/local/bin/hermes").apply {
            writeText(HERMES_CLI)
            runCatching { Os.chmod(absolutePath, "755".toInt(8)) }
        }
    }

    /** Host-side environment for the proot process itself. */
    fun hostEnv(): Map<String, String> = buildMap {
        put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
        val l32 = File(nativeDir, "libproot-loader32.so")
        if (l32.exists()) put("PROOT_LOADER_32", l32.absolutePath)
        put("PROOT_TMP_DIR", tmpDir.absolutePath)
        put("LD_LIBRARY_PATH", nativeDir.absolutePath)
        put("TMPDIR", tmpDir.absolutePath)
        if (settings.prootNoSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    /** argv for proot entering the rootfs and running [guestArgv] with a clean env. */
    fun prootArgv(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): List<String> {
        tmpDir.mkdirs()
        val argv = mutableListOf(
            prootBin.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${File(rootfs, "tmp").absolutePath}:/dev/shm",
        )
        if (settings.bindSharedStorage) {
            val ext = Environment.getExternalStorageDirectory()
            if (ext.canRead()) argv += listOf("-b", "${ext.absolutePath}:/sdcard")
        }
        argv += listOf("-w", cwd)
        argv += listOf("/usr/bin/env", "-i")
        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "HERMES_HOME" to "/root/.hermes",
            "SHELL" to "/bin/sh",
        )
        env.putAll(settings.envVars())
        if (settings.githubToken.isNotBlank()) {
            env["GH_TOKEN"] = settings.githubToken
            env["GITHUB_TOKEN"] = settings.githubToken
            if (settings.githubUser.isNotBlank()) env["GITHUB_USER"] = settings.githubUser
        }
        env.putAll(extraEnv)
        env.forEach { (k, v) -> argv += "$k=$v" }
        argv += guestArgv
        return argv
    }

    fun processBuilder(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        val pb = ProcessBuilder(prootArgv(guestArgv, cwd, extraEnv))
        pb.environment().putAll(hostEnv())
        pb.directory(context.filesDir)
        return pb
    }

    /**
     * Run a shell command like Hermes' terminal tool: the working directory persists
     * between calls (we source the command in the same shell and report $PWD back).
     */
    suspend fun exec(
        command: String,
        cwd: String = "/root",
        timeoutSec: Int = 180,
        maxOutput: Int = 100_000,
        onOutput: ((String) -> Unit)? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!isInstalled() && !ensureInstalled()) {
            return@withContext ExecResult("Sandbox is not available: ${(state.value as? State.Failed)?.error ?: "not installed"}", -1, cwd, false)
        }
        val id = UUID.randomUUID().toString().take(8)
        val script = File(rootfs, "tmp/.hermes_cmd_$id.sh")
        script.writeText(command + "\n")
        val wrapper = "cd ${shQuote(cwd)} 2>/dev/null || cd /root; . /tmp/.hermes_cmd_$id.sh; " +
            "__hec=\$?; printf '\\n$CWD_MARK%s\\n' \"\$PWD\"; exit \$__hec"
        val pb = processBuilder(listOf("/bin/sh", "-c", wrapper), "/root")
        pb.redirectErrorStream(true)
        val proc = try { pb.start() } catch (e: Exception) {
            script.delete()
            return@withContext ExecResult("Failed to start sandbox: ${e.message}", -1, cwd, false)
        }
        proc.outputStream.close()
        val buf = StringBuilder()
        var dropped = 0L
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        val s = String(chunk, 0, n)
                        synchronized(buf) {
                            if (buf.length < maxOutput * 2) buf.append(s) else dropped += n
                        }
                        onOutput?.invoke(s)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val finished = try { waitForCommand(proc, timeoutSec.toLong()) }
        catch (e: kotlinx.coroutines.CancellationException) { script.delete(); throw e }
        reader.join(2000)
        script.delete()
        var out = synchronized(buf) { buf.toString() }
        var newCwd = cwd
        val idx = out.lastIndexOf(CWD_MARK)
        if (idx >= 0) {
            newCwd = out.substring(idx + CWD_MARK.length).trim().ifEmpty { cwd }
            out = out.substring(0, idx).trimEnd('\n')
        }
        out = TerminalSanitizer.clean(out)
        if (out.length > maxOutput) out = com.ersaz.hermesmobile.core.truncateMiddle(out, maxOutput)
        if (dropped > 0) out += "\n[… $dropped more bytes of output dropped]"
        ExecResult(out, if (finished) proc.exitValue() else 124, newCwd, !finished)
    }

    companion object {
        const val ROOTFS_ASSET = "sandbox/alpine-rootfs.bin"
        const val CWD_MARK = "__HERMES_CWD__"
        const val BRIDGE_PORT_FILE = "root/.hermes/.bridge_port"
        const val BRIDGE_TOKEN_FILE = "root/.hermes/.bridge_token"

        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        private val PROFILE = """
            # Hermes sandbox profile (rewritten by the app on every start)
            export HERMES_HOME=/root/.hermes
            export PAGER=less
            alias ll='ls -la'
            if [ -n "${'$'}PS1" ] || [ -t 0 ]; then
              PS1='\[\e[38;5;178m\]☤ \[\e[38;5;179m\]\w\[\e[0m\] ❯ '
            fi
        """.trimIndent() + "\n"

        private val MOTD = """

             ☤  Hermes sandbox — Alpine Linux on PRoot (no Termux, no root)

             apk add python3 git nodejs     install packages
             hermes                         chat with your Hermes agent here
             hermes "summarise ~/notes.md"  one-shot prompt
             hermes /status                 run any slash command

        """.trimIndent() + "\n\n"

        /** `hermes` CLI inside the sandbox: talks to the app's agent over a localhost bridge. */
        private val HERMES_CLI = """
            #!/bin/sh
            # Hermes CLI bridge — forwards prompts and /commands to the Hermes app agent.
            H=${'$'}{HERMES_HOME:-/root/.hermes}
            PORT=${'$'}(cat "${'$'}H/.bridge_port" 2>/dev/null)
            TOKEN=${'$'}(cat "${'$'}H/.bridge_token" 2>/dev/null)
            SESSION=${'$'}{HERMES_SESSION:-terminal}
            if [ -z "${'$'}PORT" ] || [ -z "${'$'}TOKEN" ]; then
              echo "hermes: agent bridge not running (open the Hermes app)" >&2; exit 1
            fi
            send() {
              tmp=${'$'}(mktemp)
              printf '%s' "${'$'}1" > "${'$'}tmp"
              wget -qO- --header "Authorization: Bearer ${'$'}TOKEN" --header "X-Hermes-Session: ${'$'}SESSION" \
                --header "Content-Type: text/plain; charset=utf-8" \
                --post-file="${'$'}tmp" "http://127.0.0.1:${'$'}PORT/v1/chat" || echo "hermes: request failed" >&2
              rm -f "${'$'}tmp"
              echo
            }
            case "${'$'}1" in
              -h|--help|help)
                echo "usage: hermes                 interactive chat"
                echo "       hermes \"prompt\"        one-shot prompt"
                echo "       hermes /command [args] slash command (/help lists them)"
                echo "       hermes status|model|cron|skills|sessions|memory|tools|doctor"
                exit 0 ;;
              status|model|cron|skills|sessions|memory|tools|toolsets|usage|insights|personality|platforms|version|config)
                cmd="/${'$'}*"; send "${'$'}cmd"; exit 0 ;;
              doctor)
                send "/status"; echo; uname -a; cat /etc/alpine-release; exit 0 ;;
              chat) shift ;;
            esac
            if [ ${'$'}# -gt 0 ]; then
              [ "${'$'}1" = "-q" ] && shift
              send "${'$'}*"; exit 0
            fi
            printf '\033[38;5;220m☤ Hermes Agent\033[0m — type a message, /help for commands, /exit to quit\n'
            while true; do
              printf '\033[38;5;179m❯ \033[0m'
              IFS= read -r line || break
              case "${'$'}line" in
                "") continue ;;
                /exit|/quit|exit|quit) break ;;
              esac
              send "${'$'}line"
            done
        """.trimIndent() + "\n"
    }
}

/** Strip ANSI escape sequences and carriage-return redraws from captured command output. */
object TerminalSanitizer {
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]|\u001B[=>]")

    fun clean(s: String): String {
        val noAnsi = ansi.replace(s, "")
        // Collapse progress-bar style "\r" overwrites to the final state of each line.
        return noAnsi.split('\n').joinToString("\n") { line ->
            if ('\r' in line) line.split('\r').lastOrNull { it.isNotEmpty() } ?: "" else line
        }
    }
}

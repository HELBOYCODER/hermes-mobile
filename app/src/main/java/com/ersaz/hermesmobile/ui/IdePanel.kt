package com.ersaz.hermesmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.ui.theme.LocalSkin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android IDE, ON by default inside the Coding workspace (not a submenu).
 * Concepts only — no code copied from any other IDE project (Ghostty/Ghostide
 * or otherwise); every piece here reuses Hermes' own building blocks:
 * the sandbox filesystem and terminal, the sandbox git binary, the
 * SkillsScreen-style monospace editor, and Hermes' own native/Pi engine
 * selection for code generation. Nothing here is a desktop IDE and
 * nothing is faked: "Build APK" runs Gradle in the sandbox only when an
 * Android SDK is actually present, and otherwise says so with the
 * install steps.
 */
@Composable
fun IdePanel(rt: HermesRuntime, vm: ChatViewModel, onChat: () -> Unit, onNavigate: (String) -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    val project = rt.settings.codingProject.ifBlank { "/root/projects" }
    val hostDir = remember(project, refresh) { runCatching { rt.sandbox.hostPath(project).canonicalFile }.getOrNull() }

    fun runInSandbox(command: String) {
        if (busy) return
        busy = true; output = "Running…"
        scope.launch {
            val r = runCatching { rt.sandbox.exec(command, cwd = project, timeoutSec = 600) }.getOrNull()
            output = if (r == null) "Could not run in the sandbox." else "exit=${r.exitCode}\n" + r.output.take(20_000)
            busy = false; refresh++
        }
    }

    SectionTitle("Android IDE")
    Hint("Default workspace view: templates, file tree, editor, sandbox terminal, read-only logcat, and git — all on the current project ($project). Code generation uses Hermes' own engine (current: ${rt.settings.engine}); nothing here runs another project's engine.")
    Row {
        OutlinedButton(onClick = { onNavigate("Terminal") }) { Text("Open terminal") }
        TextButton(onClick = { vm.input = "Work in $project. Implement what I describe next, using the ${rt.settings.engine} engine."; onChat() }) { Text("Generate code in chat") }
    }

    // --- templates -------------------------------------------------------
    OutlinedButton(onClick = {
        val dir = hostDir ?: return@OutlinedButton
        dir.mkdirs()
        writeIdeSkeleton(dir)
        output = "Skeleton written to $project (settings.gradle.kts, build.gradle.kts, app module, AndroidManifest.xml, MainActivity.kt). Plugin versions in the template may need adjusting to the SDK on the device."
        refresh++
    }) { Text("Create minimal Kotlin/Compose app skeleton") }

    // --- Claude Quickstarts (17 creatable of 21; 4 skipped, see catalog) --
    val ctx = LocalContext.current
    var showQuickstarts by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showQuickstarts = !showQuickstarts }) { Text(if (showQuickstarts) "Hide quickstart templates" else "Quickstart templates (Claude, 21)") }
    if (showQuickstarts) {
        Hint("Claude Quickstarts (MIT, https://github.com/anthropics/claude-quickstarts): each template creates /root/projects/<name> with its original README.md + a Hermes-written RUN.md (sandbox steps). 17 create on the phone; 4 are skipped (Docker/VNC/infra — desktop only) and their buttons are disabled with the reason. All need YOUR OWN Anthropic API key (Settings → Environment, ANTHROPIC_API_KEY).")
        com.ersaz.hermesmobile.coding.QUICKSTARTS.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, color = skin.text, fontSize = 13.sp)
                    Text(entry.runtime + (entry.skippedReason?.let { " · skipped: $it" } ?: ""), color = skin.dim, fontSize = 11.5.sp)
                }
                OutlinedButton(enabled = entry.skippedReason == null && !busy, onClick = {
                    val result = com.ersaz.hermesmobile.coding.createQuickstart(ctx, rt, entry.slug)
                    output = if (result.startsWith("ERROR:")) result else "Quickstart created at $result (README.md + RUN.md). It is now the current project."
                    refresh++
                }) { Text(if (entry.skippedReason == null) "Create" else "Skipped") }
            }
        }
    }

    // --- Python agent (Claude Agent SDK template, user's own key) --------
    OutlinedButton(onClick = {
        val dir = hostDir ?: return@OutlinedButton
        dir.mkdirs()
        File(dir, "agent.py").writeText(PYTHON_AGENT_SAMPLE)
        File(dir, "requirements.txt").writeText("claude-agent-sdk\n")
        File(dir, "README_PYAGENT.md").writeText(
            "# Python agent (Claude Agent SDK)\n\nTemplate written by Hermes (sample, NOT verified against an installed SDK in this build).\n\nInstall in the sandbox terminal in this folder: `pip install claude-agent-sdk` (or use the Install button below).\nRun: `ANTHROPIC_API_KEY` must be set in Hermes Settings → Environment (YOUR OWN key, never hardcoded here),\nthen `python agent.py`. The sample follows the SDK's public query pattern; if the installed SDK version's API\nnames differ, adjust the imports to that version's docs — the sample does not claim a verified run.\n")
        output = "Python agent template written to $project (agent.py, requirements.txt, README_PYAGENT.md). Install + run steps are in the README; nothing was run yet."
        refresh++
    }) { Text("Python agent (Claude SDK) template") }
    OutlinedButton(onClick = { runInSandbox("pip install claude-agent-sdk 2>&1 | tail -5; python3 -c \"import claude_agent_sdk; print('SDK import OK')\" 2>&1 | tail -3") }) { Text("Install claude-agent-sdk (sandbox pip)") }

    // --- Claude Code CLI (proprietary tool, NOT bundled; user's account) --
    SectionTitle("Claude Code CLI")
    Hint("Claude Code is Anthropic's proprietary CLI — it is NOT bundled and NOT copied. These buttons install the official CLI from npm inside the sandbox and check it. It needs YOUR OWN Claude account (subscription or ANTHROPIC_API_KEY in Environment); interactive login happens in the terminal. Until installed and signed in, this entry shows 'needs your Claude account' and runs nothing.")
    Row {
        OutlinedButton(onClick = { runInSandbox("command -v node >/dev/null 2>&1 || apk add --no-cache nodejs npm; npm install -g @anthropic-ai/claude-code 2>&1 | tail -5; claude --version 2>&1 | head -3") }) { Text("Install Claude Code CLI") }
        OutlinedButton(onClick = { runInSandbox("claude --version 2>&1 | head -5 || echo 'Claude Code CLI not installed — tap Install first, then sign in with your Claude account.'") }) { Text("Check claude") }
    }

    // --- @claude workflow for the user's own repo -------------------------
    OutlinedButton(onClick = {
        val dir = hostDir ?: return@OutlinedButton
        val wf = File(dir, ".github/workflows/claude.yml")
        wf.parentFile?.mkdirs()
        wf.writeText(CLAUDE_WORKFLOW_YAML)
        output = "Wrote .github/workflows/claude.yml into $project. REVIEW it before committing to your repo: it needs a GitHub secret in YOUR repo (ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN — your own, never stored by Hermes, never pasted into chat). Input names per the action's README — verify against https://github.com/anthropics/claude-code-action before committing."
        refresh++
    }) { Text("Add @claude workflow (GitHub)") }

    // --- file tree + editor ----------------------------------------------
    val tree = remember(hostDir, refresh) { listIdeFiles(hostDir).take(200) }
    if (hostDir == null || !hostDir.isDirectory) {
        Text("No project folder yet — start a project above, then its files appear here.", color = skin.dim, fontSize = 12.5.sp)
    } else {
        if (tree.isEmpty()) Text("(empty project folder)", color = skin.dim, fontSize = 12.5.sp)
        tree.forEach { (file, depth) ->
            Row(Modifier.fillMaxWidth().clickable {
                if (file.isFile && file.length() < 500_000) { editing = file; editText = runCatching { file.readText() }.getOrDefault("") }
            }.padding(start = (depth * 14).dp, vertical = 3.dp)) {
                Text(if (file.isDirectory) "📁 " else "📄 ", fontSize = 13.sp)
                Text(file.name, color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
            }
        }
    }
    editing?.let { f ->
        Column(Modifier.padding(top = 8.dp)) {
            Text("✏️ ${f.name}", color = skin.title, fontSize = 14.sp)
            OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth().height(260.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp))
            Row {
                Button(onClick = { f.writeText(editText); editing = null; refresh++ }) { Text("Save") }
                OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
            }
        }
    }

    // --- logcat (read-only) ----------------------------------------------
    OutlinedButton(onClick = {
        if (busy) return@OutlinedButton
        busy = true; output = "Reading logcat…"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200"))
                    p.inputStream.bufferedReader().readText().takeLast(20_000)
                }.getOrDefault("Logcat is not readable on this device.")
            }
            output = text.ifBlank { "(no log lines)" }; busy = false
        }
    }) { Text("Refresh logcat (read-only)") }
    Hint("Logcat here is read-only, and Android restricts third-party apps to their own app's log lines — system-wide logcat needs a system app or ADB. Nothing is cleared or filtered by Hermes.")

    // --- git + build -------------------------------------------------------
    Row {
        OutlinedButton(onClick = { runInSandbox("git status --short") }) { Text("Git status") }
        OutlinedButton(onClick = { runInSandbox("git log --oneline -10") }) { Text("Git log") }
    }
    OutlinedButton(onClick = {
        if (busy) return@OutlinedButton
        val dir = hostDir
        if (dir == null || !dir.isDirectory) { output = "No project folder yet — start a project above first."; return@OutlinedButton }
        if (!File(dir, "gradlew").exists()) {
            output = "No build attempted: this project has no Gradle wrapper (gradlew). Add a wrapper first; this button never reports a build it did not run."
            return@OutlinedButton
        }
        busy = true; output = "Checking for an Android SDK in the sandbox…"
        scope.launch {
            // Real SDK check inside the sandbox; only if an SDK directory
            // with platform-tools is found does the same tap run Gradle.
            val check = runCatching {
                rt.sandbox.exec(
                    "for d in \"\$ANDROID_HOME\" \"\$ANDROID_SDK_ROOT\" /opt/android-sdk /usr/lib/android-sdk \"\$HOME/Android/Sdk\"; do " +
                        "if [ -d \"\$d/platform-tools\" ]; then echo \"SDK_DIR=\$d\"; break; fi; done; echo CHECK_DONE",
                    cwd = project, timeoutSec = 120,
                )
            }.getOrNull()
            val sdkDir = check?.output?.lines()?.firstOrNull { it.startsWith("SDK_DIR=") }?.removePrefix("SDK_DIR=")
            if (sdkDir.isNullOrBlank()) {
                output = "Android SDK not installed on this device — no build attempted.\n" +
                    "Install steps: (1) install Android cmdline tools in the sandbox, (2) set ANDROID_HOME to the SDK directory, " +
                    "(3) install platform android-35 + build-tools 35.0.0, (4) make sure this project has a Gradle wrapper, " +
                    "then tap Build APK again (it runs ./gradlew assembleDebug here)."
                busy = false; return@launch
            }
            output = "SDK found at $sdkDir — running ./gradlew assembleDebug…"
            val build = runCatching { rt.sandbox.exec("./gradlew assembleDebug", cwd = project, timeoutSec = 900) }.getOrNull()
            output = if (build == null) "Gradle could not be started in the sandbox."
            else "exit=${build.exitCode}\n" + build.output.take(20_000)
            busy = false; refresh++
        }
    }) { Text("Build APK") }
    Hint("Build APK honesty rule: one tap first checks for an Android SDK in the sandbox (ANDROID_HOME / ANDROID_SDK_ROOT / /opt/android-sdk / /usr/lib/android-sdk / ~/Android/Sdk); only if one is found AND the project has a gradlew does it run ./gradlew assembleDebug and show its real exit code and output. Otherwise it reports the missing piece with install steps and attempts no build.")
    // --- ThinkRail concepts (Hermes implementation, ON by default) -----
    SectionTitle("ThinkRail concepts (in Hermes' own IDE)")
    Hint("ThinkRail has no account of its own and neither does this — everything runs on your own provider credentials already in Hermes. The desktop ThinkRail IDE does NOT run on Android; these are its concepts (embedded Pi, living SPEC.md, worktree per task, one-click PR + CI) implemented in Hermes' own panel. The desktop curl install.sh | bash is never used here.")
    // Pi inside the IDE (same SkillStore skills + MCP Hub servers as
    // every other Hermes agent — no separate skill set).
    Row {
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            busy = true; output = "Running Pi here (project as working directory)…"
            scope.launch {
                val thinking = com.ersaz.hermesmobile.agent.PiHarness.mapThinking(rt.settings.reasoningEffort)
                val r = runCatching {
                    com.ersaz.hermesmobile.agent.PiHarness.runPrint(
                        "Work in this project. Read SPEC.md first if it exists, then follow the user's current task.",
                        project, rt.settings.model, thinking)
                }.getOrNull()
                output = r ?: "Pi harness not installed — falling back to the native engine (see the Pi status in Settings → Engine)."
                busy = false; refresh++
            }
        }) { Text("Run Pi here") }
        OutlinedButton(onClick = {
            val spec = com.ersaz.hermesmobile.agent.SpecFile.read(rt, project)
            output = spec ?: "No SPEC.md in this project yet — create one below."
        }) { Text("View SPEC.md") }
    }
    var specIntent by remember { mutableStateOf("") }
    OutlinedTextField(value = specIntent, onValueChange = { specIntent = it }, label = { Text("Project intent (for a new SPEC.md)") },
        modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        output = com.ersaz.hermesmobile.agent.SpecFile.ensure(rt, project, specIntent.ifBlank { "(no intent stated yet)" })
        refresh++
    }) { Text("Create SPEC.md (Spec Kit template + intent)") }
    Hint("Living SPEC.md: the agent reads it first at the start of every session and updates it after each completed task (see the spec-first routing in chat). It lives in the project folder, so it travels with ZIP export and git.")
    // Worktree per task + parallel agents.
    var taskName by remember { mutableStateOf("") }
    var worktrees by remember { mutableStateOf<List<com.ersaz.hermesmobile.agent.Worktrees.Entry>>(emptyList()) }
    var pendingDiscard by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(value = taskName, onValueChange = { taskName = it }, label = { Text("Task name (new worktree)") },
        modifier = Modifier.fillMaxWidth())
    Row {
        OutlinedButton(enabled = taskName.isNotBlank(), onClick = {
            if (busy) return@OutlinedButton
            busy = true; scope.launch {
                output = com.ersaz.hermesmobile.agent.Worktrees.create(rt, project, taskName.trim())
                worktrees = com.ersaz.hermesmobile.agent.Worktrees.list(rt, project)
                busy = false; refresh++
            }
        }) { Text("Create worktree") }
        OutlinedButton(onClick = {
            scope.launch { worktrees = com.ersaz.hermesmobile.agent.Worktrees.list(rt, project) }
        }) { Text("Refresh worktrees") }
    }
    worktrees.forEach { w ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("${w.path} — branch ${w.branch.ifBlank { "(detached)" }} @ ${w.head}", color = skin.text, fontSize = 12.sp)
            Row {
                OutlinedButton(onClick = {
                    scope.launch { output = com.ersaz.hermesmobile.agent.Worktrees.merge(rt, project, w.branch) }
                }) { Text("Merge into main working copy") }
                OutlinedButton(onClick = {
                    if (pendingDiscard == w.path) {
                        pendingDiscard = null
                        scope.launch {
                            output = com.ersaz.hermesmobile.agent.Worktrees.discard(rt, project, w.path, w.branch)
                            worktrees = com.ersaz.hermesmobile.agent.Worktrees.list(rt, project)
                        }
                    } else pendingDiscard = w.path
                }) { Text(if (pendingDiscard == w.path) "Confirm discard?" else "Discard worktree") }
            }
            Row {
                OutlinedButton(onClick = {
                    scope.launch { output = com.ersaz.hermesmobile.agent.Worktrees.createPr(rt, project, w.branch, "Work from ${w.branch}") }
                }) { Text("Create pull request") }
                OutlinedButton(onClick = {
                    scope.launch { output = com.ersaz.hermesmobile.agent.Worktrees.ciStatus(rt, project, w.branch) }
                }) { Text("CI status") }
            }
        }
    }
    Hint("PR/CI use your own GitHub token from Settings (githubToken) and this project's own remote. No remote or no token → the buttons say exactly that; no PR is ever faked.")
    // --- Xerj local search (default ON) --------------------------------
    SectionTitle("Local search (Xerj)")
    var refDir by remember { mutableStateOf("") }
    Row {
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            busy = true; scope.launch {
                output = "Xerj status: " + com.ersaz.hermesmobile.agent.XerjSetup.status(rt) +
                    "\nLive path: " + if (com.ersaz.hermesmobile.agent.XerjSetup.status(rt).startsWith("installed")) "Xerj index (BM25 + hybrid, file:line snippets via the xerj MCP server)" else "BM25-ranked SearchFilesTool fallback (Xerj binary not installed)"
                busy = false
            }
        }) { Text("Xerj status") }
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            busy = true; scope.launch {
                output = com.ersaz.hermesmobile.agent.XerjSetup.ensureInstalled(rt)
                busy = false
            }
        }) { Text("Install Xerj") }
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            busy = true; scope.launch {
                output = com.ersaz.hermesmobile.agent.XerjSetup.indexProjects(rt)
                busy = false
            }
        }) { Text("Index projects") }
    }
    OutlinedTextField(value = refDir, onValueChange = { refDir = it }, label = { Text("Open-source repo dir to index (reference search)") },
        modifier = Modifier.fillMaxWidth())
    OutlinedButton(enabled = refDir.isNotBlank(), onClick = {
        if (busy) return@OutlinedButton
        busy = true; scope.launch {
            output = com.ersaz.hermesmobile.agent.XerjSetup.indexReference(rt, refDir.trim())
            busy = false
        }
    }) { Text("Index reference repo") }
    Hint("Xerj is local-only: the index lives in the sandbox (/root/.xerj, outside /root/projects), no vector database, no external service, nothing uploaded. Agent memory search is ON by default for your projects.")
    // --- Design workspace: Components + Film (ON by default) -----------
    DesignWorkspacePanel(rt, vm, onNavigate)
    if (output.isNotBlank()) {
        Text(output, color = skin.label, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
            modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()))
    }
    Spacer(Modifier.height(8.dp))
}

private fun listIdeFiles(root: File?): List<Pair<File, Int>> {
    if (root == null || !root.isDirectory) return emptyList()
    val skip = setOf(".git", "build", ".gradle", ".idea")
    val out = mutableListOf<Pair<File, Int>>()
    fun walk(dir: File, depth: Int) {
        if (out.size > 400) return
        (dir.listFiles() ?: emptyArray())
            .filter { it.name !in skip }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { f ->
                out.add(f to depth)
                if (f.isDirectory && depth < 6) walk(f, depth + 1)
            }
    }
    walk(root, 0)
    return out
}

/** Hermes-written sample agent for the Claude Agent SDK template.
 *  NOT verified against an installed SDK in this build — the template
 *  README says so, and the user adjusts to the installed version. */
private const val PYTHON_AGENT_SAMPLE =
    "\"\"\"Sample agent using the Claude Agent SDK (claude-agent-sdk).\n" +
    "Runs in the Alpine sandbox. Uses YOUR OWN Anthropic API key from the\n" +
    "environment (ANTHROPIC_API_KEY, set in Hermes Settings -> Environment).\n" +
    "Never hardcode a key here. Install: pip install claude-agent-sdk\n" +
    "\"\"\"\n" +
    "import asyncio\n" +
    "import os\n" +
    "\n" +
    "from claude_agent_sdk import ClaudeAgentOptions, query\n" +
    "\n" +
    "\n" +
    "async def main():\n" +
    "    if not os.environ.get(\"ANTHROPIC_API_KEY\"):\n" +
    "        print(\"Set ANTHROPIC_API_KEY in Hermes Settings -> Environment first.\")\n" +
    "        return\n" +
    "    async for msg in query(\n" +
    "        prompt=\"Say hello and describe what you can do in one paragraph.\",\n" +
    "        options=ClaudeAgentOptions(model=\"claude-sonnet-4-5\"),\n" +
    "    ):\n" +
    "        print(msg)\n" +
    "\n" +
    "\n" +
    "if __name__ == \"__main__\":\n" +
    "    asyncio.run(main())\n"

/** Hermes-written GitHub workflow template for anthropics/claude-code-action.
 *  The user reviews it and commits it to THEIR repo themselves; Hermes
 *  never touches their repo and never stores their secret. Input names
 *  follow the action's README and should be re-checked there at commit time. */
private const val CLAUDE_WORKFLOW_YAML =
    "# Generated by Hermes' IDE panel — REVIEW before committing to your repo.\n" +
    "# Required GitHub secret (in YOUR repo's Settings -> Secrets, never in Hermes, never in chat):\n" +
    "#   ANTHROPIC_API_KEY (your own Anthropic API key) OR CLAUDE_CODE_OAUTH_TOKEN (your Claude Pro/Max OAuth token).\n" +
    "# Action docs: https://github.com/anthropics/claude-code-action\n" +
    "name: Claude Code\n" +
    "on:\n" +
    "  issue_comment:\n" +
    "    types: [created]\n" +
    "  pull_request_review_comment:\n" +
    "    types: [created]\n" +
    "jobs:\n" +
    "  claude:\n" +
    "    if: contains(github.event.comment.body, '@claude')\n" +
    "    runs-on: ubuntu-latest\n" +
    "    steps:\n" +
    "      - uses: actions/checkout@v4\n" +
    "      - uses: anthropics/claude-code-action@v1\n" +
    "        with:\n" +
    "          anthropic_api_key: \${{ secrets.ANTHROPIC_API_KEY }}\n"

private fun writeIdeSkeleton(dir: File) {
    fun write(rel: String, content: String) {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        if (!f.exists()) f.writeText(content)
    }
    write("settings.gradle.kts", "rootProject.name = \"app\"\ninclude(\":app\")\n")
    write("build.gradle.kts", "// Skeleton — adjust plugin versions to the SDK on the device.\nplugins {\n    id(\"com.android.application\") version \"8.5.2\" apply false\n    id(\"org.jetbrains.kotlin.android\") version \"2.0.21\" apply false\n    id(\"org.jetbrains.kotlin.compose\") version \"2.0.21\" apply false\n}\n")
    write("app/build.gradle.kts", "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n    id(\"org.jetbrains.kotlin.compose\")\n}\nandroid {\n    namespace = \"com.example.app\"\n    compileSdk = 35\n    defaultConfig { applicationId = \"com.example.app\"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = \"1.0\" }\n    buildFeatures { compose = true }\n}\ndependencies {\n    implementation(platform(\"androidx.compose:compose-bom:2024.09.00\"))\n    implementation(\"androidx.compose.ui:ui\")\n    implementation(\"androidx.compose.material3:material3\")\n    implementation(\"androidx.activity:activity-compose:1.9.0\")\n}\n")
    write("app/src/main/AndroidManifest.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"App\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\">\n            <intent-filter><action android:name=\"android.intent.action.MAIN\" /><category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter>\n        </activity>\n    </application>\n</manifest>\n")
    write("app/src/main/java/com/example/app/MainActivity.kt", "package com.example.app\n\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\nimport androidx.compose.material3.Text\n\nclass MainActivity : ComponentActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContent { Text(\"Hello from the Hermes IDE skeleton\") }\n    }\n}\n")
    write("README_IDE.md", "# IDE skeleton\n\nMinimal Kotlin/Compose app skeleton created by Hermes' Android IDE panel.\nBuild it only where an Android SDK + Gradle wrapper exist; the Build APK\nbutton checks for both first and never reports a build it did not run.\n")
}

package com.ersaz.hermesmobile.coding

import android.content.Context
import java.io.File

/**
 * Claude Quickstarts as IDE project templates (Hermes' own catalog).
 * Source: https://github.com/anthropics/claude-quickstarts (MIT).
 * This snapshot has 21 starters: 9 top-level + 12 managed-agents
 * sub-apps. 17 are creatable on the phone; 4 are skipped (see
 * [skippedReason]) because they need Docker, VNC/desktop control, or
 * sandbox-provisioning infrastructure. Each template ships its
 * original README.md verbatim plus a Hermes-written RUN.md with
 * sandbox-adapted steps. Nothing here runs without the user's own
 * Anthropic API key (Settings → Environment, ANTHROPIC_API_KEY).
 */
data class QuickstartEntry(val slug: String, val title: String, val runtime: String, val skippedReason: String? = null)

val QUICKSTARTS = listOf(
    QuickstartEntry("agents", "Agents", "Python"),
    QuickstartEntry("autonomous-coding", "Autonomous coding", "Python"),
    QuickstartEntry("browser-toolset", "Browser toolset", "Python"),
    QuickstartEntry("browser-use-demo", "Browser use demo", "Docker", "Needs Docker containers — desktop only."),
    QuickstartEntry("computer-toolset", "Computer toolset", "VNC", "Drives a real computer screen via VNC — desktop GUI control, not available on a phone."),
    QuickstartEntry("computer-use-best-practices", "Computer use best practices", "Python"),
    QuickstartEntry("computer-use-demo", "Computer use demo", "Docker", "Needs Docker containers and a desktop display — desktop only."),
    QuickstartEntry("customer-support-agent", "Customer support agent", "Node/Next.js"),
    QuickstartEntry("financial-data-analyst", "Financial data analyst", "Node/Next.js"),
    QuickstartEntry("managed-agents-assistant-ui", "Managed agents: assistant-ui", "Node/TS"),
    QuickstartEntry("managed-agents-chat-sdk", "Managed agents: chat-sdk", "Node/TS"),
    QuickstartEntry("managed-agents-copilot-kit-ag-ui", "Managed agents: copilot-kit-ag-ui", "Node/TS"),
    QuickstartEntry("managed-agents-daily-brief", "Managed agents: daily-brief", "See README"),
    QuickstartEntry("managed-agents-knowledge-wiki", "Managed agents: knowledge-wiki", "Python"),
    QuickstartEntry("managed-agents-linear", "Managed agents: linear", "Node/TS"),
    QuickstartEntry("managed-agents-mcp-server-typescript", "Managed agents: mcp-server-typescript", "Node/TS"),
    QuickstartEntry("managed-agents-roadtrip-planner", "Managed agents: roadtrip-planner", "Node/TS"),
    QuickstartEntry("managed-agents-self-hosted-sandboxes", "Managed agents: self-hosted-sandboxes", "Infra", "Provisions sandbox infrastructure — a server capability, not available in the phone sandbox."),
    QuickstartEntry("managed-agents-sentry-autofix", "Managed agents: sentry-autofix", "Node/TS"),
    QuickstartEntry("managed-agents-sentry", "Managed agents: sentry", "Python"),
    QuickstartEntry("managed-agents-slack", "Managed agents: slack", "Node/TS"),
)

/** Creates /root/projects/<slug> (guest path) with README.md + RUN.md
 *  from assets, and returns its guest path. Returns an error string
 *  starting with "ERROR:" on failure. Skipped entries are never created. */
fun createQuickstart(ctx: Context, rt: com.ersaz.hermesmobile.HermesRuntime, slug: String): String {
    val entry = QUICKSTARTS.firstOrNull { it.slug == slug } ?: return "ERROR: unknown starter $slug"
    if (entry.skippedReason != null) return "ERROR: ${entry.title} is skipped on the phone: ${entry.skippedReason}"
    return runCatching {
        val current = rt.settings.codingProject.ifBlank { "/root/projects" }
        val parentGuest = current.trimEnd('/').substringBeforeLast('/', "/root/projects")
        val guestTarget = "$parentGuest/$slug"
        val hostTarget = rt.sandbox.hostPath(guestTarget).canonicalFile.apply { mkdirs() }
        for (name in listOf("README.md", "RUN.md")) {
            ctx.assets.open("quickstarts/$slug/$name").use { input ->
                File(hostTarget, name).outputStream().use { input.copyTo(it) }
            }
        }
        rt.settings.codingProject = guestTarget
        guestTarget
    }.getOrElse { "ERROR: ${it.message}" }
}

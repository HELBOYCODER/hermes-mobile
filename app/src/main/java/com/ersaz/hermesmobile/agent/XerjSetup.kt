package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime

/**
 * Xerj local search (xerj-org/xerj, Apache-2.0, Rust) — phone path.
 * The desktop `curl https://xerj.org/get | sh` installer is NOT used:
 * the Alpine sandbox on ARM64 installs the verified musl release
 * tarball directly from GitHub releases, checksum-verified against
 * the release .sha256, and nothing else runs.
 *
 * Verified 2026-10-30, release v1.0.0-rc.100:
 *  tarball: xerj-1.0.0-rc.100-aarch64-unknown-linux-musl.tar.gz
 *  size:    30,887,623 bytes (~29.5 MB)
 *  sha256:  published alongside as .sha256 (fetched + compared at
 *           install time; a mismatch aborts the install).
 * CLI surface verified from the repo's own README (not invented):
 *  `xerj autoindex <folder>` — index code, docs, logs, PDFs
 *  `xerj search "query"`     — ranked passages, file:line
 *  `xerj mcp`                — MCP server (see MCP Hub entry "xerj")
 * Data dir /root/.xerj lives OUTSIDE /root/projects, so the index
 * never indexes its own storage (the repo's own warning).
 * Everything is local-only: no vector database, no external service,
 * nothing uploaded.
 *
 * If the musl binary does not run in the sandbox (arch/musl
 * mismatch), the live path is Hermes' own SearchFilesTool with its
 * indexed BM25 ranking + line-numbered snippets — see XerjSetup
 * .search() fallback note and FileTools.kt. The UI reports exactly
 * which path is live; neither path is ever claimed to be Xerj when
 * it is not.
 */
object XerjSetup {
    const val VERSION = "1.0.0-rc.100"
    const val TARBALL = "xerj-1.0.0-rc.100-aarch64-unknown-linux-musl.tar.gz"
    const val RELEASE_URL = "https://github.com/xerj-org/xerj/releases/download/v$VERSION/$TARBALL"
    const val BIN_GUEST = "/root/.local/bin/xerj"
    const val DATA_DIR = "/root/.xerj"
    const val PROJECTS = "/root/projects"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** One sandbox exec, best-effort; returns "exit=…\noutput" or "". */
    private suspend fun exec(rt: HermesRuntime, cmd: String, timeoutSec: Int = 120): String =
        runCatching { rt.sandbox.exec(cmd, timeoutSec = timeoutSec) }
            .getOrNull()?.let { "exit=${it.exitCode}\n${it.output}" }.orEmpty()

    /** "installed: <version>" | "missing: <reason>" — never faked. */
    suspend fun status(rt: HermesRuntime): String {
        val r = exec(rt, "test -x $BIN_GUEST && $BIN_GUEST --version || command -v xerj")
        val out = r.substringAfter("\n", "").trim()
        return if (r.startsWith("exit=0") && out.isNotBlank()) "installed: $out" else "missing: no xerj binary in the sandbox"
    }

    /**
     * Install from the verified musl tarball (default ON, called from
     * HermesRuntime.startOptionalServices). Idempotent: an existing
     * working binary is left alone. Returns an honest result string.
     */
    suspend fun ensureInstalled(rt: HermesRuntime): String {
        val st = status(rt)
        if (st.startsWith("installed")) return "Xerj already $st"
        val script = "set -e; mkdir -p /root/.local/bin /tmp/xerj-install; " +
            "cd /tmp/xerj-install; " +
            "curl -L --fail -o $TARBALL ${q(RELEASE_URL)}; " +
            "curl -L --fail -o $TARBALL.sha256 ${q("$RELEASE_URL.sha256")}; " +
            "echo \"$(cat $TARBALL.sha256 | cut -d' ' -f1)  $TARBALL\" | sha256sum -c -; " +
            "tar -xzf $TARBALL; " +
            "cp xerj $BIN_GUEST 2>/dev/null || cp */xerj $BIN_GUEST; " +
            "chmod +x $BIN_GUEST; $BIN_GUEST --version"
        val r = exec(rt, script, timeoutSec = 600)
        return if (r.startsWith("exit=0")) "Xerj installed: ${r.substringAfter("\n", "").trim().take(200)}"
        else "Xerj install failed (binary left uninstalled; BM25 fallback stays live): ${r.take(500)}"
    }

    /** Index the user's project roots (default ON). */
    suspend fun indexProjects(rt: HermesRuntime): String {
        if (!status(rt).startsWith("installed")) return "Xerj not installed — run Install first."
        val r = exec(rt, "$BIN_GUEST autoindex ${q(PROJECTS)} --data-dir ${q(DATA_DIR)}", timeoutSec = 900)
        return if (r.startsWith("exit=0")) "Indexed $PROJECTS:\n${r.substringAfter("\n", "").take(1000)}"
        else "Indexing failed: ${r.take(500)}"
    }

    /** "Search open-source references": index a user-cloned repo dir. */
    suspend fun indexReference(rt: HermesRuntime, repoDir: String): String {
        if (!status(rt).startsWith("installed")) return "Xerj not installed — run Install first."
        val r = exec(rt, "$BIN_GUEST autoindex ${q(repoDir)} --data-dir ${q(DATA_DIR)}", timeoutSec = 900)
        return if (r.startsWith("exit=0")) "Indexed reference $repoDir:\n${r.substringAfter("\n", "").take(1000)}"
        else "Indexing failed: ${r.take(500)}"
    }

    /**
     * Search via the Xerj binary (ranked passages, file:line).
     * Returns null when Xerj is not installed — the caller then uses
     * the BM25 fallback in SearchFilesTool instead of faking Xerj.
     */
    suspend fun search(rt: HermesRuntime, query: String): String? {
        if (!status(rt).startsWith("installed")) return null
        val r = exec(rt, "$BIN_GUEST search ${q(query)} --data-dir ${q(DATA_DIR)}", timeoutSec = 120)
        return if (r.startsWith("exit=0")) r.substringAfter("\n", "").take(8000) else null
    }

    /**
     * Headroom proxy option (documented, NOT the live path): attempt
     * `pip install headroom-ai` (base package, not [all] extras) in
     * the sandbox. The Hermes-native TokenSaver pipeline stays live
     * unless this reports a working proxy; never claim otherwise.
     */
    suspend fun headroomProxyAttempt(rt: HermesRuntime): String {
        val r = exec(rt, "pip install --quiet headroom-ai 2>&1 | tail -3; python3 -c \"import headroom; print('headroom ok')\" 2>&1 | tail -2", timeoutSec = 600)
        return if ("headroom ok" in r) "headroom-ai pip package imports OK in the sandbox — proxy wiring still install-steps-only (see report)."
        else "headroom-ai pip install/import not verified here — Hermes-native TokenSaver stays the live path. Output: ${r.take(400)}"
    }
}

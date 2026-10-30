package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nightly-learning rules (Sno concept, Hermes implementation).
 * NOTHING is auto-adopted: the nightly job only DRAFTS proposals
 * (into proposed.json). A rule becomes active ONLY after the user
 * approves it — via the morning-brief card in chat or /rules approve.
 * Approved rules live in approved.md and are injected into every
 * future agent prompt (see PromptBuilder). Rejected rules go to
 * rejected.json and are never re-proposed.
 *
 * Files (all under $HERMES_HOME/rules/):
 *  proposed.json — [{id, rule, reason}]
 *  approved.md   — approved rule texts (prompt-injected)
 *  rejected.json — [ids] never proposed again
 */
class RulesStore(private val rt: HermesRuntime) {
    private val dir get() = File(rt.hermesHome, "rules").apply { mkdirs() }
    private val proposedFile get() = File(dir, "proposed.json")
    private val approvedFile get() = File(dir, "approved.md")
    private val rejectedFile get() = File(dir, "rejected.json")

    data class Proposal(val id: String, val rule: String, val reason: String)

    fun proposals(): List<Proposal> = runCatching {
        if (!proposedFile.isFile) return emptyList()
        val arr = JSONArray(proposedFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Proposal(o.optString("id"), o.optString("rule"), o.optString("reason"))
        }
    }.getOrDefault(emptyList())

    fun rejectedIds(): Set<String> = runCatching {
        if (!rejectedFile.isFile) return emptySet()
        val arr = JSONArray(rejectedFile.readText())
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun approvedText(): String = if (approvedFile.isFile) approvedFile.readText().take(4000) else ""

    fun briefText(): String {
        val list = proposals()
        if (list.isEmpty()) return "No proposed rules pending. (Nightly learning drafts proposals; nothing is adopted without your approval.)"
        return buildString {
            append("Morning brief — proposed rules (approve each to activate, reject to discard forever):\n")
            list.forEachIndexed { i, p -> append("${i + 1}. ${p.rule}\n   Why: ${p.reason}\n") }
            append("Use /rules approve <n> or /rules reject <n>, or the buttons above.")
        }
    }

    /** Approve proposal #n (1-based as listed): appends to approved.md. */
    fun approve(n: Int): String? {
        val list = proposals().toMutableList()
        val p = list.getOrNull(n - 1) ?: return null
        approvedFile.appendText("\n- ${p.rule}\n")
        list.removeAt(n - 1)
        saveProposals(list)
        return "Approved: \"${p.rule}\" — now active in future runs."
    }

    /** Reject proposal #n: records the id so it is never re-proposed. */
    fun reject(n: Int): String? {
        val list = proposals().toMutableList()
        val p = list.getOrNull(n - 1) ?: return null
        val rejected = rejectedIds().toMutableSet().also { it.add(p.id) }
        rejectedFile.writeText(JSONArray(rejected.toList()).toString())
        list.removeAt(n - 1)
        saveProposals(list)
        return "Rejected: \"${p.rule}\" — discarded and never re-proposed."
    }

    private fun saveProposals(list: List<Proposal>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("id", p.id).put("rule", p.rule).put("reason", p.reason)) }
        proposedFile.writeText(arr.toString())
    }

    companion object {
        /** Nightly job prompt (seeded once, 03:00). It only drafts. */
        const val NIGHTLY_PROMPT =
            "Nightly learning review (do NOT change any behaviour yet): review today's sessions in \$HERMES_HOME/sessions " +
            "and today's work. Extract candidate lessons as proposed RULES — each a single actionable sentence with a reason. " +
            "Read \$HERMES_HOME/rules/rejected.json first and NEVER re-propose a rejected id. Write the proposals (new ids only) " +
            "to \$HERMES_HOME/rules/proposed.json as [{id, rule, reason}]. Do NOT append to approved.md — approval is the user's job " +
            "in the morning brief. Then summarize how many proposals you drafted. If nothing was learned, write an empty list and say so."
    }
}

package com.ersaz.hermesmobile.agent

import com.ersaz.hermesmobile.HermesRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Graphify concept, rebuilt for Android in Hermes' own code (the desktop `graphify` CLI is
 * never run on the phone). A DesignGraph is a small index of the current project's real
 * design elements — screens, @Composable components, design tokens (hex colours) and CSS
 * styles — plus "uses" edges between files and the components they reference. It is built
 * only when the user taps Refresh in DesignWorkspace (never on chat open, never in the
 * background), stored as `.hermes/design-graph.json` under the project folder, and the
 * agent can read that file when designing. Nothing is invented: a node exists only if a
 * project file actually contains it, and the counts shown are the counts found.
 */
data class DesignNode(val kind: String, val name: String, val path: String) {
    fun toJson() = JSONObject().put("kind", kind).put("name", name).put("path", path)
    companion object { fun fromJson(o: JSONObject) = DesignNode(o.optString("kind"), o.optString("name"), o.optString("path")) }
}

data class DesignEdge(val from: String, val to: String) {
    fun toJson() = JSONObject().put("from", from).put("to", to)
    companion object { fun fromJson(o: JSONObject) = DesignEdge(o.optString("from"), o.optString("to")) }
}

data class DesignGraph(val nodes: List<DesignNode>, val edges: List<DesignEdge>) {
    fun toJson() = JSONObject()
        .put("nodes", JSONArray().also { a -> nodes.forEach { a.put(it.toJson()) } })
        .put("edges", JSONArray().also { a -> edges.forEach { a.put(it.toJson()) } })
    fun count(kind: String) = nodes.count { it.kind == kind }
    fun connections(name: String) = edges.filter { it.from == name || it.to == name }
        .map { if (it.from == name) it.to else it.from }.distinct()

    companion object {
        fun fromJson(s: String): DesignGraph? = runCatching {
            val o = JSONObject(s)
            val nodes = o.optJSONArray("nodes")?.let { a -> (0 until a.length()).map { DesignNode.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val edges = o.optJSONArray("edges")?.let { a -> (0 until a.length()).map { DesignEdge.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            DesignGraph(nodes, edges)
        }.getOrNull()
    }
}

object DesignGraphBuilder {
    private val EXTENSIONS = setOf("kt", "kts", "xml", "html", "css", "js", "ts", "mjs", "json", "md")
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".hermes", ".gradle", "out")
    private val COMPOSABLE = Regex("""@Composable[\s\S]{0,300}?fun\s+(\w+)""")
    private val HEX = Regex("""#[0-9A-Fa-f]{6}\b""")
    private val CSS_CLASS = Regex("""\.([A-Za-z_-][\w-]*)\s*\{""")
    private const val MAX_FILES = 600
    private const val MAX_BYTES = 200_000L

    /** Scan the project on disk. Call from Dispatchers.IO; returns only what was found. */
    fun build(rt: HermesRuntime, projectGuestDir: String): DesignGraph {
        val root = rt.sandbox.hostPath(projectGuestDir)
        if (!root.exists()) return DesignGraph(emptyList(), emptyList())
        val files = ArrayList<File>()
        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS && it.length() <= MAX_BYTES }
            .take(MAX_FILES).forEach { files.add(it) }
        val nodes = ArrayList<DesignNode>()
        val texts = HashMap<String, String>()
        for (f in files) {
            val rel = f.relativeTo(root).path
            val text = runCatching { f.readText() }.getOrDefault("")
            texts[rel] = text
            if (f.name.endsWith("Screen.kt") || f.name.endsWith("Panel.kt") || rel.contains("/ui/")) {
                nodes += DesignNode("screen", f.nameWithoutExtension, rel)
            }
            if (f.extension.lowercase() in setOf("kt", "kts")) {
                COMPOSABLE.findAll(text).map { it.groupValues[1] }.distinct().forEach { name ->
                    nodes += DesignNode("component", name, rel)
                }
            }
            HEX.findAll(text).map { it.value.uppercase() }.distinct().take(40).forEach { hex ->
                nodes += DesignNode("token", hex, rel)
            }
            if (f.extension.lowercase() == "css") {
                CSS_CLASS.findAll(text).map { it.groupValues[1] }.distinct().take(40).forEach { cls ->
                    nodes += DesignNode("style", ".$cls", rel)
                }
            }
        }
        val edges = ArrayList<DesignEdge>()
        val components = nodes.filter { it.kind == "component" }.distinctBy { it.name }
        for (c in components) {
            val word = Regex("""\b${Regex.escape(c.name)}\b""")
            for ((rel, text) in texts) {
                if (rel != c.path && word.containsMatchIn(text)) {
                    edges += DesignEdge(rel, c.name)
                    if (edges.size >= 500) break
                }
            }
            if (edges.size >= 500) break
        }
        return DesignGraph(nodes.distinctBy { it.kind + "\u0000" + it.name + "\u0000" + it.path }, edges.distinct())
    }

    fun fileFor(rt: HermesRuntime, projectGuestDir: String): File {
        val dir = File(rt.sandbox.hostPath(projectGuestDir), ".hermes")
        dir.mkdirs()
        return File(dir, "design-graph.json")
    }

    fun save(rt: HermesRuntime, projectGuestDir: String, graph: DesignGraph) {
        runCatching { fileFor(rt, projectGuestDir).writeText(graph.toJson().toString(2)) }
    }

    fun load(rt: HermesRuntime, projectGuestDir: String): DesignGraph? =
        runCatching { DesignGraph.fromJson(fileFor(rt, projectGuestDir).readText()) }.getOrNull()
}

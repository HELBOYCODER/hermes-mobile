package com.ersaz.hermesmobile.mcp

/** Header placeholders reference Environment without writing credentials into MCP config. */
object McpEnvironment {
    private val placeholder = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
    fun resolve(value: String, variables: Map<String, String>): String = placeholder.replace(value) { match ->
        val name = match.groupValues[1]
        variables[name]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Set $name in Settings → Environment before connecting this server.")
    }.also { require('\n' !in it && '\r' !in it) { "MCP header values must be a single line" } }
}

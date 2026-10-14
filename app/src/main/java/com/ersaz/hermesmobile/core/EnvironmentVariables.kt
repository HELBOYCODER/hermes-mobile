package com.ersaz.hermesmobile.core

/** Values are literal: no shell expansion, quote stripping, or whitespace loss. */
object EnvironmentVariables {
    fun validName(name: String) = name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
    fun parse(text: String): Map<String, String> = linkedMapOf<String, String>().apply {
        text.lineSequence().forEach { line ->
            val split = line.indexOf('=')
            if (split > 0) {
                val name = line.substring(0, split).trim()
                if (validName(name)) put(name, line.substring(split + 1))
            }
        }
    }
    fun serialize(values: Map<String, String>): String = values.entries.joinToString("\n") { (name, value) ->
        require(validName(name) && '\n' !in value && '\r' !in value && '\u0000' !in value)
        "$name=$value"
    }
    fun redact(text: String, values: Map<String, String>): String = values.filter { (name, value) -> value.isNotEmpty() && (value.length >= 8 || Regex("(?i)(key|token|secret|password|credential)").containsMatchIn(name)) }.values.distinct().sortedByDescending { it.length }
        .fold(text) { result, value -> result.replace(value, "[REDACTED]") }
}

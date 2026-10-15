package com.ersaz.hermesmobile.files

import java.net.URI

object WebLinks {
    fun webTarget(raw: String): String? = runCatching {
        val text = raw.trim().removeSurrounding("<", ">").trimEnd('.', ',', ';', '!', '،', '؛', '؟', '。')
        val uri = URI(text)
        text.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }.getOrNull()
    fun localTarget(raw: String): String? {
        val text = raw.removePrefix("sandbox:").removePrefix("file://").removePrefix("hermes://")
        return text.takeIf { it.startsWith("/root/") && '\u0000' !in it }
    }
}

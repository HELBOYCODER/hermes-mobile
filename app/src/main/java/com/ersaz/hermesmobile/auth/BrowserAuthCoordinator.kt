package com.ersaz.hermesmobile.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File

object BrowserAuthCoordinator {
    fun store(context: Context) = AuthOperationStore(File(context.filesDir, "auth-return.json"))
    fun returnLink(context: Context, kind: String): String {
        val id = store(context).create(kind, System.currentTimeMillis() + 10 * 60_000L)
        return "intent://return/complete?id=$id#Intent;scheme=hermes-auth;package=${context.packageName};component=${context.packageName}/com.ersaz.hermesmobile.MainActivity;end"
    }
    fun onReturn(context: Context, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "hermes-auth" || uri.host != "return" || uri.path != "/complete" || uri.queryParameterNames != setOf("id")) return false
        return store(context).consume(uri.getQueryParameter("id").orEmpty(), System.currentTimeMillis())
    }
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1"))) { "Unsupported sign-in URL" }
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Prefer Chrome, without making it a prerequisite on phones with another browser.
            val chrome = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).setPackage("com.android.chrome")
            if (chrome.resolveActivity(context.packageManager) != null) tab.intent.setPackage("com.android.chrome")
            tab.launchUrl(context, uri)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

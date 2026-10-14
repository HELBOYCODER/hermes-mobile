package com.ersaz.hermesmobile.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets tools ask for runtime permissions mid-run: the activity registers a launcher, a tool awaits the answer.
 * Without a visible activity the request can't be shown, and the tool reports that instead of hanging.
 */
class Permissions(private val ctx: Context) {
    @Volatile var launcher: ActivityResultLauncher<Array<String>>? = null
    @Volatile private var waiting: CompletableDeferred<Map<String, Boolean>>? = null

    fun has(vararg perms: String) = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun ensure(vararg perms: String): Boolean {
        if (has(*perms)) return true
        val l = launcher ?: return false
        val d = CompletableDeferred<Map<String, Boolean>>().also { waiting = it }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { l.launch(arrayOf(*perms)) }
        val res = withTimeoutOrNull(120_000) { d.await() } ?: return false
        return perms.all { res[it] == true || has(it) }
    }

    fun onResult(result: Map<String, Boolean>) { waiting?.complete(result); waiting = null }
}

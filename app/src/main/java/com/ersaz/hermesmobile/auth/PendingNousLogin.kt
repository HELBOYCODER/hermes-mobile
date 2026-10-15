package com.ersaz.hermesmobile.auth

import android.content.Context
import com.ersaz.hermesmobile.core.EnvironmentSecretStore
import org.json.JSONObject

/** Device grant stays private, encrypted and expires on the original deadline. */
class PendingNousLogin(context: Context) {
    private val prefs = context.getSharedPreferences("pending_nous_login", Context.MODE_PRIVATE)
    fun save(dc: DeviceCode) {
        val data = JSONObject().put("device", dc.deviceCode).put("user", dc.userCode)
            .put("uri", dc.verificationUri).put("complete", dc.verificationUriComplete)
            .put("expires", System.currentTimeMillis() + dc.expiresInSeconds.coerceIn(1, 1800) * 1000L)
            .put("interval", dc.intervalSeconds)
        check(prefs.edit().putString("pending", EnvironmentSecretStore.seal(data.toString())).commit())
    }
    fun read(): DeviceCode? = try {
        val raw = prefs.getString("pending", null)
        if (raw == null) null else {
            val data = JSONObject(EnvironmentSecretStore.open(raw))
            val remaining = ((data.getLong("expires") - System.currentTimeMillis()) / 1000).toInt()
            if (remaining <= 0) { clear(); null } else DeviceCode(data.getString("device"), data.getString("user"),
                data.getString("uri"), data.getString("complete"), remaining, data.getInt("interval").coerceIn(1, 30))
        }
    } catch (_: Exception) { clear(); null }
    fun clear() { prefs.edit().remove("pending").commit() }
}

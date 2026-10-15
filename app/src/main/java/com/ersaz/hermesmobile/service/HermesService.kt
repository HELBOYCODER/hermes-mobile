package com.ersaz.hermesmobile.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ersaz.hermesmobile.HermesRuntime
import com.ersaz.hermesmobile.MainActivity
import com.ersaz.hermesmobile.R

/**
 * Foreground service = Hermes "gateway" process: keeps messaging bots, the cron scheduler and
 * the sandbox CLI bridge alive while the app is in the background (bot mode).
 */
class HermesService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recovery = getSharedPreferences("recovery", MODE_PRIVATE)
        if (recovery.getBoolean("safe", false) || recovery.getBoolean("crashed", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val rt = HermesRuntime.get(this)
        if (intent?.action == ACTION_STOP) {
            rt.gateway.stop(); rt.cronRunner.stop()
            rt.settings.botMode = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(rt), type)
        } catch (error: Exception) {
            rt.log.e("service", "Foreground startup failed: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (rt.settings.botMode) rt.gateway.start()
        rt.cronRunner.start()
        runCatching { rt.bridge.start() }.onFailure { rt.log.e("bridge", "Service bridge failed: ${it.message}") }
        if (rt.settings.keepAwake && wakeLock == null) {
            runCatching {
                val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:gateway")
                lock.setReferenceCounted(false)
                lock.acquire()
                wakeLock = lock
            }.onFailure { rt.log.e("service", "CPU wake lock unavailable: ${it.message}") }
        }
        return START_STICKY
    }

    private fun buildNotification(rt: HermesRuntime) = NotificationCompat.Builder(this, HermesRuntime.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setContentTitle("☤ Hermes agent running")
        .setContentText(
            if (rt.settings.botMode) "Messaging gateway on · cron scheduler active" else "Cron scheduler active"
        )
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, HermesService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.ersaz.hermesmobile.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HermesService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HermesService::class.java).setAction(ACTION_STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val recovery = context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
        if (recovery.getBoolean("safe", false) || recovery.getBoolean("crashed", false)) return
        val rt = HermesRuntime.get(context)
        if (rt.settings.botMode || rt.cron.all().any { it.enabled }) HermesService.start(context)
    }
}

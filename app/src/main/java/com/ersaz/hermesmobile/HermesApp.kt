package com.ersaz.hermesmobile

import android.app.Application

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val rt = HermesRuntime.get(this)
        if (rt.settings.botMode || rt.cron.all().any { it.enabled }) {
            runCatching { com.ersaz.hermesmobile.service.HermesService.start(this) }
        }
    }
}

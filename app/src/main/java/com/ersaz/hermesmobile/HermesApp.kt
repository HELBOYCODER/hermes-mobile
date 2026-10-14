package com.ersaz.hermesmobile

import android.app.Activity
import android.app.Application
import android.os.Bundle

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { com.ersaz.hermesmobile.mcp.AppForeground.started++ }
            override fun onActivityStopped(activity: Activity) { com.ersaz.hermesmobile.mcp.AppForeground.started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        val rt = HermesRuntime.get(this)
        if (rt.settings.botMode || rt.cron.all().any { it.enabled }) {
            runCatching { com.ersaz.hermesmobile.service.HermesService.start(this) }
        }
        rt.mcp.connectAllAsync()
    }
}

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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(error.stackTraceToString().take(32000))
                getSharedPreferences("recovery", MODE_PRIVATE).edit().putBoolean("crashed", true).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

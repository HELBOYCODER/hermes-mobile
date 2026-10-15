package com.ersaz.hermesmobile

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Independent of Compose, the Linux sandbox and optional integrations. Never removes user data. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("recovery", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Hermes recovery\nبازیابی هرمس\n\nچت‌ها و تنظیمات شما حفظ شده‌اند. ورود امن، اجرای خودکار MCP و روتر را متوقف می‌کند."
            textSize = 20f
        })
        fun action(label: String, safe: Boolean) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    prefs.edit().putBoolean("crashed", false).putBoolean("safe", safe).putLong("reviewed", System.currentTimeMillis()).commit()
                    startActivity(Intent(this@RecoveryActivity, MainActivity::class.java))
                    finish()
                }
            })
        }
        action("Open safely — ورود امن", true)
        action("Retry normal startup — اجرای عادی", false)
        layout.addView(Button(this).apply {
            text = "Copy crash report"
            setOnClickListener {
                val report = runCatching { java.io.File(filesDir, "last-crash.txt").readText() }.getOrDefault("No crash report available")
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Hermes crash", report))
            }
        })
        setContentView(layout)
    }
}

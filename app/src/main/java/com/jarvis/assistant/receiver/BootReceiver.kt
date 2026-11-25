package com.jarvis.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.JarvisPrefs
import com.jarvis.assistant.service.JarvisWakeService

/**
 * Restarts J.A.R.V.I.S. wake-word service automatically after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = JarvisPrefs(context)
            if (prefs.wakeWordEnabled && JarvisApp.hasMicPermission(context)) {
                JarvisWakeService.startService(context)
            }
        }
    }
}

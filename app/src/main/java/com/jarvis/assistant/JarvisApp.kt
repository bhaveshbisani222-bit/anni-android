package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class JarvisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            val bgChannel = NotificationChannel(
                CHANNEL_WAKE_SERVICE,
                "A.N.N.I. Wake Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hey Anni active even when phone is locked"
                setShowBadge(false)
            }

            val lockChannel = NotificationChannel(
                CHANNEL_LOCK_OVERLAY,
                "A.N.N.I. Lock Screen Activation",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wakes display when Hey Anni is spoken"
                setSound(null, null)
            }

            mgr.createNotificationChannel(bgChannel)
            mgr.createNotificationChannel(lockChannel)
        }
    }

    companion object {
        const val CHANNEL_WAKE_SERVICE = "jarvis_wake_channel"
        const val CHANNEL_LOCK_OVERLAY = "jarvis_lock_channel"

        fun hasMicPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

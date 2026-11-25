package com.jarvis.assistant.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.JarvisPrefs
import com.jarvis.assistant.service.JarvisWakeService

/**
 * Registers J.A.R.V.I.S. as a selectable Digital Assistant in Android Settings.
 */
class JarvisInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        val prefs = JarvisPrefs(this)
        if (prefs.wakeWordEnabled && JarvisApp.hasMicPermission(this)) {
            JarvisWakeService.startService(this)
        }
    }
}

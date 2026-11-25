package com.jarvis.assistant.brain

import android.content.Context
import com.jarvis.assistant.data.JarvisPrefs

data class CommandExecutionOutcome(
    val userTranscript: String,
    val replyText: String
)

/**
 * Routes spoken voice commands (via Gemini Audio or Android STT text) to local Siri actions or JARVIS cloud AI.
 */
class CommandRouter(private val context: Context) {

    private val executor = PhoneActionExecutor(context)

    suspend fun handleAudioWithGemini(
        wavBytes: ByteArray,
        prefs: JarvisPrefs
    ): CommandExecutionOutcome? {
        val result = JarvisApiClient.processAudioWithGemini(
            wavBytes = wavBytes,
            geminiApiKey = prefs.geminiApiKey,
            userName = prefs.userName,
            installedApps = executor.listInstalledAppNames()
        ) ?: return null

        val executedReply = executeStructuredIntent(
            intent = result.intent,
            target = result.target,
            value = result.value,
            fallbackReply = result.reply,
            prefs = prefs
        )
        return CommandExecutionOutcome(
            userTranscript = result.transcript.ifBlank { "(Voice Command)" },
            replyText = executedReply
        )
    }

    suspend fun handleTextCommand(
        rawText: String,
        prefs: JarvisPrefs
    ): CommandExecutionOutcome {
        val clean = rawText.trim().lowercase()
        val userName = prefs.userName

        // 1. Flashlight / Torch
        if (Regex("\\b(torch|flashlight|flash|light)\\b").containsMatchIn(clean)) {
            val turnOff = Regex("\\b(off|band|close|disable)\\b").containsMatchIn(clean)
            val reply = executor.setTorch(!turnOff, userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 2. Phone Call
        if (Regex("\\b(call|dial|phone lagao|phone karo|call lagao|call karo)\\b").containsMatchIn(clean)) {
            val target = extractCallTarget(clean)
            val reply = executor.placeCall(target, userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 3. WhatsApp
        if (clean.contains("whatsapp")) {
            val msgMatch = Regex("(?:message|msg|bhejo|send)\\s+(.+)").find(clean)
            val msg = msgMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val reply = executor.sendWhatsApp("", msg, userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 4. SMS
        if (Regex("\\b(sms|text message)\\b").containsMatchIn(clean)) {
            val reply = executor.sendSms("", "", userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 5. Alarm
        if (clean.contains("alarm")) {
            val (h, m) = parseHourMinute(clean)
            val reply = executor.setAlarm(h, m, "J.A.R.V.I.S. Alarm", userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 6. Timer
        if (clean.contains("timer")) {
            val sec = parseDurationSeconds(clean)
            val reply = executor.setTimer(sec, "J.A.R.V.I.S. Timer", userName)
            return CommandExecutionOutcome(rawText, reply)
        }

        // 7. Read Notifications / Messages
        if (Regex("\\b(notification|notifications|messages padho|message padho|naye message|kiska message|unread)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, executor.readNotifications(userName))
        }

        // 8. Volume & Media Controls
        if (Regex("\\b(volume|awaaz|sound|mute|unmute)\\b").containsMatchIn(clean)) {
            val action = when {
                clean.contains("unmute") -> "unmute"
                clean.contains("mute") || clean.contains("silent") -> "mute"
                clean.contains("kam") || clean.contains("down") || clean.contains("decrease") -> "down"
                else -> "up"
            }
            return CommandExecutionOutcome(rawText, executor.adjustVolume(action, userName))
        }
        if (Regex("\\b(pause music|stop music|gana roko|next song|agla gana|previous song)\\b").containsMatchIn(clean)) {
            val action = when {
                clean.contains("next") || clean.contains("agla") -> "next"
                clean.contains("previous") -> "previous"
                else -> "pause"
            }
            return CommandExecutionOutcome(rawText, executor.controlMedia(action, userName))
        }

        // 9. Play Music / Song
        val musicMatch = Regex("^(?:play|chalao|bajao|gana bajao|gana lagao|sunao)\\s+(.+)").find(clean)
        if (musicMatch != null && !clean.contains("youtube video")) {
            val song = musicMatch.groupValues[1].trim()
            return CommandExecutionOutcome(rawText, executor.playMusicQuery(song, userName))
        }

        // 10. Wi-Fi / Bluetooth Settings
        if (Regex("\\b(wifi|wi-fi)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, executor.openSettingsPanel("wifi", userName))
        }
        if (clean.contains("bluetooth")) {
            return CommandExecutionOutcome(rawText, executor.openSettingsPanel("bluetooth", userName))
        }

        // 11. Maps Navigation
        if (Regex("\\b(maps|navigation|navigate|rasta)\\b").containsMatchIn(clean)) {
            val dest = Regex("(?:navigate to|maps for|rasta dikhao|rasta batao)\\s+(.+)").find(clean)
                ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return CommandExecutionOutcome(rawText, executor.openMapsNavigation(dest, userName))
        }

        // 12. Battery / Time / Date / Weather
        if (Regex("\\b(battery|charge|charging)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, executor.getBatteryReport(userName))
        }
        if (Regex("\\b(time|samay|kitne baje)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, executor.getTimeReport(userName))
        }
        if (Regex("\\b(date|tareekh|aaj ka din)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, executor.getDateReport(userName))
        }
        if (Regex("\\b(weather|mausam|temperature)\\b").containsMatchIn(clean)) {
            return CommandExecutionOutcome(rawText, JarvisApiClient.fetchWeatherSummary(prefs.backendUrl, userName))
        }

        // 13. Tasks / Notes Sync with JARVIS Cloud
        val taskMatch = Regex("(?:add task|new task|task banao|remind me to|yaad dilana)\\s+(.+)").find(clean)
        if (taskMatch != null) {
            val title = taskMatch.groupValues[1].trim()
            JarvisApiClient.saveTaskToBackend(prefs.backendUrl, title)
            return CommandExecutionOutcome(rawText, "Task save kar diya hai: \"$title\", $userName.")
        }
        val noteMatch = Regex("(?:take note|note down|note likho|save note)\\s+(.+)").find(clean)
        if (noteMatch != null) {
            val note = noteMatch.groupValues[1].trim()
            JarvisApiClient.saveNoteToBackend(prefs.backendUrl, note)
            return CommandExecutionOutcome(rawText, "Note save kar liya hai, $userName.")
        }

        // 14. Open Any Installed App
        val openMatch = Regex("(?:open|launch|kholo|chalao)\\s+([a-zA-Z0-9\\s]+)").find(clean)
        if (openMatch != null) {
            val appTarget = openMatch.groupValues[1].trim()
            return CommandExecutionOutcome(rawText, executor.openApp(appTarget, userName))
        }

        // 15. Search Web
        val searchMatch = Regex("(?:google|search|dhoondho|pata karo)\\s+(?:for\\s+)?(.+)").find(clean)
        if (searchMatch != null) {
            return CommandExecutionOutcome(rawText, executor.searchWeb(searchMatch.groupValues[1], userName))
        }

        // 16. Fallback to J.A.R.V.I.S. AI Cloud Chat
        return try {
            val reply = JarvisApiClient.chatWithBackend(prefs.backendUrl, rawText, prefs.geminiApiKey)
            CommandExecutionOutcome(rawText, reply)
        } catch (_: Exception) {
            CommandExecutionOutcome(rawText, "Network error aa raha hai, $userName. Kripya internet check karein.")
        }
    }

    private suspend fun executeStructuredIntent(
        intent: String,
        target: String,
        value: String,
        fallbackReply: String,
        prefs: JarvisPrefs
    ): String {
        val userName = prefs.userName
        return when (intent.lowercase()) {
            "call" -> executor.placeCall(target, userName)
            "whatsapp" -> executor.sendWhatsApp(target, value, userName)
            "sms" -> executor.sendSms(target, value, userName)
            "torch" -> executor.setTorch(!value.equals("off", ignoreCase = true), userName)
            "alarm" -> {
                val parts = value.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                executor.setAlarm(h, m, target.ifBlank { "J.A.R.V.I.S. Alarm" }, userName)
            }
            "timer" -> {
                val sec = value.filter { it.isDigit() }.toIntOrNull() ?: 60
                executor.setTimer(sec, target.ifBlank { "J.A.R.V.I.S. Timer" }, userName)
            }
            "open_app" -> executor.openApp(target, userName)
            "play_music" -> executor.playMusicQuery(target, userName)
            "maps" -> executor.openMapsNavigation(target, userName)
            "volume" -> executor.adjustVolume(value.ifBlank { "up" }, userName)
            "media" -> executor.controlMedia(value.ifBlank { "pause" }, userName)
            "wifi" -> executor.openSettingsPanel("wifi", userName)
            "bluetooth" -> executor.openSettingsPanel("bluetooth", userName)
            "notifications" -> executor.readNotifications(userName)
            "battery" -> executor.getBatteryReport(userName)
            "time" -> executor.getTimeReport(userName)
            "date" -> executor.getDateReport(userName)
            "weather" -> JarvisApiClient.fetchWeatherSummary(prefs.backendUrl, userName)
            "search" -> executor.searchWeb(target, userName)
            "add_task" -> {
                JarvisApiClient.saveTaskToBackend(prefs.backendUrl, target)
                fallbackReply
            }
            "add_note" -> {
                JarvisApiClient.saveNoteToBackend(prefs.backendUrl, target)
                fallbackReply
            }
            else -> fallbackReply
        }
    }

    private fun extractCallTarget(clean: String): String {
        val koMatch = Regex("([a-zA-Z0-9+\\s]+?)\\s+ko\\s+(?:call|phone)").find(clean)
        if (koMatch != null) return koMatch.groupValues[1].trim()

        val afterMatch = Regex("(?:call|dial|phone lagao|phone karo)\\s+(.+)").find(clean)
        return afterMatch?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\b(ko|karo|lagao|please|on mobile)\\b"), "")
            ?.trim()
            .orEmpty()
    }

    private fun parseHourMinute(clean: String): Pair<Int, Int> {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|subah|shaam|raat)?").find(clean)
        var hour = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val suffix = match?.groupValues?.getOrNull(3).orEmpty()
        if ((suffix == "pm" || suffix == "shaam" || suffix == "raat") && hour in 1..11) {
            hour += 12
        }
        return Pair(hour, minute)
    }

    private fun parseDurationSeconds(clean: String): Int {
        val num = Regex("(\\d+)").find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 60
        return when {
            clean.contains("hour") || clean.contains("ghanta") -> num * 3600
            clean.contains("minute") || clean.contains("min") -> num * 60
            else -> num
        }
    }
}

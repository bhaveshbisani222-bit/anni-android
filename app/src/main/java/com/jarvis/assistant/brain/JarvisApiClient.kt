package com.jarvis.assistant.brain

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiVoiceResult(
    val transcript: String,
    val intent: String,
    val target: String,
    val value: String,
    val reply: String
)

/**
 * Network bridge to the JARVIS cloud backend (Vercel / Local) and Gemini Multimodal Audio API.
 */
object JarvisApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun chatWithBackend(
        backendUrl: String,
        prompt: String,
        geminiApiKey: String
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("history", JSONArray())
            if (geminiApiKey.isNotBlank()) put("apiKey", geminiApiKey)
        }

        val request = Request.Builder()
            .url("${backendUrl.trimEnd('/')}/api/ai/chat")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Backend returned HTTP ${response.code}")
            }
            val parsed = JSONObject(bodyStr)
            parsed.optString("reply", "Command processed.")
        }
    }

    suspend fun saveTaskToBackend(backendUrl: String, title: String) = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("title", title)
                put("priority", "high")
            }
            val request = Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/tasks")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }

    suspend fun saveNoteToBackend(backendUrl: String, content: String) = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("title", "Voice Note")
                put("content", content)
            }
            val request = Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/notes")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }

    suspend fun fetchWeatherSummary(backendUrl: String, userName: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/weather")
                .get()
                .build()
            client.newCall(request).execute().use { res ->
                val obj = JSONObject(res.body?.string().orEmpty())
                val city = obj.optString("city", "your area")
                val temp = obj.optInt("temperature", 28)
                val cond = obj.optString("condition", "Clear")
                "$userName, $city ka mausam abhi $cond hai aur temperature $temp degree Celsius hai."
            }
        } catch (_: Exception) {
            "Weather server se connect nahi ho paaya, $userName."
        }
    }

    /**
     * Sends verified owner WAV audio directly to Gemini 2.5 Flash for transcription + Siri intent extraction.
     */
    suspend fun processAudioWithGemini(
        wavBytes: ByteArray,
        geminiApiKey: String,
        userName: String,
        installedApps: List<String>
    ): GeminiVoiceResult? = withContext(Dispatchers.IO) {
        if (geminiApiKey.isBlank()) return@withContext null

        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val appsHint = installedApps.take(30).joinToString(", ")

        val instruction = """
You are Anni (A.N.N.I.), a Siri-like personal voice assistant on $userName's Android phone.
Listen to the audio command (Hindi, Hinglish, or English) and output ONLY valid JSON (no markdown) with these keys:
- "transcript": exact words spoken by user
- "intent": one of ["call","whatsapp","sms","alarm","timer","torch","open_app","youtube_play","play_music","maps","volume","media","wifi","bluetooth","notifications","battery","time","date","weather","search","add_task","add_note","chat"]
- "target": contact name, app name, YouTube video/show query, song query, destination, search query, or task/note text
- "value": extra parameter ("on"/"off" for torch, "up"/"down"/"mute"/"unmute" for volume, "play"/"pause"/"next"/"previous" for media, "HH:MM" 24-hour for alarm, seconds integer string for timer, or message text for whatsapp/sms)
- "reply": short, natural spoken response in Hinglish/English addressing $userName.

Rules:
- If the user says to open YouTube AND play/search a video or show (e.g., "open youtube play taarak mehta"), MUST use intent "youtube_play" and put only the video/show name in "target" (do NOT use "open_app").

Installed apps include: $appsHint
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", instruction))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", "audio/wav")
                        put("data", b64Audio)
                    }))
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey.trim()}"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini audio error HTTP ${response.code}")
            }
            val json = JSONObject(rawBody)
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            if (text.isBlank()) return@withContext null
            val parsed = JSONObject(text)
            GeminiVoiceResult(
                transcript = parsed.optString("transcript", ""),
                intent = parsed.optString("intent", "chat"),
                target = parsed.optString("target", ""),
                value = parsed.optString("value", ""),
                reply = parsed.optString("reply", "Done, $userName.")
            )
        }
    }
}

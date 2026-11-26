package com.jarvis.assistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persistent settings and enrolled owner voiceprint storage.
 */
class JarvisPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Sir") ?: "Sir"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value.ifBlank { "Sir" }).apply()

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim().trimEnd('/')).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value.trim()).apply()

    var voiceThreshold: Float
        get() = prefs.getFloat(KEY_VOICE_THRESHOLD, 0.42f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_THRESHOLD, value.coerceIn(0.15f, 0.85f)).apply()

    var voiceLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_LOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_LOCK_ENABLED, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var silentBackgroundMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_BG_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_BG_MODE, value).apply()

    fun getOwnerEmbeddings(): List<FloatArray> {
        val raw = prefs.getString(KEY_OWNER_EMBEDDINGS, null) ?: return emptyList()
        return try {
            val outer = JSONArray(raw)
            val result = ArrayList<FloatArray>(outer.length())
            for (i in 0 until outer.length()) {
                val inner = outer.getJSONArray(i)
                val vec = FloatArray(inner.length())
                for (j in 0 until inner.length()) {
                    vec[j] = inner.getDouble(j).toFloat()
                }
                result.add(vec)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOwnerEmbedding(embedding: FloatArray): Int {
        val existing = getOwnerEmbeddings().toMutableList()
        existing.add(embedding)
        saveOwnerEmbeddings(existing)
        return existing.size
    }

    fun clearOwnerEmbeddings() {
        prefs.edit().remove(KEY_OWNER_EMBEDDINGS).apply()
    }

    fun getEnrolledSampleCount(): Int = getOwnerEmbeddings().size

    private fun saveOwnerEmbeddings(list: List<FloatArray>) {
        val outer = JSONArray()
        for (vec in list) {
            val inner = JSONArray()
            for (v in vec) {
                inner.put(v.toDouble())
            }
            outer.put(inner)
        }
        prefs.edit().putString(KEY_OWNER_EMBEDDINGS, outer.toString()).apply()
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://jarvis-voice-ai-lemon.vercel.app"

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_VOICE_THRESHOLD = "voice_threshold"
        private const val KEY_VOICE_LOCK_ENABLED = "voice_lock_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_SILENT_BG_MODE = "silent_background_mode"
        private const val KEY_OWNER_EMBEDDINGS = "owner_embeddings"
    }
}

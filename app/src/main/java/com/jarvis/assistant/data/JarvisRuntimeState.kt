package com.jarvis.assistant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AssistantMode {
    STOPPED,
    PASSIVE_WAKE,
    LISTENING_COMMAND,
    VERIFYING_VOICE,
    THINKING,
    SPEAKING,
    DENIED
}

data class ConversationEntry(
    val speaker: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class RuntimeUiState(
    val mode: AssistantMode = AssistantMode.STOPPED,
    val statusText: String = "Tap Start Shield to enable Wake Word",
    val lastHeardText: String = "",
    val lastReplyText: String = "At your service, Sir.",
    val voiceStatusBadge: String = "Not Verified Yet",
    val enrolledSamples: Int = 0,
    val isServiceRunning: Boolean = false,
    val history: List<ConversationEntry> = emptyList()
)

/**
 * Shared live state observed by MainActivity, LockScreenActivity, and JarvisWakeService.
 */
object JarvisRuntimeState {
    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    fun updateMode(mode: AssistantMode, statusText: String) {
        _uiState.update { it.copy(mode = mode, statusText = statusText) }
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(isServiceRunning = running) }
    }

    fun setEnrolledCount(count: Int) {
        _uiState.update { it.copy(enrolledSamples = count) }
    }

    fun setVoiceBadge(badge: String) {
        _uiState.update { it.copy(voiceStatusBadge = badge) }
    }

    fun setLastHeard(text: String) {
        _uiState.update { it.copy(lastHeardText = text) }
    }

    fun appendInteraction(userText: String, jarvisReply: String) {
        _uiState.update { current ->
            val updated = (current.history + listOf(
                ConversationEntry("You", userText),
                ConversationEntry("A.N.N.I.", jarvisReply)
            )).takeLast(30)
            current.copy(
                lastHeardText = userText,
                lastReplyText = jarvisReply,
                history = updated
            )
        }
    }

    fun setReplyOnly(jarvisReply: String) {
        _uiState.update { current ->
            val updated = (current.history + ConversationEntry("A.N.N.I.", jarvisReply)).takeLast(30)
            current.copy(lastReplyText = jarvisReply, history = updated)
        }
    }
}

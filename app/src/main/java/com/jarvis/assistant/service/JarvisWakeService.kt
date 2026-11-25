package com.jarvis.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.LockScreenActivity
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.JarvisAudioEngine
import com.jarvis.assistant.audio.JarvisChimePlayer
import com.jarvis.assistant.audio.VoiceVerifyStatus
import com.jarvis.assistant.audio.WavEncoder
import com.jarvis.assistant.brain.CommandRouter
import com.jarvis.assistant.data.AssistantMode
import com.jarvis.assistant.data.JarvisPrefs
import com.jarvis.assistant.data.JarvisRuntimeState
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Persistent microphone foreground service supporting lock-screen wake-word ("Hey Jarvis")
 * and owner speaker verification ("sirf meri awaj pe").
 */
class JarvisWakeService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: JarvisPrefs
    private lateinit var commandRouter: CommandRouter

    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    @Volatile
    private var running = false

    @Volatile
    private var isSpeaking = false

    @Volatile
    private var manualTriggerRequested = false

    @Volatile
    private var enrollRequested = false

    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        prefs = JarvisPrefs(this)
        commandRouter = CommandRouter(this)
        tts = TextToSpeech(this, this)
        JarvisRuntimeState.setEnrolledCount(prefs.getEnrolledSampleCount())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!JarvisApp.hasMicPermission(this)) {
            JarvisRuntimeState.updateMode(AssistantMode.STOPPED, "Microphone permission required")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()

        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_LISTEN -> {
                manualTriggerRequested = true
            }
            ACTION_ENROLL_SAMPLE -> {
                enrollRequested = true
            }
        }

        if (!running) {
            running = true
            JarvisRuntimeState.setServiceRunning(true)
            startAudioLoop()
        }

        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = buildOngoingNotification("Listening for \"Hey Jarvis\"...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildOngoingNotification(subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val listenIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisWakeService::class.java).apply { action = ACTION_MANUAL_LISTEN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_WAKE_SERVICE)
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentTitle("J.A.R.V.I.S. Shield Active")
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic_notif, "Speak Now", listenIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateOngoingNotification(subtitle: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildOngoingNotification(subtitle))
    }

    private fun startAudioLoop() {
        workerThread = Thread {
            val initialized = JarvisAudioEngine.ensureInitialized(this)
            if (!initialized) {
                JarvisRuntimeState.updateMode(AssistantMode.STOPPED, "Failed to load AI audio models")
                running = false
                JarvisRuntimeState.setServiceRunning(false)
                return@Thread
            }

            var kwsStream: OnlineStream? = JarvisAudioEngine.createKwsStream()
            if (kwsStream == null) {
                running = false
                JarvisRuntimeState.setServiceRunning(false)
                return@Thread
            }

            val sampleRate = JarvisAudioEngine.SAMPLE_RATE
            val chunkSamples = 1600 // 100ms chunks
            val shortBuf = ShortArray(chunkSamples)

            // Rolling 2.2-second buffer to capture the wake phrase audio for fallback speaker check
            val ringCapacity = sampleRate * 22 / 10
            val ringBuffer = FloatArray(ringCapacity)
            var ringCursor = 0
            var ringFilled = 0

            if (!openAudioRecord()) {
                JarvisRuntimeState.updateMode(AssistantMode.STOPPED, "Mic busy or unavailable")
                running = false
                JarvisRuntimeState.setServiceRunning(false)
                kwsStream.release()
                return@Thread
            }

            JarvisRuntimeState.updateMode(
                AssistantMode.PASSIVE_WAKE,
                "Say \"Hey Jarvis\" anytime (Lock-Screen Ready)"
            )

            while (running) {
                try {
                    if (enrollRequested) {
                        enrollRequested = false
                        handleVoiceEnrollmentFlow()
                        kwsStream.release()
                        kwsStream = JarvisAudioEngine.createKwsStream()
                        continue
                    }

                    if (manualTriggerRequested) {
                        manualTriggerRequested = false
                        val wakeSnapshot = snapshotRing(ringBuffer, ringCursor, ringFilled)
                        handleActivatedSession(wakeSnapshot, verifyWakeSnapshotFirst = false)
                        kwsStream?.let { JarvisAudioEngine.resetKwsStream(it) }
                        continue
                    }

                    val rec = audioRecord ?: run {
                        if (!openAudioRecord()) {
                            Thread.sleep(500)
                            continue
                        }
                        audioRecord!!
                    }

                    val readCount = rec.read(shortBuf, 0, shortBuf.size)
                    if (readCount <= 0) {
                        Thread.sleep(30)
                        continue
                    }

                    if (isSpeaking) {
                        // Avoid triggering wake-word on JARVIS's own TTS speech
                        continue
                    }

                    val floatSamples = FloatArray(readCount) { shortBuf[it] / 32768.0f }

                    for (s in floatSamples) {
                        ringBuffer[ringCursor] = s
                        ringCursor = (ringCursor + 1) % ringCapacity
                        if (ringFilled < ringCapacity) ringFilled++
                    }

                    val detected = kwsStream?.let { JarvisAudioEngine.processKwsAudio(it, floatSamples) }
                    if (!detected.isNullOrBlank()) {
                        Log.i(TAG, "Wake-word detected: $detected")
                        val wakeSnapshot = snapshotRing(ringBuffer, ringCursor, ringFilled)
                        handleActivatedSession(wakeSnapshot, verifyWakeSnapshotFirst = true)
                        kwsStream?.let { JarvisAudioEngine.resetKwsStream(it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio loop error", e)
                    Thread.sleep(300)
                }
            }

            kwsStream?.release()
            closeAudioRecord()
        }.apply {
            name = "JarvisWakeAudioThread"
            start()
        }
    }

    private fun handleVoiceEnrollmentFlow() {
        JarvisRuntimeState.updateMode(
            AssistantMode.LISTENING_COMMAND,
            "Recording voiceprint (3.5s)... Speak clearly!"
        )
        JarvisChimePlayer.playWakeChime()
        Thread.sleep(250)

        val samples = recordFixedDuration(3500)
        if (samples.size < JarvisAudioEngine.SAMPLE_RATE) {
            JarvisRuntimeState.updateMode(AssistantMode.PASSIVE_WAKE, "Voice too short, try again")
            speakSync("Awaz clear record nahi hui. Kripya dubara bolein.")
            return
        }

        val count = JarvisAudioEngine.enrollOwnerSample(samples, prefs)
        if (count > 0) {
            JarvisRuntimeState.setEnrolledCount(count)
            JarvisRuntimeState.setVoiceBadge("Voiceprint Saved ($count samples)")
            JarvisChimePlayer.playSuccessChime()
            speakSync("Voice sample $count save ho gaya hai, ${prefs.userName}.")
        } else {
            speakSync("Voice sample save karne me error aaya.")
        }

        JarvisRuntimeState.updateMode(
            AssistantMode.PASSIVE_WAKE,
            "Say \"Hey Jarvis\" anytime (Lock-Screen Ready)"
        )
    }

    private fun handleActivatedSession(wakeSnapshot: FloatArray, verifyWakeSnapshotFirst: Boolean) {
        acquireTemporaryWakeLock()
        wakeScreenAndShowOverlay()
        JarvisChimePlayer.playWakeChime()
        updateOngoingNotification("Listening for your command...")

        val useGeminiAudio = prefs.geminiApiKey.isNotBlank()

        if (useGeminiAudio) {
            // Mode 1: Record command PCM -> Check Voiceprint -> Send WAV directly to Gemini Audio
            JarvisRuntimeState.updateMode(AssistantMode.LISTENING_COMMAND, "Listening...")
            Thread.sleep(260) // Let wake chime finish
            val commandSamples = captureCommandWithVad(maxMs = 7000)

            if (commandSamples.size < JarvisAudioEngine.SAMPLE_RATE / 2) {
                JarvisRuntimeState.updateMode(AssistantMode.PASSIVE_WAKE, "Nothing heard. Ready.")
                updateOngoingNotification("Listening for \"Hey Jarvis\"...")
                return
            }

            if (prefs.voiceLockEnabled && prefs.getEnrolledSampleCount() > 0) {
                JarvisRuntimeState.updateMode(AssistantMode.VERIFYING_VOICE, "Verifying your voice...")
                val verifyStatus = JarvisAudioEngine.verifyOwnerVoice(commandSamples, prefs)
                if (verifyStatus == VoiceVerifyStatus.REJECTED_OTHER) {
                    handleVoiceRejected()
                    return
                } else if (verifyStatus == VoiceVerifyStatus.VERIFIED_OWNER) {
                    JarvisRuntimeState.setVoiceBadge("Owner Verified ✓")
                }
            }

            JarvisRuntimeState.updateMode(AssistantMode.THINKING, "Processing command...")
            val wav = WavEncoder.encodeFloatSamplesToWav(commandSamples, JarvisAudioEngine.SAMPLE_RATE)

            val latch = CountDownLatch(1)
            var outcomeTranscript = ""
            var outcomeReply = ""

            serviceScope.launch {
                try {
                    val outcome = commandRouter.handleAudioWithGemini(wav, prefs)
                    if (outcome != null) {
                        outcomeTranscript = outcome.userTranscript
                        outcomeReply = outcome.replyText
                    } else {
                        outcomeReply = "Command samajh nahi aaya, ${prefs.userName}."
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini audio failed", e)
                    outcomeReply = "Internet ya Gemini API me dikkat aa rahi hai, ${prefs.userName}."
                } finally {
                    latch.countDown()
                }
            }
            latch.await(32, TimeUnit.SECONDS)

            if (outcomeTranscript.isNotBlank()) {
                JarvisRuntimeState.appendInteraction(outcomeTranscript, outcomeReply)
            } else {
                JarvisRuntimeState.setReplyOnly(outcomeReply)
            }
            speakSync(outcomeReply)
        } else {
            // Mode 2 (Zero Gemini Key): Check Voiceprint on wake audio (if enrolled & >=1.1s),
            // pause AudioRecord, run Android SpeechRecognizer, route to Siri executor / Vercel AI!
            if (verifyWakeSnapshotFirst && prefs.voiceLockEnabled && prefs.getEnrolledSampleCount() > 0) {
                if (wakeSnapshot.size >= JarvisAudioEngine.SAMPLE_RATE) {
                    val wakeCheck = JarvisAudioEngine.verifyOwnerVoice(wakeSnapshot, prefs)
                    if (wakeCheck == VoiceVerifyStatus.REJECTED_OTHER) {
                        handleVoiceRejected()
                        return
                    } else if (wakeCheck == VoiceVerifyStatus.VERIFIED_OWNER) {
                        JarvisRuntimeState.setVoiceBadge("Owner Verified ✓")
                    }
                }
            }

            // Release AudioRecord briefly so Android SpeechRecognizer can access the mic cleanly
            closeAudioRecord()
            Thread.sleep(220)

            JarvisRuntimeState.updateMode(AssistantMode.LISTENING_COMMAND, "Listening (Hindi/English)...")
            val recognizedText = listenWithAndroidSttBlocking()

            // Re-open AudioRecord immediately after STT completes
            openAudioRecord()

            if (recognizedText.isNullOrBlank()) {
                JarvisRuntimeState.updateMode(AssistantMode.PASSIVE_WAKE, "Didn't catch that. Ready.")
                updateOngoingNotification("Listening for \"Hey Jarvis\"...")
                return
            }

            JarvisRuntimeState.setLastHeard(recognizedText)
            JarvisRuntimeState.updateMode(AssistantMode.THINKING, "Executing...")

            val latch = CountDownLatch(1)
            var finalReply = ""
            serviceScope.launch {
                try {
                    val outcome = commandRouter.handleTextCommand(recognizedText, prefs)
                    finalReply = outcome.replyText
                } catch (e: Exception) {
                    finalReply = "Command process nahi ho paaya, ${prefs.userName}."
                } finally {
                    latch.countDown()
                }
            }
            latch.await(25, TimeUnit.SECONDS)

            JarvisRuntimeState.appendInteraction(recognizedText, finalReply)
            speakSync(finalReply)
        }

        JarvisRuntimeState.updateMode(
            AssistantMode.PASSIVE_WAKE,
            "Say \"Hey Jarvis\" anytime (Lock-Screen Ready)"
        )
        updateOngoingNotification("Listening for \"Hey Jarvis\"...")
    }

    private fun handleVoiceRejected() {
        JarvisChimePlayer.playDeniedChime()
        JarvisRuntimeState.setVoiceBadge("Unauthorized Voice Blocked ✗")
        val msg = "Awaz match nahi hui. Sirf owner ki awaz par hi kaam karta hu."
        JarvisRuntimeState.updateMode(AssistantMode.DENIED, msg)
        JarvisRuntimeState.setReplyOnly(msg)
        speakSync(msg)
        JarvisRuntimeState.updateMode(
            AssistantMode.PASSIVE_WAKE,
            "Say \"Hey Jarvis\" anytime (Lock-Screen Ready)"
        )
        updateOngoingNotification("Listening for \"Hey Jarvis\"...")
    }

    private fun listenWithAndroidSttBlocking(): String? {
        val latch = CountDownLatch(1)
        var resultText: String? = null
        var activeRecognizer: SpeechRecognizer? = null

        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    latch.countDown()
                    return@post
                }
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                activeRecognizer = recognizer
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            JarvisRuntimeState.setLastHeard(partial)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        resultText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        recognizer.destroy()
                        activeRecognizer = null
                        latch.countDown()
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "Android SpeechRecognizer error code: $error")
                        recognizer.destroy()
                        activeRecognizer = null
                        latch.countDown()
                    }
                })
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SpeechRecognizer", e)
                latch.countDown()
            }
        }

        val completed = latch.await(11, TimeUnit.SECONDS)
        if (!completed) {
            mainHandler.post {
                try {
                    activeRecognizer?.destroy()
                } catch (_: Exception) {
                }
            }
        }
        return resultText
    }

    private fun captureCommandWithVad(maxMs: Int): FloatArray {
        val sampleRate = JarvisAudioEngine.SAMPLE_RATE
        val chunk = ShortArray(1600) // 100ms
        val collected = ArrayList<Float>(sampleRate * 5)

        val maxChunks = maxMs / 100
        var speechStarted = false
        var silentChunksAfterSpeech = 0

        for (i in 0 until maxChunks) {
            if (!running) break
            val n = audioRecord?.read(chunk, 0, chunk.size) ?: break
            if (n <= 0) continue

            var sumSq = 0.0
            for (j in 0 until n) {
                val f = chunk[j] / 32768.0f
                collected.add(f)
                sumSq += f * f
            }
            val rms = sqrt(sumSq / n)

            if (rms > 0.022) {
                speechStarted = true
                silentChunksAfterSpeech = 0
            } else if (speechStarted) {
                silentChunksAfterSpeech++
                // ~1.1 seconds of silence after speaking ends capture
                if (silentChunksAfterSpeech >= 11) break
            } else if (i >= 35) {
                // 3.5 seconds with no speech at all
                break
            }
        }
        return collected.toFloatArray()
    }

    private fun recordFixedDuration(durationMs: Int): FloatArray {
        val sampleRate = JarvisAudioEngine.SAMPLE_RATE
        val targetSamples = (sampleRate * durationMs) / 1000
        val out = FloatArray(targetSamples)
        val chunk = ShortArray(1600)
        var offset = 0
        while (offset < targetSamples && running) {
            val n = audioRecord?.read(chunk, 0, minOf(chunk.size, targetSamples - offset)) ?: break
            if (n <= 0) continue
            for (i in 0 until n) {
                out[offset + i] = chunk[i] / 32768.0f
            }
            offset += n
        }
        return if (offset == targetSamples) out else out.copyOf(offset)
    }

    private fun snapshotRing(ring: FloatArray, cursor: Int, filled: Int): FloatArray {
        if (filled <= 0) return FloatArray(0)
        val out = FloatArray(filled)
        val start = if (filled < ring.size) 0 else cursor
        for (i in 0 until filled) {
            out[i] = ring[(start + i) % ring.size]
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): Boolean {
        closeAudioRecord()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val sampleRate = JarvisAudioEngine.SAMPLE_RATE
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (src in sources) {
            try {
                val rec = AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes * 2, sampleRate)
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    audioRecord = rec
                    return true
                }
                rec.release()
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun closeAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun acquireTemporaryWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:CommandWakeLock")
            wl.acquire(15_000L)
        } catch (_: Exception) {
        }
    }

    private fun wakeScreenAndShowOverlay() {
        val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            try {
                startActivity(lockIntent)
            } catch (_: Exception) {
            }
        }

        // Full-screen intent fallback to reliably wake locked screens across OEMs
        val fullScreenPending = PendingIntent.getActivity(
            this,
            99,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotif = NotificationCompat.Builder(this, JarvisApp.CHANNEL_LOCK_OVERLAY)
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentTitle("J.A.R.V.I.S. Listening")
            .setContentText("Speak your command...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(2500)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(LOCK_NOTIF_ID, alertNotif)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val indianEnglish = Locale("en", "IN")
            val res = tts?.setLanguage(indianEnglish)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            tts?.setSpeechRate(1.03f)
            ttsReady = true
        }
    }

    private fun speakSync(text: String) {
        if (!ttsReady || tts == null || text.isBlank()) return
        val clean = text.replace(Regex("[*_#`~\\[\\]()]"), " ").trim()
        JarvisRuntimeState.updateMode(AssistantMode.SPEAKING, "J.A.R.V.I.S. Speaking...")
        isSpeaking = true

        val latch = CountDownLatch(1)
        val uttId = "jarvis_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == uttId) latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == uttId) latch.countDown()
            }
        })

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, uttId)
        latch.await(20, TimeUnit.SECONDS)
        isSpeaking = false
    }

    private fun stopAll() {
        running = false
        JarvisRuntimeState.setServiceRunning(false)
        JarvisRuntimeState.updateMode(AssistantMode.STOPPED, "J.A.R.V.I.S. paused")
        closeAudioRecord()
    }

    override fun onDestroy() {
        stopAll()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisWakeService"
        private const val NOTIFICATION_ID = 1001
        private const val LOCK_NOTIF_ID = 1002

        const val ACTION_START = "com.jarvis.assistant.ACTION_START"
        const val ACTION_STOP = "com.jarvis.assistant.ACTION_STOP"
        const val ACTION_MANUAL_LISTEN = "com.jarvis.assistant.ACTION_MANUAL_LISTEN"
        const val ACTION_ENROLL_SAMPLE = "com.jarvis.assistant.ACTION_ENROLL_SAMPLE"

        fun startService(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, JarvisWakeService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

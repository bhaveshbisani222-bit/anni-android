package com.jarvis.assistant.audio

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.JarvisPrefs
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import com.k2fsa.sherpa.onnx.getFeatureConfig

enum class VoiceVerifyStatus {
    VERIFIED_OWNER,
    REJECTED_OTHER,
    NO_ENROLLED_OWNER,
    AUDIO_TOO_SHORT
}

/**
 * Manages offline Sherpa-ONNX Keyword Spotting ("Hey Jarvis") and Speaker Verification (Owner Voiceprint).
 */
object JarvisAudioEngine {

    private const val TAG = "JarvisAudioEngine"
    private const val KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    private const val SPEAKER_MODEL_FILE = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    private const val OWNER_NAME = "owner"
    const val SAMPLE_RATE = 16000

    @Volatile
    private var keywordSpotter: KeywordSpotter? = null

    @Volatile
    private var speakerExtractor: SpeakerEmbeddingExtractor? = null

    @Volatile
    private var speakerManager: SpeakerEmbeddingManager? = null

    private val lock = Any()

    fun ensureInitialized(context: Context): Boolean {
        synchronized(lock) {
            val appCtx = context.applicationContext
            try {
                if (keywordSpotter == null) {
                    val kwsConfig = KeywordSpotterConfig(
                        featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "$KWS_MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                                decoder = "$KWS_MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                                joiner = "$KWS_MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
                            ),
                            tokens = "$KWS_MODEL_DIR/tokens.txt",
                            numThreads = 2,
                            modelType = "zipformer2"
                        ),
                        keywordsFile = "$KWS_MODEL_DIR/keywords.txt",
                        keywordsScore = 2.4f,
                        keywordsThreshold = 0.07f,
                        numTrailingBlanks = 1
                    )
                    keywordSpotter = KeywordSpotter(assetManager = appCtx.assets, config = kwsConfig)
                }

                if (speakerExtractor == null) {
                    val spkConfig = SpeakerEmbeddingExtractorConfig(
                        model = SPEAKER_MODEL_FILE,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    speakerExtractor = SpeakerEmbeddingExtractor(
                        assetManager = appCtx.assets,
                        config = spkConfig
                    )
                    speakerManager = SpeakerEmbeddingManager(dim = speakerExtractor!!.dim())
                }

                reloadVoiceprints(JarvisPrefs(appCtx))
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Sherpa-ONNX models", e)
                return false
            }
        }
    }

    fun reloadVoiceprints(prefs: JarvisPrefs) {
        synchronized(lock) {
            val mgr = speakerManager ?: return
            mgr.remove(OWNER_NAME)
            val saved = prefs.getOwnerEmbeddings()
            if (saved.isNotEmpty()) {
                mgr.add(OWNER_NAME, saved.toTypedArray())
            }
        }
    }

    fun createKwsStream(): OnlineStream? {
        val kws = keywordSpotter ?: return null
        return kws.createStream()
    }

    fun processKwsAudio(stream: OnlineStream, samples: FloatArray): String? {
        val kws = keywordSpotter ?: return null
        stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE)
        while (kws.isReady(stream)) {
            kws.decode(stream)
            val kw = kws.getResult(stream).keyword
            if (kw.isNotBlank()) {
                kws.reset(stream)
                return kw
            }
        }
        return null
    }

    fun resetKwsStream(stream: OnlineStream) {
        keywordSpotter?.reset(stream)
    }

    fun computeSpeakerEmbedding(samples: FloatArray): FloatArray? {
        val ext = speakerExtractor ?: return null
        val stream = ext.createStream()
        return try {
            stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE)
            stream.inputFinished()
            if (!ext.isReady(stream)) {
                null
            } else {
                ext.compute(stream)
            }
        } finally {
            stream.release()
        }
    }

    fun enrollOwnerSample(samples: FloatArray, prefs: JarvisPrefs): Int {
        val embedding = computeSpeakerEmbedding(samples) ?: return -1
        val count = prefs.addOwnerEmbedding(embedding)
        reloadVoiceprints(prefs)
        return count
    }

    fun verifyOwnerVoice(samples: FloatArray, prefs: JarvisPrefs): VoiceVerifyStatus {
        val mgr = speakerManager
        if (mgr == null || !mgr.contains(OWNER_NAME)) {
            return VoiceVerifyStatus.NO_ENROLLED_OWNER
        }
        if (samples.size < SAMPLE_RATE / 2) {
            return VoiceVerifyStatus.AUDIO_TOO_SHORT
        }
        val embedding = computeSpeakerEmbedding(samples) ?: return VoiceVerifyStatus.AUDIO_TOO_SHORT
        val matched = mgr.verify(
            name = OWNER_NAME,
            embedding = embedding,
            threshold = prefs.voiceThreshold
        )
        return if (matched) VoiceVerifyStatus.VERIFIED_OWNER else VoiceVerifyStatus.REJECTED_OTHER
    }
}

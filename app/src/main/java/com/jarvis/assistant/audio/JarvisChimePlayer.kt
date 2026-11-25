package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates Siri/JARVIS-style soft harmonic chimes using AudioTrack with zero asset files.
 */
object JarvisChimePlayer {

    fun playWakeChime() = playTwoTone(587.33, 880.0, 0.14)

    fun playSuccessChime() = playTwoTone(783.99, 1046.50, 0.12)

    fun playDeniedChime() = playTwoTone(349.23, 246.94, 0.18)

    private fun playTwoTone(freq1: Double, freq2: Double, toneSeconds: Double) {
        Thread {
            try {
                val sampleRate = 22050
                val samplesPerTone = (sampleRate * toneSeconds).toInt()
                val totalSamples = samplesPerTone * 2
                val pcm = ShortArray(totalSamples)

                for (i in 0 until totalSamples) {
                    val isFirst = i < samplesPerTone
                    val localIdx = if (isFirst) i else i - samplesPerTone
                    val t = localIdx.toDouble() / sampleRate
                    val freq = if (isFirst) freq1 else freq2
                    val envelope = sin(PI * (localIdx.toDouble() / samplesPerTone)) * exp(-t * 3.0)
                    val sample = sin(2.0 * PI * freq * t) * envelope * 0.35
                    pcm[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((toneSeconds * 2000 + 80).toLong())
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}

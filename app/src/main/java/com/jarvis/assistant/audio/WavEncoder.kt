package com.jarvis.assistant.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes 16-bit mono PCM float audio samples [-1f, 1f] into standard RIFF/WAV bytes.
 */
object WavEncoder {

    fun encodeFloatSamplesToWav(samples: FloatArray, sampleRate: Int = 16000): ByteArray {
        val pcmBytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = (samples[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            pcmBytes[i * 2] = (clamped.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()
        }

        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(1.toShort()) // NumChannels = 1 (Mono)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2.toShort()) // BlockAlign
            putShort(16.toShort()) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.size)
        }.array()

        return ByteArrayOutputStream(44 + pcmBytes.size).apply {
            write(header)
            write(pcmBytes)
        }.toByteArray()
    }
}

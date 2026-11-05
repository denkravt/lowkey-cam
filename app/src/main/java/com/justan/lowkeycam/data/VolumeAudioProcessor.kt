package com.justan.lowkeycam.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Scales PCM by [gain] — used to keep background music under the voice.
 * Handles both 16-bit and float PCM: decoders pick either, and declining the format would
 * silently drop the processor (which is what made the slider feel dead).
 */
@OptIn(markerClass = [UnstableApi::class])
class VolumeAudioProcessor(private val gain: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val samples = remaining / 4
                for (i in 0 until samples) {
                    output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
                }
                while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
            }
            else -> {
                val samples = remaining / 2
                for (i in 0 until samples) {
                    val scaled = (inputBuffer.short * gain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.putShort(scaled.toShort())
                }
                while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
            }
        }
        output.flip()
    }
}

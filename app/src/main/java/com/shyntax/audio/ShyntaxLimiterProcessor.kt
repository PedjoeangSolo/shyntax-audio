package com.shyntax.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Linked-channel PCM16 peak limiter. Enabled through shyntax_dsp/limiter_enabled.
 * Fast gain reduction above -1 dBFS with a smooth release to avoid abrupt pumping.
 * Encoded passthrough and non-PCM16 audio are left untouched.
 */
@UnstableApi
class ShyntaxLimiterProcessor(context: Context) : BaseAudioProcessor() {
    private val preferences = context.applicationContext.getSharedPreferences("shyntax_dsp", Context.MODE_PRIVATE)
    private var limiterGain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Stay out of the audio chain entirely while the limiter is switched off.
        if (!preferences.getBoolean("limiter_enabled", false)) return AudioProcessor.AudioFormat.NOT_SET
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!preferences.getBoolean("limiter_enabled", false)) {
            limiterGain = 1f
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val samples = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val sampleCount = samples.remaining()
        val output = replaceOutputBuffer(sampleCount * 2).order(ByteOrder.nativeOrder())
        val threshold = 0.8912509f // -1 dBFS
        val release = exp(-1.0 / (sampleRate * 0.050)).toFloat()
        var index = 0
        while (index < sampleCount) {
            var framePeak = 0f
            val frameEnd = min(sampleCount, index + channels)
            for (i in index until frameEnd) framePeak = max(framePeak, kotlin.math.abs(samples.get(i).toFloat() / 32768f))
            val targetGain = if (framePeak > threshold) threshold / framePeak else 1f
            limiterGain = if (targetGain < limiterGain) targetGain else 1f - (1f - limiterGain) * release
            for (i in index until frameEnd) {
                val value = (samples.get(i).toFloat() * limiterGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(value.toShort())
            }
            index = frameEnd
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush() { limiterGain = 1f }
    override fun onReset() { limiterGain = 1f }
}

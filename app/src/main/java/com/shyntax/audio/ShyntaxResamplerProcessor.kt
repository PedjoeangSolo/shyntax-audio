package com.shyntax.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.log10
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

object AudioAnalysisState {
    var peakDb by mutableFloatStateOf(-120f)
        private set
    var rmsDb by mutableFloatStateOf(-120f)
        private set
    var clippingSamples by mutableLongStateOf(0L)
        private set

    fun update(peak: Float, rms: Float, clipped: Long) {
        peakDb = if (peak > 0f) (20f * log10(peak)).coerceAtLeast(-120f) else -120f
        rmsDb = if (rms > 0f) (20f * log10(rms)).coerceAtLeast(-120f) else -120f
        clippingSamples += clipped
    }

    fun reset() {
        peakDb = -120f
        rmsDb = -120f
        clippingSamples = 0L
    }
}

/**
 * PCM16 sample-rate converter for Media3's AudioProcessor chain.
 * Encoded passthrough and unsupported PCM encodings are deliberately left untouched.
 */
@UnstableApi
class ShyntaxResamplerProcessor(context: Context) : BaseAudioProcessor() {
    private val preferences = context.getSharedPreferences("shyntax_output", Context.MODE_PRIVATE)
    private var selectedRate = 0
    private var quality = "Seimbang"

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val mode = preferences.getString("resampler_mode", "Nonaktif") ?: "Nonaktif"
        selectedRate = preferences.getInt("resampler_rate", 48000)
        quality = preferences.getString("resampler_quality", "Seimbang") ?: "Seimbang"
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        if (mode == "Manual" && selectedRate > 0 && selectedRate != inputAudioFormat.sampleRate) {
            return AudioProcessor.AudioFormat(selectedRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val inputRate = inputAudioFormat.sampleRate
        val outputRate = outputAudioFormat.sampleRate
        val frameBytes = channels * 2
        val frameCount = inputBuffer.remaining() / frameBytes
        if (frameCount <= 0 || inputRate <= 0 || outputRate <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val analysisSamples = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        var peak = 0f
        var sumSquares = 0.0
        var clipped = 0L
        val sampleCount = analysisSamples.remaining()
        for (index in 0 until sampleCount) {
            val normalized = analysisSamples.get(index).toFloat() / 32768f
            val magnitude = kotlin.math.abs(normalized)
            if (magnitude > peak) peak = magnitude
            sumSquares += normalized * normalized
            if (magnitude >= 0.999f) clipped++
        }
        AudioAnalysisState.update(peak, kotlin.math.sqrt(sumSquares / sampleCount).toFloat(), clipped)

        if (outputRate == inputRate || frameCount < 2) {
            val copy = replaceOutputBuffer(inputBuffer.remaining())
            copy.put(inputBuffer)
            copy.flip()
            return
        }

        val outputFrames = (((frameCount - 1).toLong() * outputRate) / inputRate).toInt() + 1
        val output = replaceOutputBuffer(outputFrames * frameBytes).order(ByteOrder.nativeOrder())
        val samples = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val lastFrame = frameCount - 1

        for (outFrame in 0 until outputFrames) {
            val sourcePosition = outFrame.toDouble() * inputRate / outputRate
            val base = floor(sourcePosition).toInt().coerceIn(0, lastFrame)
            val fraction = (sourcePosition - base).toFloat()
            val previous = max(0, base - 1)
            val next = min(lastFrame, base + 1)
            val nextNext = min(lastFrame, base + 2)
            for (channel in 0 until channels) {
                fun sample(frame: Int): Float = samples.get(frame * channels + channel).toFloat()
                val a = sample(previous)
                val b = sample(base)
                val c = sample(next)
                val d = sample(nextNext)
                val value = when (quality) {
                    "Cepat" -> b
                    "Tinggi" -> {
                        val t = fraction
                        0.5f * ((2f * b) + (-a + c) * t +
                            (2f * a - 5f * b + 4f * c - d) * t * t +
                            (-a + 3f * b - 3f * c + d) * t * t * t)
                    }
                    else -> b + (c - b) * fraction
                }
                output.putShort(value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}

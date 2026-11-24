package com.shyntax.audio

import android.media.audiofx.Equalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Uses Android's real playback-session Equalizer effect.
 * UI curves are interpolated onto the frequency bands exposed by the device.
 */
class NativeEqEngine(audioSessionId: Int) {
    private val effect = Equalizer(0, audioSessionId)
    private val range = effect.bandLevelRange
    private val minDb = range[0].toFloat() / 100f
    private var preampDb = 0f
    private var trebleDb = 0
    private val maxDb = range[1].toFloat() / 100f
    private val ten = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val thirtyOne = floatArrayOf(20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,20000f)
    private var lastFrequencies = thirtyOne
    private var lastValues: List<Float> = List(31) { 0f }

    fun setPreamp(gainDb: Float) { preampDb = gainDb.coerceIn(-12f, 12f); applyCurve(lastFrequencies, lastValues) }

    fun setEnabled(enabled: Boolean) {
        runCatching { effect.enabled = enabled }
    }

    fun applyGraphic(values: List<Float>, bandCount: Int) {
        val frequencies = if (bandCount == 31) thirtyOne else ten
        val source = if (bandCount == 31) values.take(31) else {
            List(10) { i -> values[(i * 30 / 9).coerceIn(0, 30)] }
        }
        applyCurve(frequencies, source)
    }

    fun applyParametric(frequencies: List<Float>, gains: List<Float>, qs: List<Float>) {
        val points = thirtyOne.map { hz ->
            frequencies.indices.sumOf { index ->
                val center = frequencies[index].coerceIn(20f, 20000f)
                val q = qs.getOrElse(index) { 1f }.coerceIn(0.3f, 4f)
                val gain = gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
                val sigma = ln(2.0) / (2.0 * q)
                val distance = ln(hz.toDouble() / center.toDouble()) / sigma
                (gain * exp(-0.5 * distance * distance)).toDouble()
            }.toFloat().coerceIn(-12f, 12f)
        }
        applyCurve(thirtyOne, points)
    }

    private fun applyCurve(frequencies: FloatArray, values: List<Float>) {
        lastFrequencies = frequencies
        lastValues = values
        val count = effect.numberOfBands.toInt()
        for (band in 0 until count) {
            val centerHz = effect.getCenterFreq(band.toShort()).toFloat() / 1000f
            val trebleOffset = if (centerHz >= 2000f) trebleDb.toFloat() * ((ln(centerHz.toDouble() / 2000.0) / ln(10.0)).coerceIn(0.0, 1.0)).toFloat() else 0f
            val db = (interpolateLog(frequencies, values, centerHz) + preampDb + trebleOffset).coerceIn(minDb, maxDb)
            val millibels = (db * 100f).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
            runCatching { effect.setBandLevel(band.toShort(), millibels.toShort()) }
        }
    }

    private fun interpolateLog(freqs: FloatArray, values: List<Float>, target: Float): Float {
        if (target <= freqs.first()) return values.firstOrNull() ?: 0f
        if (target >= freqs.last()) return values.lastOrNull() ?: 0f
        for (i in 0 until freqs.size - 1) {
            if (target in freqs[i]..freqs[i + 1]) {
                val t = (ln(target) - ln(freqs[i])) / (ln(freqs[i + 1]) - ln(freqs[i]))
                return values[i] + (values[i + 1] - values[i]) * t
            }
        }
        return 0f
    }

    fun applyTreble(gainDb: Int) {
        trebleDb = gainDb.coerceIn(-12, 12)
        applyCurve(lastFrequencies, lastValues)
    }

    fun release() {
        runCatching { effect.release() }
    }
}

package com.shyntax.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer

/** Knob values (0..1) for the reverb panel, mirroring the on-screen controls. */
data class ReverbSettings(
    val enabled: Boolean = false,
    val damp: Float = 0f,
    val filter: Float = 0f,
    val decay: Float = 0f,
    val preDelay: Float = 0f,
    val preDelayMix: Float = 0f,
    val size: Float = 0f,
    val mix: Float = 0f
)

/**
 * Optional Android playback-session effects. Unsupported effects fail closed.
 * Availability and audible behavior depend on the device audio implementation.
 */
class NativeDspEngine(sessionId: Int) {
    private val bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
    private val virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
    private val loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    private val presetReverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()
    private val envReverb = runCatching { EnvironmentalReverb(0, sessionId) }.getOrNull()

    fun apply(bassPercent: Int, widthPercent: Int, loudnessMb: Int) {
        runCatching {
            bass?.let { if (it.strengthSupported) { it.setStrength((bassPercent.coerceIn(0,100) * 10).toShort()); it.enabled = bassPercent > 0 } }
        }
        runCatching {
            virtualizer?.let { if (it.strengthSupported) { it.setStrength((widthPercent.coerceIn(0,100) * 10).toShort()); it.enabled = widthPercent > 0 } }
        }
        runCatching {
            loudness?.let { it.setTargetGain(loudnessMb.coerceIn(0,1000)); it.enabled = loudnessMb > 0 }
        }
    }

    /**
     * Full parametric reverb through EnvironmentalReverb: the panel knobs map onto
     * decay time / HF ratio (Damp), room HF level (Filter), decay (Pudar), reverb and
     * reflections delay (Pra-Delay), reflections level (Mix Pra-Delay), diffusion and
     * density (Ukuran), and reverb level (Mix). Devices without EnvironmentalReverb
     * fall back to the nearest PresetReverb room size.
     */
    fun applyReverb(s: ReverbSettings) {
        val env = envReverb
        if (env != null) {
            runCatching {
                if (!s.enabled) {
                    env.enabled = false
                    return@runCatching
                }
                env.setRoomLevel(0.toShort())
                env.setRoomHFLevel((-9000f * s.filter).toInt().toShort())
                env.setDecayTime((100f + s.decay * 19900f).toInt())
                env.setDecayHFRatio((2000f - s.damp * 1900f).toInt().toShort())
                env.setReflectionsLevel((-9000f + s.preDelayMix * 9000f).toInt().toShort())
                env.setReflectionsDelay((s.preDelay * 300f).toInt())
                env.setReverbLevel((-6000f + s.mix * 6000f).toInt().toShort())
                env.setReverbDelay((s.preDelay * 300f).toInt())
                env.setDiffusion((100f + s.size * 900f).toInt().toShort())
                env.setDensity((100f + s.size * 900f).toInt().toShort())
                env.enabled = true
            }
            return
        }
        runCatching {
            presetReverb?.let { effect ->
                if (!s.enabled) {
                    effect.enabled = false
                    return@runCatching
                }
                effect.preset = when {
                    s.size < 0.34f -> PresetReverb.PRESET_SMALLROOM
                    s.size < 0.67f -> PresetReverb.PRESET_MEDIUMHALL
                    else -> PresetReverb.PRESET_LARGEHALL
                }
                effect.enabled = true
            }
        }
    }

    fun release() {
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        runCatching { presetReverb?.release() }
        runCatching { envReverb?.release() }
    }
}

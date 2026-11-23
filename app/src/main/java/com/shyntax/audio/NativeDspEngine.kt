package com.shyntax.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer

/**
 * Optional Android playback-session effects. Unsupported effects fail closed.
 * Availability and audible behavior depend on the device audio implementation.
 */
class NativeDspEngine(sessionId: Int) {
    private val bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
    private val virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
    private val loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    private val reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()

    fun apply(bassPercent: Int, widthPercent: Int, loudnessMb: Int, reverbName: String) {
        runCatching {
            bass?.let { if (it.strengthSupported) { it.setStrength((bassPercent.coerceIn(0,100) * 10).toShort()); it.enabled = bassPercent > 0 } }
        }
        runCatching {
            virtualizer?.let { if (it.strengthSupported) { it.setStrength((widthPercent.coerceIn(0,100) * 10).toShort()); it.enabled = widthPercent > 0 } }
        }
        runCatching {
            loudness?.let { it.setTargetGain(loudnessMb.coerceIn(0,1000)); it.enabled = loudnessMb > 0 }
        }
        runCatching {
            reverb?.let { effect ->
                val preset = when (reverbName) {
                    "Room" -> PresetReverb.PRESET_SMALLROOM
                    "Hall" -> PresetReverb.PRESET_MEDIUMHALL
                    "Plate" -> PresetReverb.PRESET_PLATE
                    else -> null
                }
                if (preset == null) effect.enabled = false
                else { effect.preset = preset; effect.enabled = true }
            }
        }
    }

    fun release() {
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        runCatching { reverb?.release() }
    }
}

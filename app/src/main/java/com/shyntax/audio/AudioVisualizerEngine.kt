package com.shyntax.audio

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Captures reusable primitive audio frames and publishes only at the selected FPS.
 * The callback arrays are immutable snapshots owned by the receiver.
 */
class AudioVisualizerEngine(
    sessionId: Int,
    frameRate: Int,
    private val onFrame: (FloatArray, FloatArray) -> Unit
) {
    @Volatile private var targetFrameRate = frameRate.coerceIn(15, 60)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameAt = 0L
    private var lastWaveAt = 0L
    private var latestWave = FloatArray(0)
    private val visualizer: Visualizer

    init {
        visualizer = Visualizer(sessionId).apply {
            val range = Visualizer.getCaptureSizeRange()
            captureSize = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {
                    val now = SystemClock.elapsedRealtime()
                    val interval = 1000L / targetFrameRate
                    if (now - lastWaveAt < interval) return
                    lastWaveAt = now
                    val wave = FloatArray(data.size)
                    for (i in data.indices) {
                        wave[i] = ((data[i].toInt() and 0xff) - 128) / 128f
                    }
                    latestWave = wave
                }

                override fun onFftDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {
                    publishFft(data)
                }
            }, Visualizer.getMaxCaptureRate().coerceAtMost(20_000), true, true)
            enabled = false
        }
    }

    fun start() { visualizer.enabled = true }

    fun setFrameRate(value: Int) {
        targetFrameRate = value.coerceIn(15, 60)
    }

    private fun publishFft(bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val interval = 1000L / targetFrameRate
        if (now - lastFrameAt < interval) return
        lastFrameAt = now

        val bins = FloatArray((bytes.size - 2).coerceAtLeast(0) / 2)
        var source = 2
        var destination = 0
        while (source + 1 < bytes.size) {
            val real = bytes[source].toInt()
            val imaginary = bytes[source + 1].toInt()
            val magnitude = sqrt((real * real + imaginary * imaginary).toFloat()) / 180f
            bins[destination++] = magnitude.coerceIn(0f, 1f)
            source += 2
        }
        val waveSnapshot = latestWave
        mainHandler.post { onFrame(waveSnapshot, bins) }
    }

    fun stop() {
        runCatching {
            visualizer.enabled = false
            visualizer.setDataCaptureListener(null, 0, false, false)
            visualizer.release()
        }
    }
}

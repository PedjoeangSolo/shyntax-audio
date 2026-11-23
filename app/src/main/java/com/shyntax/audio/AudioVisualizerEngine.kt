package com.shyntax.audio

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Reads the active playback session's waveform and FFT. Android may restrict
 * this effect by device/ROM; callers must handle construction failures.
 */
class AudioVisualizerEngine(
    sessionId: Int,
    frameRate: Int,
    private val onFrame: (List<Float>, List<Float>) -> Unit
) {
    @Volatile private var targetFrameRate = frameRate.coerceIn(15, 60)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameAt = 0L
    private var latestWave: List<Float> = emptyList()
    private var latestFft: List<Float> = emptyList()
    private val visualizer: Visualizer

    init {
        visualizer = Visualizer(sessionId).apply {
            val range = Visualizer.getCaptureSizeRange()
            captureSize = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {
                    publish(data, null)
                }

                override fun onFftDataCapture(v: Visualizer, data: ByteArray, samplingRate: Int) {
                    publish(null, data)
                }
            }, Visualizer.getMaxCaptureRate().coerceAtMost(20_000), true, true)
            enabled = true
        }
    }

    fun setFrameRate(value: Int) {
        targetFrameRate = value.coerceIn(15, 60)
    }

    private fun publish(waveBytes: ByteArray?, fftBytes: ByteArray?) {
        if (waveBytes != null) latestWave = waveBytes.map { ((it.toInt() and 0xff) - 128) / 128f }
        if (fftBytes == null) return
        latestFft = fftBytes.let { bytes ->
            val bins = ArrayList<Float>(bytes.size / 2)
            var i = 2
            while (i + 1 < bytes.size) {
                val real = bytes[i].toInt()
                val imaginary = bytes[i + 1].toInt()
                val magnitude = sqrt((real * real + imaginary * imaginary).toFloat()) / 180f
                bins.add(magnitude.coerceIn(0f, 1f))
                i += 2
            }
            bins
        }
        val now = SystemClock.elapsedRealtime()
        val minInterval = 1000L / targetFrameRate
        if (now - lastFrameAt < minInterval) return
        lastFrameAt = now
        mainHandler.post {
            onFrame(latestWave, latestFft)
        }
    }

    fun stop() {
        runCatching {
            visualizer.enabled = false
            visualizer.setDataCaptureListener(null, 0, false, false)
            visualizer.release()
        }
    }
}

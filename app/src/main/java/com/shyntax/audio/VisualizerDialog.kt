package com.shyntax.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

private val VisualIce = Color(0xFF62D9FF)
private val VisualBg = Color(0xFF000000)

/** Deterministic 0..1 pseudo-random per (particle, salt) so particles keep stable identities. */
private fun particleHash(index: Int, salt: Int): Float {
    val x = sin(index * 127.1 + salt * 311.7) * 43758.5453
    return (x - floor(x)).toFloat()
}

@Composable
fun VisualizerDialog(
    mode: String,
    waveform: FloatArray,
    fft: FloatArray,
    fps: Int,
    quality: Int,
    status: String,
    onMode: (String) -> Unit,
    onFps: (Int) -> Unit,
    onQuality: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    var fullscreen by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = fullscreen)
    ) {
        Surface(
            color = Color(0xFF0A0D11),
            shape = RoundedCornerShape(22.dp),
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp).then(if (fullscreen) Modifier.fillMaxSize() else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUDIO VISUALIZER", color = VisualIce, style = MaterialTheme.typography.labelLarge)
                        Text("SHYNTAX • LIVE FFT", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { fullscreen = !fullscreen }) {
                        Icon(
                            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (fullscreen) "Keluar layar penuh" else "Layar penuh",
                            tint = VisualIce
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
                VisualizerCanvas(
                    mode, waveform, fft, quality,
                    if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                    else Modifier.fillMaxWidth().height(210.dp)
                )
                Text(status, color = if (status.startsWith("Terhubung")) VisualIce else Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
                Text("MODE", color = VisualIce, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Waveform", "Spectrum", "Circular", "Oscilloscope", "Particles").forEach {
                        FilterChip(selected = mode == it, onClick = { onMode(it) }, label = { Text(it) })
                    }
                }
                Text("FRAME RATE  •  ${fps} FPS", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { value ->
                        FilterChip(selected = fps == value, onClick = { onFps(value) }, label = { Text("${value}") })
                    }
                }
                Text("KUALITAS  •  ${quality} BINS", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(value = quality.toFloat(), onValueChange = { onQuality((it.toInt() / 32 * 32).coerceIn(32, 128)) },
                    valueRange = 32f..128f, steps = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Mulai / Sambungkan") }
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Jeda") }
                }
                Text("Visualizer membaca output sesi pemutar. FPS dan jumlah bin dapat diturunkan untuk menghemat baterai.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VisualizerCanvas(
    mode: String,
    waveform: FloatArray,
    fft: FloatArray,
    quality: Int,
    modifier: Modifier = Modifier
) {
    val fftState = rememberUpdatedState(fft)
    val waveformState = rememberUpdatedState(waveform)
    val smoothed = remember(quality) { FloatArray(quality) }
    val peaks = remember(quality) { FloatArray(quality) }
    var beatPulse by remember { mutableFloatStateOf(0f) }
    var energyAverage by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var frameVersion by remember { mutableIntStateOf(0) }

    // Cache deterministic particle parameters once; only their animated position changes.
    val particleBase = remember {
        Array(7) { FloatArray(90) }.also { data ->
            for (i in 0 until 90) {
                for (salt in 1..6) data[salt][i] = particleHash(i, salt)
                data[0][i] = if (particleHash(i, 2) > 0.5f) 1f else -1f
            }
        }
    }

    // Animation work and redraws follow the selected FPS instead of the display refresh rate.
    LaunchedEffect(fps, quality) {
        var lastFrameAt = 0L
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            val deltaMs = if (lastFrameAt == 0L) 1000f / fps
                else (now - lastFrameAt).toFloat().coerceIn(1f, 250f)
            lastFrameAt = now
            val frames = deltaMs / 16.7f
            phase += 0.004f * frames

            val target = fftState.value
            val bassBins = min(6, quality)
            var bass = 0f
            for (i in 0 until bassBins) {
                if (i < target.size) bass += target[i].coerceIn(0f, 1f)
            }
            if (bassBins > 0) bass /= bassBins
            val averageK = 1f - 0.96f.pow(frames)
            energyAverage = if (energyAverage <= 0f) bass
                else energyAverage + (bass - energyAverage) * averageK
            if (bass > 0.12f && bass > energyAverage * 1.28f) beatPulse = 1f
            beatPulse *= 0.90f.pow(frames)
            val attackK = 1f - 0.45f.pow(frames)
            val decayK = 1f - 0.90f.pow(frames)
            for (i in 0 until quality) {
                val t = if (i < target.size) target[i].coerceIn(0f, 1f) else 0f
                val current = smoothed[i]
                val next = if (t > current) current + (t - current) * attackK
                    else current + (t - current) * decayK
                smoothed[i] = next
                peaks[i] = maxOf(next, peaks[i] - 0.0035f * frames)
            }
            frameVersion++
            delay((1000L / fps).coerceAtLeast(1L))
        }
    }

    Canvas(modifier.background(VisualBg, RoundedCornerShape(16.dp))) {
        // Read once per scheduled frame to invalidate the drawing layer without per-bin Compose state.
        frameVersion
        val w = size.width
        val h = size.height
        val values = smoothed
        when (mode) {
            "Waveform", "Oscilloscope" -> {
                val samples = waveformState.value
                val sampleCount = min(samples.size, quality * 4)
                val mid = h / 2f
                drawLine(Color(0xFF27465A), Offset(0f, mid), Offset(w, mid), 1f)
                if (sampleCount > 1) {
                    val stepX = w / (sampleCount - 1).toFloat()
                    var previousX = 0f
                    var previousY = mid - samples[0] * h * 0.42f
                    for (i in 1 until sampleCount) {
                        val x = i * stepX
                        val y = mid - samples[i] * h * 0.42f
                        drawLine(VisualIce, Offset(previousX, previousY), Offset(x, y),
                            if (mode == "Oscilloscope") 2.5f else 1.8f, cap = StrokeCap.Round)
                        previousX = x
                        previousY = y
                    }
                }
            }
            "Circular" -> {
                val cx = w / 2f
                val cy = h / 2f
                val radius = min(w, h) * (0.22f + beatPulse * 0.025f)
                drawCircle(VisualIce.copy(alpha = 0.16f + beatPulse * 0.30f), radius * 0.62f, Offset(cx, cy))
                drawCircle(Color(0xFF25465B), radius, Offset(cx, cy), style = Stroke(2f))
                for (i in 0 until quality) {
                    val angle = (i.toFloat() / quality) * (2.0 * PI) - PI / 2.0
                    val value = values[i]
                    val outer = radius + value * min(w, h) * 0.24f
                    drawLine(VisualIce,
                        Offset(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius),
                        Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                        strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
            }
            "Particles" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxRadius = min(w, h) * 0.5f
                for (i in 0 until 90) {
                    val direction = particleBase[0][i]
                    val angle = particleBase[1][i] * (2.0 * PI).toFloat() +
                        direction * phase * (0.4f + particleBase[3][i] * 0.9f)
                    val binIndex = (particleBase[4][i] * quality).toInt().coerceIn(0, quality - 1)
                    val energy = values[binIndex]
                    val radius = maxRadius * (0.10f + particleBase[5][i] * 0.55f + energy * 0.22f + beatPulse * 0.10f)
                    val x = cx + cos(angle) * radius
                    val y = cy + sin(angle) * radius
                    val particleSize = 1.5f + particleBase[6][i] * 3f + energy * 5f + beatPulse * 2.5f
                    val alpha = (0.25f + energy * 0.55f + beatPulse * 0.20f).coerceIn(0f, 1f)
                    drawCircle(VisualIce.copy(alpha = alpha), particleSize, Offset(x, y))
                }
            }
            else -> {
                val gap = 3f
                val barWidth = (w - gap * quality) / quality
                for (i in 0 until quality) {
                    val value = values[i]
                    val barHeight = (value * h * 0.88f).coerceAtLeast(2f)
                    drawRoundRect(VisualIce, Offset(i * (barWidth + gap), h - barHeight),
                        Size(barWidth.coerceAtLeast(1f), barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                    val peak = peaks[i]
                    if (peak > value + 0.02f) {
                        val peakHeight = (peak * h * 0.88f).coerceAtLeast(2f)
                        drawRect(VisualIce.copy(alpha = 0.85f),
                            Offset(i * (barWidth + gap), h - peakHeight - 4f),
                            Size(barWidth.coerceAtLeast(1f), 3f))
                    }
                }
            }
        }
    }
}

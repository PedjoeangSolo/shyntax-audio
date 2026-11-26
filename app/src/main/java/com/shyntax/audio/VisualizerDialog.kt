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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
    waveform: List<Float>,
    fft: List<Float>,
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
    waveform: List<Float>,
    fft: List<Float>,
    quality: Int,
    modifier: Modifier = Modifier
) {
    val fftState = rememberUpdatedState(fft)
    val smoothed = remember { mutableStateListOf<Float>() }
    val peaks = remember { mutableStateListOf<Float>() }
    var beatPulse by remember { mutableFloatStateOf(0f) }
    var energyAverage by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }

    // Per-display-frame animation state: fast attack / slow decay smoothing per bin,
    // falling peak caps, and a simple bass-energy beat detector driving [beatPulse].
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                val deltaMs = if (lastNanos == 0L) 16.7f
                    else ((frameNanos - lastNanos) / 1_000_000f).coerceIn(1f, 100f)
                lastNanos = frameNanos
                val frames = deltaMs / 16.7f
                phase += 0.004f * frames
                while (smoothed.size < quality) { smoothed.add(0f); peaks.add(0f) }
                val target = fftState.value
                val bassBins = min(6, quality)
                var bass = 0f
                for (i in 0 until bassBins) bass += target.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                if (bassBins > 0) bass /= bassBins
                val averageK = 1f - 0.96f.pow(frames)
                energyAverage = if (energyAverage <= 0f) bass
                    else energyAverage + (bass - energyAverage) * averageK
                if (bass > 0.12f && bass > energyAverage * 1.28f) beatPulse = 1f
                beatPulse *= 0.90f.pow(frames)
                val attackK = 1f - 0.45f.pow(frames)
                val decayK = 1f - 0.90f.pow(frames)
                for (i in 0 until quality) {
                    val t = target.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val current = smoothed[i]
                    val next = if (t > current) current + (t - current) * attackK
                        else current + (t - current) * decayK
                    smoothed[i] = next
                    peaks[i] = maxOf(next, peaks[i] - 0.0035f * frames)
                }
            }
        }
    }

    Canvas(modifier.background(VisualBg, RoundedCornerShape(16.dp))) {
        val w = size.width
        val h = size.height
        val values: List<Float> = if (smoothed.size >= quality) smoothed.subList(0, quality)
            else fft.take(quality).ifEmpty { List(quality) { 0f } }
        when (mode) {
            "Waveform", "Oscilloscope" -> {
                val samples = waveform.take(quality * 4)
                val mid = h / 2f
                drawLine(Color(0xFF27465A), Offset(0f, mid), Offset(w, mid), 1f)
                if (samples.size > 1) {
                    val stepX = w / (samples.size - 1).toFloat()
                    for (i in 1 until samples.size) {
                        val a = Offset((i - 1) * stepX, mid - samples[i - 1] * h * 0.42f)
                        val b = Offset(i * stepX, mid - samples[i] * h * 0.42f)
                        drawLine(VisualIce, a, b, if (mode == "Oscilloscope") 2.5f else 1.8f, cap = StrokeCap.Round)
                    }
                }
            }
            "Circular" -> {
                val cx = w / 2f
                val cy = h / 2f
                val radius = min(w, h) * (0.22f + beatPulse * 0.025f)
                drawCircle(VisualIce.copy(alpha = 0.16f + beatPulse * 0.30f), radius * 0.62f, Offset(cx, cy))
                drawCircle(Color(0xFF25465B), radius, Offset(cx, cy), style = Stroke(2f))
                values.forEachIndexed { i, value ->
                    val angle = (i.toFloat() / values.size) * (2.0 * PI) - PI / 2.0
                    val inner = radius
                    val outer = radius + value * min(w, h) * 0.24f
                    drawLine(VisualIce,
                        Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                        Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                        strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
            }
            "Particles" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxRadius = min(w, h) * 0.5f
                val particleCount = 90
                for (i in 0 until particleCount) {
                    val direction = if (particleHash(i, 2) > 0.5f) 1f else -1f
                    val angle = particleHash(i, 1) * (2.0 * PI).toFloat() +
                        direction * phase * (0.4f + particleHash(i, 3) * 0.9f)
                    val binIndex = (particleHash(i, 4) * values.size).toInt()
                        .coerceIn(0, maxOf(0, values.size - 1))
                    val energy = values.getOrElse(binIndex) { 0f }
                    val radius = maxRadius * (0.10f + particleHash(i, 5) * 0.55f + energy * 0.22f + beatPulse * 0.10f)
                    val x = cx + cos(angle) * radius
                    val y = cy + sin(angle) * radius
                    val particleSize = 1.5f + particleHash(i, 6) * 3f + energy * 5f + beatPulse * 2.5f
                    val alpha = (0.25f + energy * 0.55f + beatPulse * 0.20f).coerceIn(0f, 1f)
                    drawCircle(VisualIce.copy(alpha = alpha), particleSize, Offset(x, y))
                }
            }
            else -> {
                val gap = 3f
                val barWidth = (w - gap * values.size) / values.size
                values.forEachIndexed { i, value ->
                    val barHeight = (value * h * 0.88f).coerceAtLeast(2f)
                    drawRoundRect(VisualIce, Offset(i * (barWidth + gap), h - barHeight),
                        Size(barWidth.coerceAtLeast(1f), barHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                    val peak = peaks.getOrElse(i) { value }
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

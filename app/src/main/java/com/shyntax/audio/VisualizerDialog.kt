package com.shyntax.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val VisualIce = Color(0xFF62D9FF)
private val VisualBg = Color(0xFF000000)

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
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color(0xFF0A0D11), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUDIO VISUALIZER", color = VisualIce, style = MaterialTheme.typography.labelLarge)
                        Text("SHYNTAX • LIVE FFT", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
                VisualizerCanvas(mode, waveform, fft, quality, Modifier.fillMaxWidth().height(210.dp))
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
    Canvas(modifier.background(VisualBg, RoundedCornerShape(16.dp))) {
        val w = size.width
        val h = size.height
        val values = fft.take(quality).ifEmpty { List(quality) { 0f } }
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
                val radius = min(w, h) * 0.22f
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
                values.forEachIndexed { i, value ->
                    val angle = (i.toFloat() / values.size) * (2.0 * PI)
                    val radius = min(w, h) * (0.12f + value * 0.34f)
                    val x = w / 2f + cos(angle).toFloat() * radius
                    val y = h / 2f + sin(angle).toFloat() * radius
                    drawCircle(VisualIce.copy(alpha = 0.35f + value * 0.65f),
                        1.5f + value * 5f, Offset(x, y))
                }
            }
            else -> {
                val gap = 3f
                val barWidth = (w - gap * values.size) / values.size
                values.forEachIndexed { i, value ->
                    val barHeight = (value * h * 0.88f).coerceAtLeast(2f)
                    drawRoundRect(VisualIce, Offset(i * (barWidth + gap), h - barHeight),
                        Size(barWidth.coerceAtLeast(1f), barHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                }
            }
        }
    }
}

package com.shyntax.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DspIce = Color(0xFF62D9FF)
private val DspMuted = Color(0xFF93A8BC)

@Composable
fun DspDialog(
    bass: Int, treble: Int, width: Int, loudness: Int, reverb: String,
    onChange: (Int, Int, Int, Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    var b by remember(bass) { mutableFloatStateOf(bass.toFloat()) }
    var t by remember(treble) { mutableFloatStateOf(treble.toFloat()) }
    var w by remember(width) { mutableFloatStateOf(width.toFloat()) }
    var l by remember(loudness) { mutableFloatStateOf(loudness.toFloat()) }
    var r by remember(reverb) { mutableStateOf(reverb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF000000),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SHYNTAX", color = DspIce, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("DSP Lanjutan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Bass, treble, stereo width & reverb", color = DspMuted, fontSize = 11.sp)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DspSliderLabel("BASS BOOST", "${b.toInt()}%")
                Slider(value = b, onValueChange = { b = it }, valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DspIce, inactiveTrackColor = Color(0xFF3A4652)))
                DspSliderLabel("TREBLE", "${t.toInt()} dB")
                Slider(value = t, onValueChange = { t = it }, valueRange = -12f..12f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DspIce, inactiveTrackColor = Color(0xFF3A4652)))
                DspSliderLabel("STEREO WIDTH / VIRTUALIZER", "${w.toInt()}%")
                Slider(value = w, onValueChange = { w = it }, valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DspIce, inactiveTrackColor = Color(0xFF3A4652)))
                DspSliderLabel("LOUDNESS ENHANCER", "${l.toInt()} mB")
                Slider(value = l, onValueChange = { l = it }, valueRange = 0f..1000f, steps = 19,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DspIce, inactiveTrackColor = Color(0xFF3A4652)))
                Text("REVERB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Off", "Room", "Hall", "Plate").forEach { option ->
                        FilterChip(selected = r == option, onClick = { r = option }, label = { Text(option) })
                    }
                }
                Text("Ketersediaan efek bergantung pada implementasi audio perangkat. Jika efek tidak didukung, aplikasi melewatinya dengan aman.", color = DspMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(onClick = { onChange(b.toInt(), t.toInt(), w.toInt(), l.toInt(), r); onDismiss() }) { Text("Terapkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = DspIce) } }
    )
}

@Composable
private fun DspSliderLabel(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Text(value, color = DspIce, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

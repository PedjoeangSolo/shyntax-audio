package com.shyntax.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        title = { Text("ADVANCED AUDIO DSP") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bass Boost • ${b.toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = b, onValueChange = { b = it }, valueRange = 0f..100f)
                Text("Treble • ${t.toInt()} dB", style = MaterialTheme.typography.labelLarge)
                Slider(value = t, onValueChange = { t = it }, valueRange = -12f..12f)
                Text("Stereo Width / Virtualizer • ${w.toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = w, onValueChange = { w = it }, valueRange = 0f..100f)
                Text("Loudness Enhancer • ${l.toInt()} mB", style = MaterialTheme.typography.labelLarge)
                Slider(value = l, onValueChange = { l = it }, valueRange = 0f..1000f, steps = 19)
                Text("Reverb", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Off", "Room", "Hall", "Plate").forEach { option ->
                        FilterChip(selected = r == option, onClick = { r = option }, label = { Text(option) })
                    }
                }
                Text("Ketersediaan efek bergantung pada implementasi audio perangkat. Jika efek tidak didukung, aplikasi melewatinya dengan aman.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onChange(b.toInt(), t.toInt(), w.toInt(), l.toInt(), r); onDismiss() }) { Text("Terapkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

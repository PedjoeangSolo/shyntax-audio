package com.shyntax.audio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val tenLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private val thirtyOneLabels = listOf("20","25","31","40","50","63","80","100","125","160","200","250","315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k","5k","6.3k","8k","10k","12.5k","16k","20k")

@Composable
fun EqualizerDialog(
    levels: List<Float>, enabled: Boolean, bandCount: Int,
    onDismiss: () -> Unit, onEnabledChange: (Boolean) -> Unit,
    onBandCountChange: (Int) -> Unit, onBandChange: (Int, Float) -> Unit,
    onPreset: (String) -> Unit,
    onParametricApply: (List<Float>, List<Float>, List<Float>) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf("Custom") }
    var frequencies by remember { mutableStateOf(listOf(60f, 250f, 1000f, 4000f, 12000f)) }
    var gains by remember { mutableStateOf(List(5) { 0f }) }
    var qs by remember { mutableStateOf(List(5) { 1f }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SHYNTAX EQUALIZER") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(if (enabled) "Efek aktif" else "Bypass", fontSize = 13.sp)
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Grafis") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Parametrik") })
                }
                if (tab == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        FilterChip(selected = bandCount == 10, onClick = { onBandCountChange(10) }, label = { Text("10-band") })
                        FilterChip(selected = bandCount == 31, onClick = { onBandCountChange(31) }, label = { Text("31-band") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Flat","Bass","Treble","Vocal","Rock","Jazz").forEach { name ->
                            TextButton(onClick = { preset = name; onPreset(name) }) { Text(name, fontSize = 10.sp) }
                        }
                    }
                    val count = if (bandCount == 31) 31 else 10
                    val indices = if (bandCount == 31) (0 until 31).toList() else (0 until 10).map { it * 30 / 9 }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(indices) { displayIndex, sourceIndex ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text((if (bandCount == 31) thirtyOneLabels[displayIndex] else tenLabels[displayIndex]).padStart(5), modifier = Modifier.width(48.dp), fontSize = 11.sp)
                                Slider(value = levels[sourceIndex], onValueChange = { onBandChange(sourceIndex, it); preset = "Custom" }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                                Text("${levels[sourceIndex].roundToInt()} dB", modifier = Modifier.width(48.dp), fontSize = 10.sp)
                            }
                        }
                    }
                } else {
                    Text("5 band parametrik • frekuensi, gain, dan Q", fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(frequencies) { index, frequency ->
                            Column {
                                Text("Band ${index + 1} • ${frequency.roundToInt()} Hz", fontSize = 12.sp)
                                Slider(value = frequency, onValueChange = { value -> frequencies = frequencies.toMutableList().also { it[index] = value } }, valueRange = 40f..16000f)
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("Gain", fontSize = 11.sp)
                                    Slider(value = gains[index], onValueChange = { value -> gains = gains.toMutableList().also { it[index] = value } }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                                    Text("${gains[index].roundToInt()} dB", fontSize = 10.sp)
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("Q", fontSize = 11.sp)
                                    Slider(value = qs[index], onValueChange = { value -> qs = qs.toMutableList().also { it[index] = value } }, valueRange = 0.3f..4f, modifier = Modifier.weight(1f))
                                    Text(String.format("%.1f", qs[index]), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Button(onClick = { onParametricApply(frequencies, gains, qs); onEnabledChange(true) }, modifier = Modifier.fillMaxWidth()) { Text("Terapkan EQ parametrik") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai") } }
    )
}

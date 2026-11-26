package com.shyntax.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

private val LyricsIce = Color(0xFF62D9FF)
private val LyricsPanel = Color(0xFF0A0D11)

data class LyricLine(val timeMs: Long?, val text: String)

fun parseLyrics(raw: String): List<LyricLine> {
    val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    val result = mutableListOf<LyricLine>()
    raw.lineSequence().forEach { line ->
        val matches = timestamp.findAll(line).toList()
        val text = timestamp.replace(line, "").trim()
        if (matches.isEmpty()) {
            if (text.isNotBlank()) result.add(LyricLine(null, text))
        } else {
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = match.groupValues[3]
                val millis = when (fractionRaw.length) {
                    1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10L
                    3 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                result.add(LyricLine((minutes * 60L + seconds) * 1000L + millis, text))
            }
        }
    }
    return result.sortedWith(compareBy<LyricLine> { it.timeMs ?: Long.MAX_VALUE })
}

@Composable
fun LyricsTempoPitchDialog(
    lyrics: String,
    positionMs: Long,
    speed: Float,
    pitchSemitones: Int,
    onImportLyrics: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val activeIndex = lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF000000),
        title = { Text("Lyrics • Tempo • Pitch", color = LyricsIce, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportLyrics, modifier = Modifier.weight(1f)) { Text("Impor Lirik") }
                    OutlinedButton(onClick = onReset) { Text("Reset Audio") }
                }
                Text("LIRIK", color = LyricsIce, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (lyrics.isBlank()) {
                    Text("Belum ada lirik. Impor file .lrc atau .txt untuk lagu yang sedang diputar.", color = Color.LightGray)
                } else if (lines.none { it.timeMs != null }) {
                    LazyColumn(Modifier.heightIn(max = 150.dp)) {
                        itemsIndexed(lines) { _, line -> Text(line.text, color = Color.White, modifier = Modifier.padding(vertical = 3.dp)) }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 150.dp)) {
                        itemsIndexed(lines) { index, line ->
                            Text(
                                line.text,
                                color = if (index == activeIndex) LyricsIce else Color.LightGray,
                                fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().background(
                                    if (index == activeIndex) LyricsPanel else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                ).padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Divider(color = LyricsPanel)
                Text("TEMPO / KECEPATAN: ${"%.2f".format(speed)}×", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(value = speed, onValueChange = onSpeedChange, valueRange = 0.5f..2f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50%", color = Color.Gray, fontSize = 11.sp)
                    Text("100%", color = Color.Gray, fontSize = 11.sp)
                    Text("200%", color = Color.Gray, fontSize = 11.sp)
                }
                Text("PITCH: ${if (pitchSemitones > 0) "+" else ""}$pitchSemitones semitone", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(value = pitchSemitones.toFloat(), onValueChange = { onPitchChange(it.toInt()) }, valueRange = -12f..12f, steps = 23)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("-12", color = Color.Gray, fontSize = 11.sp)
                    Text("0", color = Color.Gray, fontSize = 11.sp)
                    Text("+12", color = Color.Gray, fontSize = 11.sp)
                }
                Text("Tempo dan pitch diproses oleh Media3. Kualitas time-stretch bergantung pada codec dan perangkat.", color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai", color = LyricsIce) } }
    )
}

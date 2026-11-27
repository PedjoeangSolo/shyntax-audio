package com.shyntax.audio

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val LyricsIce = Color(0xFF62D9FF)

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

/** Deep tint sampled from the artwork (Spotify-style backdrop); falls back to deep ice blue. */
private fun artworkTint(bitmap: Bitmap?): Color {
    if (bitmap == null) return Color(0xFF0E2A38)
    return try {
        val small = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (x in 0 until small.width) {
            for (y in 0 until small.height) {
                val p = small.getPixel(x, y)
                r += android.graphics.Color.red(p)
                g += android.graphics.Color.green(p)
                b += android.graphics.Color.blue(p)
                n++
            }
        }
        if (n == 0) return Color(0xFF0E2A38)
        val darken = 0.5f
        Color(
            red = (r.toFloat() / n / 255f) * darken,
            green = (g.toFloat() / n / 255f) * darken,
            blue = (b.toFloat() / n / 255f) * darken
        )
    } catch (e: Exception) {
        Color(0xFF0E2A38)
    }
}

/**
 * Full-screen Spotify-style lyrics: large bold lines over an artwork-tinted
 * backdrop, the line being sung in bright white, the rest dimmed, auto-scrolling
 * with playback. Tapping a timestamped line seeks to it. Tempo & pitch controls
 * live in the Equalizer dialog now, not here.
 */
@Composable
fun LyricsDialog(
    lyrics: String,
    positionMs: Long,
    artwork: Bitmap?,
    onImportLyrics: () -> Unit,
    onSeek: (Long) -> Unit,
    onlineStatus: String,
    onFetchOnline: () -> Unit,
    onDismiss: () -> Unit
) {
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val synced = lines.any { it.timeMs != null }
    val activeIndex = if (synced) lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs } else -1
    val tint = remember(artwork) { artworkTint(artwork) }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            val offset = listState.layoutInfo.viewportSize.height / 3
            listState.animateScrollToItem(activeIndex, -offset)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Color.Black)))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lirik", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Tutup", tint = Color.White) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onFetchOnline, modifier = Modifier.weight(1f)) { Text("Cari Lirik Online") }
                OutlinedButton(onClick = onImportLyrics, modifier = Modifier.weight(1f)) { Text("Impor dari File") }
            }
            if (onlineStatus.isNotBlank()) {
                Text(
                    onlineStatus,
                    color = LyricsIce, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            if (lyrics.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada lirik untuk lagu ini.\nKetuk Cari Lirik Online untuk mengambil lirik dari internet, atau Impor dari File untuk memilih file .lrc / .txt.",
                        color = Color.White.copy(alpha = .75f), fontSize = 16.sp, lineHeight = 24.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val timeMs = line.timeMs
                        Text(
                            line.text,
                            color = when {
                                !synced -> Color.White.copy(alpha = .9f)
                                index == activeIndex -> Color.White
                                else -> Color.White.copy(alpha = .42f)
                            },
                            fontSize = 24.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (timeMs != null) Modifier.clickable { onSeek(timeMs) } else Modifier)
                                .padding(vertical = 9.dp)
                        )
                    }
                }
            }
            if (synced) {
                Text(
                    "Ketuk baris lirik untuk melompat ke bagian itu",
                    color = Color.White.copy(alpha = .45f), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

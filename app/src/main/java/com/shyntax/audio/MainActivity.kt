package com.shyntax.audio

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

private val Ice = Color(0xFF69D8FF)
private val Background = Color(0xFF080D14)
private val Panel = Color(0xFF111C29)

data class Track(val uri: Uri, val title: String)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val tracks = mutableStateListOf<Track>()
    private var currentTitle by mutableStateOf("Belum ada lagu")
    private var playing by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "Audio"
            if (tracks.none { it.uri == uri }) tracks.add(Track(uri, name))
        }
        if (tracks.isNotEmpty() && controller?.mediaItemCount == 0) playTrack(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    currentTitle = mediaMetadata.title?.toString() ?: "SHYNTAX AUDIO"
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    duration = controller?.duration?.takeIf { it > 0 } ?: 0L
                }
            })
            if (tracks.isNotEmpty()) playTrack(0)
        }, MoreExecutors.directExecutor())

        lifecycleScope.launch {
            while (isActive) {
                controller?.let { player ->
                    position = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.takeIf { it > 0 } ?: 0L
                }
                delay(400)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Ice, background = Background, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                AudioHome(
                    tracks = tracks.toList(), title = currentTitle, playing = playing,
                    position = position, duration = duration,
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    onSelect = { playTrack(it) },
                    onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onSeek = { controller?.seekTo(it) }
                )
            }
        }
    }

    private fun playTrack(index: Int) {
        val c = controller ?: return
        if (index !in tracks.indices) return
        val items = tracks.map { track ->
            MediaItem.Builder().setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build()).build()
        }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun AudioHome(
    tracks: List<Track>, title: String, playing: Boolean, position: Long, duration: Long,
    onImport: () -> Unit, onSelect: (Int) -> Unit, onPlayPause: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit
) {
    var tab by remember { mutableStateOf("Lagu") }
    var slider by remember(position) { mutableFloatStateOf(position.toFloat()) }
    Column(Modifier.fillMaxSize().background(Background).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("SHYNTAX", color = Ice, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("AUDIO", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Text("0.2.0", color = Ice, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Lagu", "Album", "Artis").forEach {
                FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it) })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onImport) { Icon(Icons.Default.Add, "Import audio", tint = Ice) }
        }
        Text(if (tab == "Lagu") "KOLEKSI MUSIK" else "$tab akan tersedia setelah indeks metadata ditambahkan",
            color = Ice, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
        if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, tint = Ice, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Perpustakaan masih kosong", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Tambahkan lagu dari penyimpanan perangkat", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImport) { Text("Pilih file audio") }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(tracks) { index, track ->
                    Surface(color = Panel, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(index) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = Ice)
                            Spacer(Modifier.width(12.dp))
                            Text(track.title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                            IconButton(onClick = { onSelect(index) }) {
                                Icon(Icons.Default.PlayArrow, "Putar", tint = Ice)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("SEDANG DIPUTAR", color = Ice, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Slider(value = if (duration > 0) slider.coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onSeek(slider.toLong()) },
                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White) }
                    FilledIconButton(onClick = onPlayPause) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White) }
                }
            }
        }
    }
}

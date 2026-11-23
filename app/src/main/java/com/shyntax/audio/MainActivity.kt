package com.shyntax.audio

import android.content.ComponentName
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

private val Ice = Color(0xFF69D8FF)
private val Background = Color(0xFF080D14)
private val Panel = Color(0xFF111C29)
private const val PREFS = "shyntax_library"
private const val TRACK_URIS = "track_uris"

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isFavorite: Boolean = false
)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val tracks = mutableStateListOf<Track>()
    private var currentTitle by mutableStateOf("Belum ada lagu")
    private var playing by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var eqEngine: NativeEqEngine? = null
    private var dspEngine: NativeDspEngine? = null
    private var showDsp by mutableStateOf(false)
    private var showLyricsControls by mutableStateOf(false)
    private var currentTrackIndex by mutableIntStateOf(0)
    private var lyricsText by mutableStateOf("")
    private var playbackSpeed by mutableFloatStateOf(1f)
    private var pitchSemitones by mutableIntStateOf(0)
    private var showVisualizer by mutableStateOf(false)
    private var visualizerEngine: AudioVisualizerEngine? = null
    private var visualizerWave by mutableStateOf<List<Float>>(emptyList())
    private var visualizerFft by mutableStateOf<List<Float>>(emptyList())
    private var visualizerStatus by mutableStateOf("Visualizer belum dimulai")
    private var visualizerMode by mutableStateOf("Waveform")
    private var visualizerFps by mutableIntStateOf(30)
    private var visualizerQuality by mutableIntStateOf(64)
    private val audioPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) startVisualizerCapture()
        else visualizerStatus = "Izin Audio ditolak. Izinkan RECORD_AUDIO untuk membaca visualizer."
    }
    private var dspBass by mutableIntStateOf(0)
    private var dspTreble by mutableIntStateOf(0)
    private var dspWidth by mutableIntStateOf(0)
    private var dspLoudness by mutableIntStateOf(0)
    private var dspReverb by mutableStateOf("Off")
    private val eqLevels = mutableStateListOf<Float>().apply { repeat(31) { add(0f) } }
    private var eqEnabled by mutableStateOf(false)
    private var eqBandCount by mutableIntStateOf(10)
    private var showEqualizer by mutableStateOf(false)
    private var showHiResDialog by mutableStateOf(false)
    private var showBackupDialog by mutableStateOf(false)
    private var backupStatus by mutableStateOf("")
    private var currentAudioInfo by mutableStateOf(AudioFileInfo())
    private var audioOutputs by mutableStateOf<List<AudioOutputInfo>>(emptyList())

    private val backupExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AppBackupManager.export(this@MainActivity, uri)
                withContext(Dispatchers.Main) { backupStatus = result.fold({ "Backup berhasil dibuat." }, { "Backup gagal: ${it.message}" }) }
            }
        }
    }
    private val backupImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AppBackupManager.restore(this@MainActivity, uri)
                withContext(Dispatchers.Main) {
                    backupStatus = result.fold({ "Pemulihan berhasil. Tutup dan buka ulang aplikasi agar semua pengaturan diterapkan." }, { "Restore gagal: ${it.message}" })
                    if (result.isSuccess) restoreLibrary()
                }
            }
        }
    }

    private val lyricsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && currentTrackIndex in tracks.indices) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text != null) {
                lyricsText = text
                getSharedPreferences("shyntax_lyrics", MODE_PRIVATE).edit()
                    .putString(tracks[currentTrackIndex].uri.toString(), text).apply()
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (tracks.none { it.uri == uri }) tracks.add(readTrack(uri))
        }
        saveLibrary()
        if (tracks.isNotEmpty() && controller?.mediaItemCount == 0) playTrack(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLibrary()
        val visualPrefs = getSharedPreferences("shyntax_visualizer", MODE_PRIVATE)
        visualizerMode = visualPrefs.getString("mode", "Waveform") ?: "Waveform"
        visualizerFps = visualPrefs.getInt("fps", 30).coerceIn(15, 60)
        visualizerQuality = visualPrefs.getInt("quality", 64).coerceIn(32, 128)
        val playbackPrefs = getSharedPreferences("shyntax_playback", MODE_PRIVATE)
        playbackSpeed = playbackPrefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        pitchSemitones = playbackPrefs.getInt("pitch", 0).coerceIn(-12, 12)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            applyPlaybackParameters()
            controller?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    if (eqEngine == null) {
                        val id = getSharedPreferences("shyntax_audio", MODE_PRIVATE).getInt("audio_session_id", 0)
                        if (id > 0) setupEqualizer(id)
                    }
                }
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    currentTitle = mediaMetadata.title?.toString() ?: "SHYNTAX AUDIO"
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    duration = controller?.duration?.takeIf { it > 0 } ?: 0L
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTrackIndex = controller?.currentMediaItemIndex ?: 0
                    loadLyricsForCurrentTrack()
                    tracks.getOrNull(currentTrackIndex)?.uri?.let { refreshAudioInfo(it) }
                }
            })
            val sessionId = getSharedPreferences("shyntax_audio", MODE_PRIVATE).getInt("audio_session_id", 0)
            if (sessionId > 0) setupEqualizer(sessionId)
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
                    onFavorite = { index ->
                        if (index in tracks.indices) {
                            tracks[index] = tracks[index].copy(isFavorite = !tracks[index].isFavorite)
                            saveLibrary()
                        }
                    },
                    onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onSeek = { controller?.seekTo(it) },
                    showLyricsControls = showLyricsControls, onShowLyricsControls = { showLyricsControls = it },
                    lyricsText = lyricsText, playbackSpeed = playbackSpeed, pitchSemitones = pitchSemitones,
                    onImportLyrics = { lyricsPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    onSpeedChange = { value -> playbackSpeed = value.coerceIn(0.5f, 2f); applyPlaybackParameters() },
                    onPitchChange = { value -> pitchSemitones = value.coerceIn(-12, 12); applyPlaybackParameters() },
                    onResetPlayback = { playbackSpeed = 1f; pitchSemitones = 0; applyPlaybackParameters() },
                    eqLevels = eqLevels.toList(), eqEnabled = eqEnabled, eqBandCount = eqBandCount,
                    showEqualizer = showEqualizer, onShowEqualizer = { showEqualizer = it },
                    showDsp = showDsp, onShowDsp = { showDsp = it },
                    showVisualizer = showVisualizer, onShowVisualizer = { showVisualizer = it },
                    showHiResDialog = showHiResDialog, onShowHiResDialog = { showHiResDialog = it },
                    audioFileInfo = currentAudioInfo, audioOutputs = audioOutputs,
                    showBackupDialog = showBackupDialog, onShowBackupDialog = { showBackupDialog = it }, backupStatus = backupStatus,
                    onExportBackup = { backupExportPicker.launch("SHYNTAX-AUDIO-backup.zip") },
                    onImportBackup = { backupImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onRefreshAudioInfo = { refreshAudioInfo() },
                    visualizerMode = visualizerMode, visualizerWave = visualizerWave, visualizerFft = visualizerFft,
                    visualizerFps = visualizerFps, visualizerQuality = visualizerQuality, visualizerStatus = visualizerStatus,
                    onStartVisualizer = { requestVisualizerPermission() },
                    onStopVisualizer = { visualizerEngine?.stop(); visualizerStatus = "Visualizer dijeda" },
                    onVisualizerMode = { visualizerMode = it; getSharedPreferences("shyntax_visualizer", MODE_PRIVATE).edit().putString("mode", it).apply() },
                    onVisualizerFps = { visualizerFps = it; getSharedPreferences("shyntax_visualizer", MODE_PRIVATE).edit().putInt("fps", it).apply(); visualizerEngine?.setFrameRate(it) },
                    onVisualizerQuality = { visualizerQuality = it; getSharedPreferences("shyntax_visualizer", MODE_PRIVATE).edit().putInt("quality", it).apply() },
                    dspBass = dspBass, dspTreble = dspTreble, dspWidth = dspWidth, dspLoudness = dspLoudness, dspReverb = dspReverb,
                    onDspChange = { bass, treble, width, loudness, reverb ->
                        dspBass = bass; dspTreble = treble; dspWidth = width; dspLoudness = loudness; dspReverb = reverb
                        dspEngine?.apply(bass, width, loudness, reverb)
                        eqEngine?.applyTreble(treble)
                        if (treble != 0) { eqEnabled = true; eqEngine?.setEnabled(true); saveEqSettings() }
                        getSharedPreferences("shyntax_dsp", MODE_PRIVATE).edit()
                            .putInt("bass", bass).putInt("treble", treble).putInt("width", width)
                            .putInt("loudness", loudness).putString("reverb", reverb).apply()
                    },
                    onEqToggle = { enabled -> eqEnabled = enabled; eqEngine?.setEnabled(enabled) },
                    onEqBandCount = { count -> eqBandCount = count },
                    onEqChange = { index, value ->
                        if (index in eqLevels.indices) eqLevels[index] = value
                        eqEngine?.applyGraphic(eqLevels.toList(), eqBandCount)
                        eqEngine?.applyTreble(dspTreble)
                        saveEqSettings()
                    },
                    onEqPreset = { preset ->
                        val values = when (preset) {
                            "Bass" -> listOf(6f,5f,4f,2f,0f,-1f,-2f,-2f,-1f,0f)
                            "Treble" -> listOf(-2f,-1f,0f,0f,0f,1f,3f,4f,5f,5f)
                            "Vocal" -> listOf(-2f,-1f,0f,2f,3f,3f,2f,1f,0f,-1f)
                            "Rock" -> listOf(4f,3f,1f,-1f,-2f,1f,3f,4f,4f,3f)
                            "Jazz" -> listOf(3f,2f,1f,2f,-1f,-1f,0f,1f,2f,3f)
                            else -> List(10) { 0f }
                        }
                        for (i in 0 until 31) eqLevels[i] = if (eqBandCount == 10) values[(i * 9 / 30).coerceIn(0,9)] else values[(i * 9 / 30).coerceIn(0,9)]
                        eqEngine?.applyGraphic(eqLevels.toList(), eqBandCount)
                        eqEngine?.applyTreble(dspTreble)
                        saveEqSettings()
                    },
                    onParametricApply = { frequencies, gains, qs ->
                        eqEngine?.applyParametric(frequencies, gains, qs)
                        eqEngine?.applyTreble(dspTreble)
                        eqEnabled = true
                        eqEngine?.setEnabled(true)
                    }
                )
            }
        }
    }

    private fun refreshAudioInfo(uri: Uri? = tracks.getOrNull(currentTrackIndex)?.uri) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            val type = audioDeviceTypeName(device.type)
            AudioOutputInfo(
                name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: type,
                type = type,
                isUsb = device.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
        if (uri == null) {
            currentAudioInfo = AudioFileInfo(detail = "Pilih dan putar file audio untuk membaca metadata.")
            return
        }
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(this@MainActivity, uri, null)
                    var found: AudioFileInfo? = null
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("audio/")) continue
                        found = AudioFileInfo(
                            mimeType = mime,
                            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null,
                            channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null,
                            bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else null,
                            pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else null,
                            detail = "Metadata dari extractor Android; bukan pembacaan sinyal aktual pada DAC."
                        )
                        break
                    }
                    found ?: AudioFileInfo(detail = "Track audio tidak dikenali oleh extractor Android.")
                } catch (error: Exception) {
                    AudioFileInfo(detail = "Metadata tidak dapat dibaca: ${error.message ?: "format atau provider tidak didukung"}.")
                } finally {
                    runCatching { extractor.release() }
                }
            }
            if (tracks.getOrNull(currentTrackIndex)?.uri == uri) currentAudioInfo = info
        }
    }

    private fun loadLyricsForCurrentTrack() {
        val track = tracks.getOrNull(currentTrackIndex) ?: run {
            lyricsText = ""
            return
        }
        val trackUri = track.uri
        // A manually imported lyric is an explicit override. Otherwise read embedded tags off the UI thread.
        val manualLyrics = getSharedPreferences("shyntax_lyrics", MODE_PRIVATE)
            .getString(trackUri.toString(), null)
        if (!manualLyrics.isNullOrBlank()) {
            lyricsText = manualLyrics
            return
        }
        lyricsText = ""
        lifecycleScope.launch {
            val embedded = withContext(Dispatchers.IO) {
                EmbeddedLyricsReader.read(contentResolver, trackUri)
            }.orEmpty()
            // Ignore a slow read if the user has already changed to another track.
            if (tracks.getOrNull(currentTrackIndex)?.uri == trackUri) {
                lyricsText = embedded
            }
        }
    }

    private fun applyPlaybackParameters() {
        val pitch = Math.pow(2.0, pitchSemitones / 12.0).toFloat()
        controller?.playbackParameters = PlaybackParameters(playbackSpeed, pitch)
        getSharedPreferences("shyntax_playback", MODE_PRIVATE).edit()
            .putFloat("speed", playbackSpeed).putInt("pitch", pitchSemitones).apply()
    }

    private fun requestVisualizerPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVisualizerCapture()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVisualizerCapture() {
        val sessionId = getSharedPreferences("shyntax_audio", MODE_PRIVATE).getInt("audio_session_id", 0)
        if (sessionId <= 0) {
            visualizerStatus = "Sesi audio belum tersedia. Putar lagu terlebih dahulu."
            return
        }
        runCatching {
            visualizerEngine?.stop()
            visualizerEngine = AudioVisualizerEngine(sessionId, visualizerFps) { wave, fft ->
                visualizerWave = wave
                visualizerFft = fft
            }
            visualizerEngine?.start()
            visualizerStatus = "Terhubung ke sesi audio"
        }.onFailure {
            visualizerStatus = "Visualizer tidak tersedia: ${it.message ?: "efek audio ditolak perangkat"}"
        }
    }

    private fun setupEqualizer(sessionId: Int) {
        runCatching {
            eqEngine?.release()
            eqEngine = NativeEqEngine(sessionId)
            dspEngine?.release()
            dspEngine = NativeDspEngine(sessionId)
            val dspPrefs = getSharedPreferences("shyntax_dsp", MODE_PRIVATE)
            dspBass = dspPrefs.getInt("bass", 0).coerceIn(0, 100)
            dspTreble = dspPrefs.getInt("treble", 0).coerceIn(-12, 12)
            dspWidth = dspPrefs.getInt("width", 0).coerceIn(0, 100)
            dspLoudness = dspPrefs.getInt("loudness", 0).coerceIn(0, 1000)
            dspReverb = dspPrefs.getString("reverb", "Off") ?: "Off"
            dspEngine?.apply(dspBass, dspWidth, dspLoudness, dspReverb)
            val prefs = getSharedPreferences("shyntax_eq", MODE_PRIVATE)
            eqEnabled = prefs.getBoolean("enabled", false)
            eqBandCount = prefs.getInt("band_count", 10).coerceIn(10, 31)
            val saved = prefs.getString("levels", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            if (saved != null && saved.size == 31) saved.forEachIndexed { i, v -> eqLevels[i] = v.coerceIn(-12f, 12f) }
            eqEngine?.applyGraphic(eqLevels.toList(), eqBandCount)
            eqEngine?.applyTreble(dspTreble)
            eqEngine?.setEnabled(eqEnabled)
        }.onFailure { eqEnabled = false }
    }

    private fun saveEqSettings() {
        getSharedPreferences("shyntax_eq", MODE_PRIVATE).edit()
            .putBoolean("enabled", eqEnabled)
            .putInt("band_count", eqBandCount)
            .putString("levels", eqLevels.joinToString(","))
            .apply()
    }

    private fun readTrack(uri: Uri): Track {
        val fileName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() } ?: "Audio"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fileName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Artis tidak diketahui"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Album tidak diketahui"
            val length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Track(uri, title, artist, album, length)
        } catch (_: Exception) {
            Track(uri, fileName, "Artis tidak diketahui", "Album tidak diketahui", 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun restoreLibrary() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(TRACK_URIS, emptySet()).orEmpty()
        saved.forEach { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@forEach
            runCatching { tracks.add(readTrack(uri)) }
        }
    }

    private fun saveLibrary() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(TRACK_URIS, tracks.map { it.uri.toString() }.toSet()).apply()
    }

    private fun playTrack(index: Int) {
        val c = controller ?: return
        if (index !in tracks.indices) return
        currentTrackIndex = index
        loadLyricsForCurrentTrack()
        val items = tracks.map { track ->
            MediaItem.Builder().setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build())
                .build()
        }
        c.setMediaItems(items, index, 0L)
        refreshAudioInfo(tracks[index].uri)
        c.prepare()
        c.play()
    }

    override fun onDestroy() {
        eqEngine?.release()
        dspEngine?.release()
        visualizerEngine?.stop()
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun AudioHome(
    tracks: List<Track>, title: String, playing: Boolean, position: Long, duration: Long,
    onImport: () -> Unit, onSelect: (Int) -> Unit, onFavorite: (Int) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    eqLevels: List<Float>, eqEnabled: Boolean, eqBandCount: Int, showEqualizer: Boolean,
    showLyricsControls: Boolean, onShowLyricsControls: (Boolean) -> Unit,
    lyricsText: String, playbackSpeed: Float, pitchSemitones: Int,
    onImportLyrics: () -> Unit, onSpeedChange: (Float) -> Unit, onPitchChange: (Int) -> Unit, onResetPlayback: () -> Unit,
    showDsp: Boolean, onShowDsp: (Boolean) -> Unit,
    showVisualizer: Boolean, onShowVisualizer: (Boolean) -> Unit,
    showHiResDialog: Boolean, onShowHiResDialog: (Boolean) -> Unit,
    audioFileInfo: AudioFileInfo, audioOutputs: List<AudioOutputInfo>, onRefreshAudioInfo: () -> Unit,\n    showBackupDialog: Boolean, onShowBackupDialog: (Boolean) -> Unit, backupStatus: String,\n    onExportBackup: () -> Unit, onImportBackup: () -> Unit,
    visualizerMode: String, visualizerWave: List<Float>, visualizerFft: List<Float>,
    visualizerFps: Int, visualizerQuality: Int, visualizerStatus: String,
    onStartVisualizer: () -> Unit, onStopVisualizer: () -> Unit,
    onVisualizerMode: (String) -> Unit, onVisualizerFps: (Int) -> Unit, onVisualizerQuality: (Int) -> Unit,
    dspBass: Int, dspTreble: Int, dspWidth: Int, dspLoudness: Int, dspReverb: String,
    onDspChange: (Int, Int, Int, Int, String) -> Unit,
    onShowEqualizer: (Boolean) -> Unit, onEqToggle: (Boolean) -> Unit, onEqBandCount: (Int) -> Unit,
    onEqChange: (Int, Float) -> Unit, onEqPreset: (String) -> Unit,
    onParametricApply: (List<Float>, List<Float>, List<Float>) -> Unit
) {
    var tab by remember { mutableStateOf("Lagu") }
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Judul A–Z") }
    var slider by remember(position) { mutableFloatStateOf(position.toFloat()) }

    val visibleTracks = tracks.withIndex().filter { (_, track) ->
        (!favoritesOnly || track.isFavorite) &&
            (groupFilter == null || if (tab == "Album") track.album == groupFilter else if (tab == "Artis") track.artist == groupFilter else true) &&
            (query.isBlank() || track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true))
    }.let { list ->
        when (sortBy) {
            "Judul Z–A" -> list.sortedByDescending { it.value.title.lowercase() }
            "Artis" -> list.sortedBy { it.value.artist.lowercase() }
            "Album" -> list.sortedBy { it.value.album.lowercase() }
            else -> list.sortedBy { it.value.title.lowercase() }
        }
    }

    if (showLyricsControls) {
        LyricsTempoPitchDialog(
            lyrics = lyricsText, positionMs = position, speed = playbackSpeed, pitchSemitones = pitchSemitones,
            onImportLyrics = onImportLyrics, onSpeedChange = onSpeedChange, onPitchChange = onPitchChange,
            onReset = onResetPlayback, onDismiss = { onShowLyricsControls(false) }
        )
    }
    if (showDsp) {
        DspDialog(bass = dspBass, treble = dspTreble, width = dspWidth, loudness = dspLoudness, reverb = dspReverb,
            onChange = onDspChange, onDismiss = { onShowDsp(false) })
    }
    if (showVisualizer) {
        VisualizerDialog(
            mode = visualizerMode, waveform = visualizerWave, fft = visualizerFft,
            fps = visualizerFps, quality = visualizerQuality, status = visualizerStatus,
            onMode = onVisualizerMode, onFps = onVisualizerFps, onQuality = onVisualizerQuality,
            onStart = onStartVisualizer, onStop = onStopVisualizer,
            onDismiss = { onShowVisualizer(false) }
        )
    }
    if (showHiResDialog) {
        HiResAudioDialog(fileInfo = audioFileInfo, outputs = audioOutputs, onRefresh = onRefreshAudioInfo,
            onDismiss = { onShowHiResDialog(false) })
    }
    if (showEqualizer) {
        EqualizerDialog(
            levels = eqLevels, enabled = eqEnabled, bandCount = eqBandCount,
            onDismiss = { onShowEqualizer(false) }, onEnabledChange = onEqToggle,
            onBandCountChange = onEqBandCount, onBandChange = onEqChange,
            onPreset = onEqPreset, onParametricApply = onParametricApply
        )
    }
    Column(Modifier.fillMaxSize().background(Background).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("SHYNTAX", color = Ice, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("AUDIO", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Text("0.9.0", color = Ice, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it; groupFilter = null },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Cari lagu, album, atau artis…", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Ice) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Hapus") } },
            shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF26384A),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Lagu", "Album", "Artis").forEach {
                    FilterChip(selected = tab == it, onClick = { tab = it; groupFilter = null; favoritesOnly = false }, label = { Text(it) })
                }
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly; groupFilter = null },
                    label = { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Favorit") })
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Urutkan", tint = Ice) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Judul A–Z", "Judul Z–A", "Artis", "Album").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { sortBy = option; sortMenu = false })
                    }
                }
            }
            IconButton(onClick = { onShowLyricsControls(true) }) {
                Icon(Icons.Default.Lyrics, "Lirik, tempo dan pitch", tint = Ice)
            }
            IconButton(onClick = { onShowEqualizer(true) }) {
                Icon(Icons.Default.Tune, "Equalizer", tint = Ice)
            }
            IconButton(onClick = { onShowVisualizer(true) }) {
                Icon(Icons.Default.GraphicEq, "Visualizer", tint = Ice)
            }
            IconButton(onClick = { onShowHiResDialog(true); onRefreshAudioInfo() }) {
                Icon(Icons.Default.Headphones, "Hi-Res dan output audio", tint = Ice)
            }
            IconButton(onClick = { onShowDsp(true) }) {
                Icon(Icons.Default.GraphicEq, "Advanced Audio DSP", tint = Ice)
            }
            IconButton(onClick = onImport) { Icon(Icons.Default.Add, "Tambah lagu", tint = Ice) }
        }
        Text(
            when {
                favoritesOnly -> "LAGU FAVORIT"
                groupFilter != null -> groupFilter!!.uppercase()
                else -> when (tab) { "Album" -> "ALBUM"; "Artis" -> "ARTIS"; else -> "SEMUA LAGU" }
            },
            color = Ice, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
        )
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
        } else if (tab != "Lagu" && groupFilter == null && !favoritesOnly) {
            val groups = if (tab == "Album") tracks.groupBy { it.album } else tracks.groupBy { it.artist }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.keys.sorted(), key = { it }) { name ->
                    val groupTracks = groups[name].orEmpty()
                    Surface(color = Panel, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { groupFilter = name }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tab == "Album") Icons.Default.Album else Icons.Default.Person, null, tint = Ice)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${groupTracks.size} lagu", color = Color.LightGray, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Ice)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groupFilter != null) item {
                    TextButton(onClick = { groupFilter = null }) { Icon(Icons.Default.ArrowBack, null); Text("Kembali ke ${tab.lowercase()}") }
                }
                items(visibleTracks, key = { it.value.uri.toString() }) { indexed ->
                    val track = indexed.value
                    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = Ice)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).clickable { onSelect(indexed.index) }.padding(vertical = 7.dp)) {
                                Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${track.artist} • ${track.album}", color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { onFavorite(indexed.index) }) {
                                Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    "Favorit", tint = if (track.isFavorite) Ice else Color.LightGray)
                            }
                            IconButton(onClick = { onSelect(indexed.index) }) {
                                Icon(Icons.Default.PlayArrow, "Putar", tint = Ice)
                            }
                        }
                    }
                }
                if (visibleTracks.isEmpty()) item {
                    Text("Tidak ada lagu yang cocok.", color = Color.LightGray, modifier = Modifier.padding(18.dp))
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

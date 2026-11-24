package com.shyntax.audio

import android.content.ComponentName
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.OpenableColumns
import android.provider.MediaStore
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

private val Ice = Color(0xFF62D9FF)
private val Background = Color(0xFF080C14)
private val Panel = Color(0xFF111A28)
private val PanelRaised = Color(0xFF192638)
private val MutedText = Color(0xFF93A8BC)
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
    private val recentTrackUris = mutableStateListOf<String>()
    private var abRepeatStart by mutableStateOf<Long?>(null)
    private var abRepeatEnd by mutableStateOf<Long?>(null)
    private var currentTitle by mutableStateOf("Belum ada lagu")
    private var playing by mutableStateOf(false)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
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
    private var showAudioOutputSettings by mutableStateOf(false)
    private var backupStatus by mutableStateOf("")
    private var currentAudioInfo by mutableStateOf(AudioFileInfo())
    private var currentArtwork by mutableStateOf<Bitmap?>(null)
    private var audioOutputs by mutableStateOf<List<AudioOutputInfo>>(emptyList())
    private var outputDeviceCallback: AudioDeviceCallback? = null
    private var scanStatus by mutableStateOf("Menyiapkan pemindaian musik…")
    private val musicPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) scanMediaLibrary() else scanStatus = "Izin musik ditolak. Gunakan Tambah Lagu untuk memilih file manual."
    }

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
        restorePlayHistory()
        val outputManager = getSystemService(AUDIO_SERVICE) as AudioManager
        outputDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshAudioInfo()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshAudioInfo()
            }
        }
        outputManager.registerAudioDeviceCallback(outputDeviceCallback, Handler(mainLooper))
        refreshAudioInfo()
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
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffleEnabled = shuffleModeEnabled
                }
                override fun onRepeatModeChanged(newRepeatMode: Int) {
                    repeatMode = newRepeatMode
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTrackIndex = controller?.currentMediaItemIndex ?: 0
                    abRepeatStart = null
                    abRepeatEnd = null
                    tracks.getOrNull(currentTrackIndex)?.let { recordPlayedTrack(it.uri) }
                    AudioAnalysisState.reset()
                    loadLyricsForCurrentTrack()
                    tracks.getOrNull(currentTrackIndex)?.let { track ->
                        refreshAudioInfo(track.uri)
                        loadArtwork(track.uri, track.album, track.artist)
                    }
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
                    val loopStart = abRepeatStart
                    val loopEnd = abRepeatEnd
                    if (player.isPlaying && loopStart != null && loopEnd != null && player.currentPosition >= loopEnd) {
                        player.seekTo(loopStart)
                        position = loopStart
                    }
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
                    tracks = tracks.toList(), recentTrackUris = recentTrackUris.toList(), title = currentTitle, playing = playing,
                    position = position, duration = duration,
                    playingTrackIndex = currentTrackIndex, shuffleEnabled = shuffleEnabled, repeatMode = repeatMode,
                    onToggleShuffle = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                    onCycleRepeat = { controller?.let { player ->
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    } },
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    scanStatus = scanStatus,
                    onScan = { requestMusicScan() },
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
                    abRepeatStart = abRepeatStart, abRepeatEnd = abRepeatEnd,
                    onSetAbStart = { abRepeatStart = controller?.currentPosition?.coerceAtLeast(0L); abRepeatEnd = null },
                    onSetAbEnd = { val now = controller?.currentPosition?.coerceAtLeast(0L); if (abRepeatStart != null && now != null && now > abRepeatStart!!) abRepeatEnd = now },
                    onClearAbRepeat = { abRepeatStart = null; abRepeatEnd = null },
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
                    audioFileInfo = currentAudioInfo, audioOutputs = audioOutputs, artwork = currentArtwork,
                    showBackupDialog = showBackupDialog, onShowBackupDialog = { showBackupDialog = it }, backupStatus = backupStatus,
                    showAudioOutputSettings = showAudioOutputSettings, onShowAudioOutputSettings = { showAudioOutputSettings = it },
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
        lifecycleScope.launch { delay(700); requestMusicScan() }
    }

    private fun requestMusicScan() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) scanMediaLibrary()
        else musicPermissionLauncher.launch(permission)
    }

    private fun scanMediaLibrary() {
        scanStatus = "Memindai penyimpanan musik…"
        lifecycleScope.launch {
            val discovered = withContext(Dispatchers.IO) {
                val result = mutableListOf<Track>()
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DISPLAY_NAME
                )
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(collection, projection, null, null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val mime = cursor.getString(mimeCol).orEmpty()
                        if (mime.isNotBlank() && !mime.startsWith("audio/")) continue
                        val uri = Uri.withAppendedPath(collection, cursor.getLong(idCol).toString())
                        result.add(Track(
                            uri = uri,
                            title = cursor.getString(titleCol)
                                ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                                ?: cursor.getString(displayNameCol)?.substringBeforeLast('.', missingDelimiterValue = "")
                                    ?.takeIf { it.isNotBlank() } ?: "Audio",
                            artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artis tidak diketahui",
                            album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Album tidak diketahui",
                            durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                        ))
                    }
                }
                result
            }
            val favorites = tracks.associate { it.uri.toString() to it.isFavorite }
            val merged = (tracks + discovered).distinctBy { it.uri.toString() }
                .map { it.copy(isFavorite = favorites[it.uri.toString()] ?: it.isFavorite) }
            tracks.clear()
            tracks.addAll(merged)
            saveLibrary()
            scanStatus = if (discovered.isEmpty()) "Pemindaian selesai: tidak ada lagu ditemukan."
                else "Pemindaian selesai: ${discovered.size} file audio ditemukan; ${tracks.size} lagu di pustaka."
            if (tracks.isNotEmpty() && controller?.mediaItemCount == 0) playTrack(0)
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

    private fun loadArtwork(uri: Uri, album: String, artist: String) {
        currentArtwork = null
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val key = (artist + "|" + album).hashCode().toString()
                val folder = File(cacheDir, "album_art").apply { mkdirs() }
                val cached = File(folder, "$key.jpg")

                val embedded = runCatching {
                    MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(this@MainActivity, uri)
                            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        } finally { retriever.release() }
                    }
                }.getOrNull()
                if (embedded != null) {
                    runCatching { cached.outputStream().use { embedded.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                    return@withContext embedded
                }

                val localCover = runCatching {
                    val path = contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    val parent = path?.let { File(it).parentFile }
                    val names = listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "cover.png", "folder.png")
                    names.firstNotNullOfOrNull { name ->
                        parent?.let { File(it, name) }?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    }
                }.getOrNull()
                if (localCover != null) {
                    runCatching { cached.outputStream().use { localCover.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                    return@withContext localCover
                }
                if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath)?.let { return@withContext it }

                if (album.isBlank() || album == "Album tidak diketahui" ||
                    artist.isBlank() || artist == "Artis tidak diketahui") return@withContext null
                runCatching {
                    val query = "release:" + "\"" + album + "\"" + " AND artist:" + "\"" + artist + "\""
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val search = URL("https://musicbrainz.org/ws/2/release/?query=$encoded&fmt=json&limit=1")
                    val connection = (search.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7000
                        readTimeout = 7000
                        setRequestProperty("User-Agent", "SHYNTAX-AUDIO/1.0 (Android music player)")
                        setRequestProperty("Accept", "application/json")
                    }
                    val releaseId = try {
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        json.optJSONArray("releases")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                    } finally { connection.disconnect() }
                    if (releaseId == null) return@runCatching null
                    val artUrl = URL("https://coverartarchive.org/release/$releaseId/front-500")
                    val artConnection = (artUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7000
                        readTimeout = 10000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "SHYNTAX-AUDIO/1.0 (Android music player)")
                    }
                    try { artConnection.inputStream.use { BitmapFactory.decodeStream(it) } }
                    finally { artConnection.disconnect() }
                }.getOrNull()?.also { image ->
                    runCatching { cached.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                }
            }
            if (tracks.getOrNull(currentTrackIndex)?.uri == uri) currentArtwork = bitmap
        }
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

    private fun restorePlayHistory() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("recent_track_uris", "[]") ?: "[]"
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val uri = array.optString(i).takeIf { it.isNotBlank() } ?: continue
                if (recentTrackUris.size < 50 && uri !in recentTrackUris) recentTrackUris.add(uri)
            }
        }
    }

    private fun recordPlayedTrack(uri: Uri) {
        val value = uri.toString()
        recentTrackUris.remove(value)
        recentTrackUris.add(0, value)
        while (recentTrackUris.size > 50) recentTrackUris.removeAt(recentTrackUris.lastIndex)
        val array = org.json.JSONArray()
        recentTrackUris.forEach { array.put(it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("recent_track_uris", array.toString()).apply()
    }

    private fun playTrack(index: Int) {
        val c = controller ?: return
        if (index !in tracks.indices) return
        currentTrackIndex = index
        recordPlayedTrack(tracks[index].uri)
        loadLyricsForCurrentTrack()
        tracks[index].let { loadArtwork(it.uri, it.album, it.artist) }
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
        outputDeviceCallback?.let {
            (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it)
        }
        eqEngine?.release()
        dspEngine?.release()
        visualizerEngine?.stop()
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun AudioHome(
    tracks: List<Track>, recentTrackUris: List<String>, title: String, playing: Boolean, position: Long, duration: Long,
    playingTrackIndex: Int, shuffleEnabled: Boolean, repeatMode: Int,
    onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit,
    abRepeatStart: Long?, abRepeatEnd: Long?, onSetAbStart: () -> Unit, onSetAbEnd: () -> Unit, onClearAbRepeat: () -> Unit,
    onImport: () -> Unit, scanStatus: String, onScan: () -> Unit,
    onSelect: (Int) -> Unit, onFavorite: (Int) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    eqLevels: List<Float>, eqEnabled: Boolean, eqBandCount: Int, showEqualizer: Boolean,
    showLyricsControls: Boolean, onShowLyricsControls: (Boolean) -> Unit,
    lyricsText: String, playbackSpeed: Float, pitchSemitones: Int,
    onImportLyrics: () -> Unit, onSpeedChange: (Float) -> Unit, onPitchChange: (Int) -> Unit, onResetPlayback: () -> Unit,
    showDsp: Boolean, onShowDsp: (Boolean) -> Unit,
    showVisualizer: Boolean, onShowVisualizer: (Boolean) -> Unit,
    showHiResDialog: Boolean, onShowHiResDialog: (Boolean) -> Unit,
    audioFileInfo: AudioFileInfo, audioOutputs: List<AudioOutputInfo>, artwork: Bitmap?, onRefreshAudioInfo: () -> Unit,
    showBackupDialog: Boolean, onShowBackupDialog: (Boolean) -> Unit, backupStatus: String,
    showAudioOutputSettings: Boolean, onShowAudioOutputSettings: (Boolean) -> Unit,
    onExportBackup: () -> Unit, onImportBackup: () -> Unit,
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
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val compactLayout = screenHeightDp < 760
    val artworkFraction = when {
        screenHeightDp < 700 -> 0.66f
        screenHeightDp < 800 -> 0.76f
        else -> 0.86f
    }
    var showLibrary by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Lagu") }
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Judul A–Z") }
    var slider by remember(position) { mutableFloatStateOf(position.toFloat()) }

    val visibleTracks = tracks.withIndex().filter { (_, track) ->
        (!favoritesOnly || track.isFavorite) &&
            (tab != "Riwayat" || track.uri.toString() in recentTrackUris) &&
            (groupFilter == null || if (tab == "Album") track.album == groupFilter else if (tab == "Artis") track.artist == groupFilter else true) &&
            (query.isBlank() || track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true))
    }.let { list ->
        when (sortBy) {
            "Judul Z–A" -> list.sortedByDescending { it.value.title.lowercase() }
            "Artis" -> list.sortedBy { it.value.artist.lowercase() }
            "Album" -> list.sortedBy { it.value.album.lowercase() }
            "Riwayat" -> list.sortedBy { recentTrackUris.indexOf(it.value.uri.toString()).let { index -> if (index < 0) Int.MAX_VALUE else index } }
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
    if (showBackupDialog) {
        BackupDialog(status = backupStatus, onExport = onExportBackup, onImport = onImportBackup,
            onDismiss = { onShowBackupDialog(false) })
    }
    if (showAudioOutputSettings) {
        AudioOutputSettingsDialog(onDismiss = { onShowAudioOutputSettings(false) })
    }
    if (showEqualizer) {
        EqualizerDialog(
            levels = eqLevels, enabled = eqEnabled, bandCount = eqBandCount,
            onDismiss = { onShowEqualizer(false) }, onEnabledChange = onEqToggle,
            onBandCountChange = onEqBandCount, onBandChange = onEqChange,
            onPreset = onEqPreset, onParametricApply = onParametricApply
        )
    }
    if (showLibrary) {
    Column(Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF101B2B), Color(0xFF080C14), Background))
    ).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Ice, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SHYNTAX", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text(" AUDIO", color = Ice, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
                Text("MUSIC LIBRARY", color = MutedText, fontSize = 10.sp, letterSpacing = 1.6.sp)
            }
            IconButton(onClick = { showLibrary = false }) {
                Icon(Icons.Default.Album, "Kembali ke Now Playing", tint = Ice)
            }
            IconButton(onClick = { onShowAudioOutputSettings(true) }) {
                Icon(Icons.Default.Headphones, "Pengaturan output audio", tint = Ice)
            }
            IconButton(onClick = { onShowBackupDialog(true) }) {
                Icon(Icons.Default.Settings, "Backup dan pengaturan", tint = Ice)
            }
            Surface(color = PanelRaised, shape = RoundedCornerShape(50)) {
                Text("v${BuildConfig.VERSION_NAME}", color = Ice, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(scanStatus, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = onScan) { Icon(Icons.Default.Refresh, null, tint = Ice); Spacer(Modifier.width(4.dp)); Text("Pindai") }
        }
        Spacer(Modifier.height(8.dp))
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
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("Lagu", "Album", "Artis", "Riwayat").forEach {
                FilterChip(
                    selected = tab == it,
                    onClick = { tab = it; groupFilter = null; favoritesOnly = false },
                    label = { Text(it) }
                )
            }
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly; groupFilter = null },
                label = {
                    Icon(Icons.Default.Favorite, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Favorit")
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Urutkan", tint = Ice) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Judul A–Z", "Judul Z–A", "Artis", "Album", "Riwayat").forEach { option ->
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
            IconButton(onClick = onImport) {
                Icon(Icons.Default.Add, "Tambah lagu", tint = Ice)
            }
        }
        Text(
            when {
                favoritesOnly -> "LAGU FAVORIT"
                groupFilter != null -> groupFilter!!.uppercase()
                tab == "Riwayat" -> "TERAKHIR DIPUTAR"
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
        } else if (tab in listOf("Album", "Artis") && groupFilter == null && !favoritesOnly) {
            val groups = if (tab == "Album") tracks.groupBy { it.album } else tracks.groupBy { it.artist }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups.keys.sorted().chunked(2)) { rowNames ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowNames.forEach { name ->
                            val groupTracks = groups[name].orEmpty()
                            Surface(color = Color(0xFF121E2D), shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).clickable { groupFilter = name }) {
                                Column(Modifier.padding(10.dp)) {
                                    val coverUri = groupTracks.firstOrNull()?.uri
                                    if (coverUri != null && tab == "Album") {
                                        TrackArtwork(coverUri, Modifier.fillMaxWidth().aspectRatio(1f))
                                    } else {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF243D57), Color(0xFF172638), Color(0xFF263B55))),
                                            RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Person, null, tint = Ice, modifier = Modifier.size(48.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(9.dp))
                                    Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${groupTracks.size} lagu", color = MutedText, fontSize = 11.sp)
                                }
                            }
                        }
                        if (rowNames.size == 1) Spacer(Modifier.weight(1f))
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
                    Surface(color = Color(0xFF111C2A), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackArtwork(track.uri, Modifier.size(48.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f).clickable { onSelect(indexed.index) }.padding(vertical = 5.dp)) {
                                Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${track.artist}  •  ${track.album}", color = MutedText, fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { onFavorite(indexed.index) }) {
                                Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit", tint = if (track.isFavorite) Ice else Color.LightGray)
                            }
                            IconButton(onClick = { onSelect(indexed.index) }) { Icon(Icons.Default.PlayArrow, "Putar", tint = Ice) }
                        }
                    }
                }
                if (visibleTracks.isEmpty()) item {
                    Text(if (tab == "Riwayat") "Belum ada riwayat pemutaran." else "Tidak ada lagu yang cocok.", color = Color.LightGray, modifier = Modifier.padding(18.dp))
                }
            }
        }
        Surface(color = Color(0xFF172435), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SEDANG DIPUTAR", color = MutedText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    FilledIconButton(onClick = onPlayPause) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar/Jeda")
                    }
                }
                Slider(value = if (duration > 0) slider.coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onSeek(slider.toLong()) },
                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(Icons.Default.Shuffle, "Acak", tint = if (shuffleEnabled) Ice else Color.LightGray)
                    }
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White) }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White) }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Ulang", tint = if (repeatMode != Player.REPEAT_MODE_OFF) Ice else Color.LightGray)
                    }
                }
            }
        }
        ShyntaxBottomBar(
            selected = "Pustaka",
            onPlayer = { showLibrary = false },
            onLibrary = {},
            onLyrics = { onShowLyricsControls(true) },
            onAudio = { onShowAudioOutputSettings(true) }
        )
    }
    } else {
        val activeIndex = playingTrackIndex.takeIf { it in tracks.indices } ?: -1
        val activeTrack = tracks.getOrNull(activeIndex)
        var sliderNow by remember(position) { mutableFloatStateOf(position.toFloat()) }
        fun timeLabel(ms: Long): String {
            val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
            return "%02d:%02d".format(total / 60, total % 60)
        }
        Column(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF18233A), Color(0xFF0D1321), Background)
                )
            ).padding(horizontal = if (compactLayout) 16.dp else 22.dp, vertical = if (compactLayout) 7.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Ice, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("SHYNTAX", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                        Text(" AUDIO", color = Ice, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Text("NOW PLAYING  •  HI-RES AUDIO", color = MutedText, fontSize = 9.sp, letterSpacing = 1.3.sp)
                }
                Surface(color = PanelRaised, shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (playing) Color(0xFF5BE0A0) else MutedText, RoundedCornerShape(50)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (playing) "PLAYING" else "READY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Pustaka Musik") }, onClick = { menuExpanded = false; showLibrary = true })
                        DropdownMenuItem(text = { Text("Pengaturan Output Audio") }, onClick = { menuExpanded = false; onShowAudioOutputSettings(true) })
                        DropdownMenuItem(text = { Text("Visualizer") }, onClick = { menuExpanded = false; onShowVisualizer(true) })
                        DropdownMenuItem(text = { Text("Equalizer") }, onClick = { menuExpanded = false; onShowEqualizer(true) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = Color(0xFF101A28), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HighQuality, contentDescription = "Format sumber", tint = Ice, modifier = Modifier.size(23.dp))
                    Column(Modifier.weight(1f)) {
                        Text("FORMAT SUMBER", color = MutedText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text(audioFileInfo.sampleRate?.let { "${it / 1000.0} kHz" } ?: "Belum terdeteksi",
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).height(30.dp).background(PanelRaised))
                    Column(Modifier.weight(1f)) {
                        Text("OUTPUT AUDIO", color = MutedText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text(audioOutputs.firstOrNull()?.name ?: "Android AudioTrack", color = Ice, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(artworkFraction).aspectRatio(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF342D5C),
                shadowElevation = 22.dp
            ) {
                if (artwork != null) {
                    Image(artwork.asImageBitmap(), contentDescription = "Sampul album",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(0xFF573B73), Color(0xFFFF855D), Color(0xFF294E7C))
                        )
                    )) {
                        Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopCenter).background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x665D3C78), Color.Transparent))
                        ))
                        Box(Modifier.size(92.dp).align(Alignment.CenterStart).offset(x = 38.dp, y = 28.dp)
                            .background(Color(0xFF34244F), RoundedCornerShape(48.dp)))
                        Box(Modifier.fillMaxWidth(0.82f).height(125.dp).align(Alignment.BottomEnd)
                            .offset(x = 28.dp, y = 12.dp).background(Color(0xFF49366E), RoundedCornerShape(topStart = 100.dp)))
                        Box(Modifier.fillMaxWidth(0.72f).height(70.dp).align(Alignment.BottomStart)
                            .offset(x = (-24).dp, y = 24.dp).background(Color(0xFF263F70), RoundedCornerShape(topEnd = 100.dp)))
                        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.Center).size(76.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(activeTrack?.title ?: "Belum ada lagu", color = Color.White, fontSize = if (compactLayout) 18.sp else 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(activeTrack?.artist ?: "Pilih lagu dari pustaka", color = MutedText, fontSize = 13.sp, maxLines = 1)
                    activeTrack?.album?.takeIf { it.isNotBlank() && it != "Unknown" }?.let {
                        Text(it, color = MutedText, fontSize = 11.sp, maxLines = 1)
                    }
                }
                IconButton(onClick = { if (activeIndex in tracks.indices) onFavorite(activeIndex) }) {
                    Icon(if (activeTrack?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favorit", tint = if (activeTrack?.isFavorite == true) Color(0xFFFF8DAB) else Color.LightGray,
                        modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = if (duration > 0) sliderNow.coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { sliderNow = it },
                onValueChangeFinished = { onSeek(sliderNow.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFB9EDFF), activeTrackColor = Color(0xFFB9EDFF),
                    inactiveTrackColor = Color(0xFF425363)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(position), color = Color(0xFF9EADBC), fontSize = 12.sp)
                Text(timeLabel(duration), color = Color(0xFF9EADBC), fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSetAbStart, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(if (abRepeatStart == null) "A • SET" else "A • ${timeLabel(abRepeatStart!!)}", color = Ice, fontSize = 10.sp) }
                TextButton(onClick = onSetAbEnd, enabled = abRepeatStart != null && position > (abRepeatStart ?: Long.MAX_VALUE), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(if (abRepeatEnd == null) "B • SET" else "B • ${timeLabel(abRepeatEnd!!)}", color = Ice, fontSize = 10.sp) }
                Text(if (abRepeatStart != null && abRepeatEnd != null) "A–B REPEAT ON" else "A–B REPEAT", color = if (abRepeatEnd != null) Ice else MutedText, fontSize = 9.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearAbRepeat, enabled = abRepeatStart != null || abRepeatEnd != null, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("Hapus", color = Color(0xFFFF9E9E), fontSize = 10.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(Icons.Default.Shuffle, "Acak", tint = if (shuffleEnabled) Ice else Color.Gray, modifier = Modifier.size(27.dp))
                    }
                    Text("Acak", color = Color.Gray, fontSize = 11.sp)
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE2F7FF), contentColor = Color(0xFF168AC1))) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar/Jeda", modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onCycleRepeat) {
                        Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Ulang", tint = if (repeatMode != Player.REPEAT_MODE_OFF) Ice else Color.Gray, modifier = Modifier.size(27.dp))
                    }
                    Text(when (repeatMode) { Player.REPEAT_MODE_ONE -> "Satu"; Player.REPEAT_MODE_ALL -> "Semua"; else -> "Ulang" }, color = Color.Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("EQ", Icons.Default.Tune) { onShowEqualizer(true) },
                    Triple("DSP", Icons.Default.GraphicEq) { onShowDsp(true) },
                    Triple("VISUAL", Icons.Default.Waves) { onShowVisualizer(true) },
                    Triple("ANALYZER", Icons.Default.Analytics) { onShowHiResDialog(true); onRefreshAudioInfo() },
                    Triple("LYRICS", Icons.Default.Lyrics) { onShowLyricsControls(true) },
                    Triple("OUTPUT", Icons.Default.Headphones) { onShowAudioOutputSettings(true) }
                ).forEach { item ->
                    Surface(
                        modifier = Modifier.width(66.dp).clickable { item.third() },
                        color = Panel, shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(item.second, contentDescription = item.first, tint = Ice, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(item.first, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f, fill = true))
            ShyntaxBottomBar(
                selected = "Pemutar",
                onPlayer = {},
                onLibrary = { showLibrary = true },
                onLyrics = { onShowLyricsControls(true) },
                onAudio = { onShowAudioOutputSettings(true) }
            )
        }
    }
}



@Composable
private fun TrackArtwork(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    Surface(modifier = modifier, color = Color(0xFF1C3045), shape = RoundedCornerShape(9.dp)) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Sampul album", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF24435B), Color(0xFF182638)))
            ), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = Ice, modifier = Modifier.size(25.dp))
            }
        }
    }
}

@Composable
private fun ShyntaxBottomBar(
    selected: String,
    onPlayer: () -> Unit,
    onLibrary: () -> Unit,
    onLyrics: () -> Unit,
    onAudio: () -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF0E1724),
        contentColor = Color(0xFF62D9FF),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        NavigationBarItem(
            selected = selected == "Pemutar",
            onClick = onPlayer,
            icon = { Icon(Icons.Default.Home, contentDescription = "Pemutar") },
            label = { Text("Putar") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF07131E),
                selectedTextColor = Color(0xFF62D9FF),
                indicatorColor = Color(0xFF62D9FF),
                unselectedIconColor = Color(0xFF91A5B8),
                unselectedTextColor = Color(0xFF91A5B8)
            )
        )
        NavigationBarItem(
            selected = selected == "Pustaka",
            onClick = onLibrary,
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Pustaka") },
            label = { Text("Pustaka") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF07131E),
                selectedTextColor = Color(0xFF69D8FF),
                indicatorColor = Color(0xFF69D8FF),
                unselectedIconColor = Color(0xFF91A5B8),
                unselectedTextColor = Color(0xFF91A5B8)
            )
        )
        NavigationBarItem(
            selected = selected == "Lirik",
            onClick = onLyrics,
            icon = { Icon(Icons.Default.Lyrics, contentDescription = "Lirik") },
            label = { Text("Lirik") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF07131E),
                selectedTextColor = Color(0xFF69D8FF),
                indicatorColor = Color(0xFF69D8FF),
                unselectedIconColor = Color(0xFF91A5B8),
                unselectedTextColor = Color(0xFF91A5B8)
            )
        )
        NavigationBarItem(
            selected = selected == "Audio",
            onClick = onAudio,
            icon = { Icon(Icons.Default.GraphicEq, contentDescription = "Audio") },
            label = { Text("Audio") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF07131E),
                selectedTextColor = Color(0xFF69D8FF),
                indicatorColor = Color(0xFF69D8FF),
                unselectedIconColor = Color(0xFF91A5B8),
                unselectedTextColor = Color(0xFF91A5B8)
            )
        )
    }
}

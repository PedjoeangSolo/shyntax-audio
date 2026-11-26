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
import android.util.LruCache
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
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
private val Background = Color(0xFF000000)
private val Panel = Color(0xFF0A0D11)
private val PanelRaised = Color(0xFF12181F)
private val MutedText = Color(0xFF93A8BC)
private const val PREFS = "shyntax_library"
private const val TRACK_URIS = "track_uris"
private val artworkBitmapCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isFavorite: Boolean = false,
    val folderPath: String = "",
    val folderName: String = ""
)

/** Folder (path + display name) containing the file at [filePath]; "" to "" when unknown. */
private fun folderOf(filePath: String?): Pair<String, String> {
    if (filePath.isNullOrBlank()) return "" to ""
    val parent = File(filePath).parentFile ?: return "" to ""
    return parent.absolutePath to (parent.name ?: "")
}

/** Same as [folderOf] but for a MediaStore RELATIVE_PATH value such as "Music/Rock/". */
private fun folderOfRelative(relativePath: String?): Pair<String, String> {
    val clean = relativePath?.trim('/')?.takeIf { it.isNotBlank() } ?: return "" to ""
    return clean to clean.substringAfterLast('/')
}

/** True for AIFF-family file names (.aif / .aiff / .aifc), whatever the stored MIME type. */
private fun isAiffFileName(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return lower.endsWith(".aif") || lower.endsWith(".aiff") || lower.endsWith(".aifc")
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val tracks = mutableStateListOf<Track>()
    private val playbackQueue = mutableStateListOf<Track>()
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
    private var limiterEnabled by mutableStateOf(false)
    private val eqLevels = mutableStateListOf<Float>().apply { repeat(31) { add(0f) } }
    private var eqEnabled by mutableStateOf(false)
    private var eqPreamp by mutableFloatStateOf(0f)
    private var eqBandCount by mutableIntStateOf(31)
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
    private val musicFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            getSharedPreferences("shyntax_music_folder", MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
            scanMediaLibrary(uri, isNewFolder = true)
        } else if (getSharedPreferences("shyntax_music_folder", MODE_PRIVATE).getString("tree_uri", null) == null) {
            scanStatus = "Pilih folder musik untuk mulai memindai."
        }
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
        limiterEnabled = getSharedPreferences("shyntax_dsp", MODE_PRIVATE).getBoolean("limiter_enabled", false)
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
                    val queueIndex = controller?.currentMediaItemIndex ?: 0
                    currentTrackIndex = playbackQueue.getOrNull(queueIndex)
                        ?.let { queueTrack -> tracks.indexOfFirst { it.uri == queueTrack.uri } }
                        ?.takeIf { it >= 0 } ?: queueIndex
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
            // Keep an existing MediaSession queue intact. Restore the last track only
            // when the service/player was recreated and its queue is empty.
            if (controller?.mediaItemCount == 0) restoreLastPlayback()
        }, MoreExecutors.directExecutor())

        lifecycleScope.launch {
            var lastPersistAt = 0L
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
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt >= 1500L) {
                        persistLastPlayback(player)
                        lastPersistAt = now
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
                    onImport = { picker.launch(arrayOf("audio/*", "audio/aiff", "audio/x-aiff", "audio/x-aif")) },
                    scanStatus = scanStatus,
                    onScan = { requestMusicScan() },
                    onChooseMusicFolder = { chooseMusicFolder() },
                    onSelect = { playTrack(it) },
                    onPlayQueue = { list, position -> playFromList(list, position) },
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
                    eqLevels = eqLevels.toList(), eqEnabled = eqEnabled, eqBandCount = eqBandCount, eqPreamp = eqPreamp,
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
                    limiterEnabled = limiterEnabled,
                    onLimiterChange = { enabled -> limiterEnabled = enabled; getSharedPreferences("shyntax_dsp", MODE_PRIVATE).edit().putBoolean("limiter_enabled", enabled).apply() },
                    onDspChange = { bass, treble, width, loudness, reverb ->
                        dspBass = bass; dspTreble = treble; dspWidth = width; dspLoudness = loudness; dspReverb = reverb
                        dspEngine?.apply(bass, width, loudness, reverb)
                        eqEngine?.applyTreble(treble)
                        if (treble != 0) { eqEnabled = true; eqEngine?.setEnabled(true); saveEqSettings() }
                        getSharedPreferences("shyntax_dsp", MODE_PRIVATE).edit()
                            .putInt("bass", bass).putInt("treble", treble).putInt("width", width)
                            .putInt("loudness", loudness).putString("reverb", reverb).apply()
                    },
                    onEqToggle = { enabled -> eqEnabled = enabled; eqEngine?.setEnabled(enabled); saveEqSettings() },
                    onEqBandCount = { count -> eqBandCount = count; eqEngine?.applyGraphic(eqLevels.toList(), count); eqEngine?.setPreamp(eqPreamp); eqEngine?.applyTreble(dspTreble); saveEqSettings() },
                    onEqPreampChange = { value -> eqPreamp = value.coerceIn(-12f, 12f); eqEngine?.setPreamp(eqPreamp); saveEqSettings() },
                    onEqBassBoostChange = { value ->
                        dspBass = value.coerceIn(0, 100)
                        dspEngine?.apply(dspBass, dspWidth, dspLoudness, dspReverb)
                        getSharedPreferences("shyntax_dsp", MODE_PRIVATE).edit().putInt("bass", dspBass).apply()
                    },
                    onEqCustomPresetApply = { values ->
                        values.take(31).forEachIndexed { i, v -> eqLevels[i] = v.coerceIn(-12f, 12f) }
                        eqEngine?.applyGraphic(eqLevels.toList(), eqBandCount)
                        eqEngine?.setPreamp(eqPreamp)
                        eqEngine?.applyTreble(dspTreble)
                        saveEqSettings()
                    },
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
                            "Pop" -> listOf(-1f,1f,3f,2f,0f,1f,2f,2f,1f,0f)
                            "Classical" -> listOf(4f,3f,2f,1f,-1f,-1f,0f,2f,3f,4f)
                            else -> List(10) { 0f }
                        }
                        for (i in 0 until 31) {
                            val scaled = i * 9f / 30f
                            val low = scaled.toInt().coerceIn(0, 9)
                            val high = (low + 1).coerceAtMost(9)
                            val fraction = scaled - low
                            eqLevels[i] = values[low] + (values[high] - values[low]) * fraction
                        }
                        eqEngine?.applyGraphic(eqLevels.toList(), eqBandCount)
                        eqEngine?.setPreamp(eqPreamp)
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
        val savedTree = getSharedPreferences("shyntax_music_folder", MODE_PRIVATE).getString("tree_uri", null)
        if (savedTree.isNullOrBlank()) {
            scanStatus = "Pilih folder musik untuk mulai memindai."
            musicFolderPicker.launch(null)
        } else scanMediaLibrary(Uri.parse(savedTree))
    }

    private fun scanMediaLibrary(treeUri: Uri, isNewFolder: Boolean = false) {
        scanStatus = "Memindai folder musik yang dipilih…"
        lifecycleScope.launch {
            val discovered = withContext(Dispatchers.IO) {
                val result = mutableListOf<Track>()
                val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: return@withContext result
                val supported = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aif", "aiff", "aifc", "alac")
                fun visit(directory: DocumentFile, relative: String, depth: Int) {
                    if (depth > 20) return
                    directory.listFiles().forEach { child ->
                        val name = child.name ?: return@forEach
                        if (child.isDirectory) {
                            visit(child, if (relative.isBlank()) name else "$relative/$name", depth + 1)
                        } else if (child.isFile && name.substringAfterLast('.', "").lowercase() in supported) {
                            val track = runCatching { readTrack(child.uri) }.getOrNull() ?: return@forEach
                            val folder = relative.ifBlank { root.name ?: "Musik" }
                            result.add(track.copy(folderPath = folder, folderName = folder.substringAfterLast('/')))
                        }
                    }
                }
                visit(root, "", 0)
                result.sortedBy { it.title.lowercase() }
            }
            val prefs = getSharedPreferences("shyntax_music_folder", MODE_PRIVATE)
            val oldScanned = prefs.getStringSet("scanned_track_uris", emptySet()).orEmpty()
            val discoveredUris = discovered.map { it.uri.toString() }.toSet()
            val favorites = tracks.associate { it.uri.toString() to it.isFavorite }
            val retained = tracks.filter { existing ->
                val key = existing.uri.toString()
                key !in oldScanned && !(isNewFolder && oldScanned.isEmpty() && key.startsWith("content://media/external/audio/media/"))
            }
            tracks.clear()
            tracks.addAll((retained + discovered).distinctBy { it.uri.toString() }
                .map { it.copy(isFavorite = favorites[it.uri.toString()] ?: it.isFavorite) })
            prefs.edit().putStringSet("scanned_track_uris", discoveredUris).apply()
            saveLibrary()
            scanStatus = if (discovered.isEmpty()) "Folder dipindai: tidak ada file audio yang didukung."
                else "Folder dipindai: ${discovered.size} lagu ditemukan."
            if (tracks.isNotEmpty() && controller?.mediaItemCount == 0) playTrack(0)
        }
    }

    private fun chooseMusicFolder() {
        val current = getSharedPreferences("shyntax_music_folder", MODE_PRIVATE).getString("tree_uri", null)
        musicFolderPicker.launch(current?.let { Uri.parse(it) })
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

    private fun persistLastPlayback(player: Player) {
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        getSharedPreferences("shyntax_playback_state", MODE_PRIVATE).edit()
            .putString("last_uri", uri)
            .putLong("last_position", player.currentPosition.coerceAtLeast(0L))
            .putBoolean("was_playing", player.playWhenReady && player.playbackState != Player.STATE_ENDED)
            .apply()
    }

    private fun restoreLastPlayback() {
        val prefs = getSharedPreferences("shyntax_playback_state", MODE_PRIVATE)
        val uriText = prefs.getString("last_uri", null) ?: return
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return
        val savedPosition = prefs.getLong("last_position", 0L).coerceAtLeast(0L)
        val shouldResume = prefs.getBoolean("was_playing", false)
        val track = tracks.firstOrNull { it.uri == uri }
        val metadata = MediaMetadata.Builder()
            .setTitle(track?.title ?: uri.lastPathSegment ?: "SHYNTAX AUDIO")
            .setArtist(track?.artist ?: "")
            .setAlbumTitle(track?.album ?: "")
            .build()
        val item = MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build()
        val player = controller ?: return
        player.setMediaItem(item, savedPosition)
        playbackQueue.clear()
        track?.let {
            playbackQueue.add(it)
            currentTrackIndex = tracks.indexOfFirst { saved -> saved.uri == uri }.coerceAtLeast(0)
            loadLyricsForCurrentTrack()
            loadArtwork(it.uri, it.album, it.artist)
            refreshAudioInfo(it.uri)
        }
        player.prepare()
        if (shouldResume) player.play() else player.pause()
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
            eqBandCount = 31
            eqPreamp = prefs.getFloat("preamp", 0f).coerceIn(-12f, 12f)
            prefs.edit().putInt("band_count", 31).apply()
            eqEngine?.setPreamp(eqPreamp)
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
            .putFloat("preamp", eqPreamp)
            .putString("levels", eqLevels.joinToString(","))
            .apply()
    }

    private fun loadArtwork(uri: Uri, album: String, artist: String) {
        currentArtwork = null
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val key = uri.toString()
                artworkBitmapCache.get(key)?.let { return@withContext it }
                val folder = File(cacheDir, "album_art").apply { mkdirs() }
                val cached = File(folder, "${key.hashCode()}.jpg")
                if (cached.isFile) {
                    val cachedBytes = runCatching { cached.readBytes() }.getOrDefault(byteArrayOf())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var sample = 1
                        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 512) sample *= 2
                        val decoded = BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                        if (decoded != null) { artworkBitmapCache.put(key, decoded); return@withContext decoded }
                    }
                }

                val embedded = runCatching {
                    MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(this@MainActivity, uri)
                            retriever.embeddedPicture?.let { bytes ->
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                                    var sample = 1
                                    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 512) sample *= 2
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                                }
                            }
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
                if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath)?.let { artworkBitmapCache.put(key, it); return@withContext it }

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
            if (bitmap != null) { artworkBitmapCache.put(uri.toString(), bitmap); runCatching { File(cacheDir, "album_art").listFiles()?.filter { it.isFile }?.let { files -> var total = files.sumOf { it.length() }; for (file in files.sortedBy { it.lastModified() }) { if (total <= 64L * 1024L * 1024L) break; val length = file.length(); if (file.delete()) total -= length } } } }
            if (tracks.getOrNull(currentTrackIndex)?.uri == uri) currentArtwork = bitmap
        }
    }

    private fun readTrack(uri: Uri): Track {
        val fileName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() } ?: "Audio"
        // Best effort: MediaStore URIs expose DATA; SAF document URIs often do not,
        // in which case the track simply lands in the "Tidak diketahui" folder group.
        val folder = folderOf(runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull())
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
            Track(uri, title, artist, album, length, folderPath = folder.first, folderName = folder.second)
        } catch (_: Exception) {
            Track(uri, fileName, "Artis tidak diketahui", "Album tidak diketahui", 0L, folderPath = folder.first, folderName = folder.second)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun restoreLibrary() {
        // SharedPreferences access is lightweight; metadata extraction is not.
        // Copy the URI set before leaving the main thread, then read tags on IO.
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(TRACK_URIS, emptySet())
            .orEmpty()
            .toList()

        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) {
                saved.mapNotNull { value ->
                    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@mapNotNull null
                    runCatching { readTrack(uri) }.getOrNull()
                }
            }

            // Merge instead of replacing: a user may import files while restore is running.
            val existingUris = tracks.mapTo(mutableSetOf()) { it.uri.toString() }
            restored.forEach { track ->
                if (existingUris.add(track.uri.toString())) tracks.add(track)
            }
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
        if (index !in tracks.indices) return
        playQueue(tracks.toList(), index, index)
    }

    /** Play [list] (e.g. the tracks of one folder) as the queue, starting at [positionInList]. */
    private fun playFromList(list: List<Track>, positionInList: Int) {
        if (positionInList !in list.indices) return
        val globalIndex = tracks.indexOfFirst { it.uri == list[positionInList].uri }
        playQueue(list, positionInList, globalIndex)
    }

    private fun playQueue(queue: List<Track>, startIndex: Int, globalIndex: Int) {
        val c = controller ?: return
        if (startIndex !in queue.indices) return
        playbackQueue.clear()
        playbackQueue.addAll(queue)
        currentTrackIndex = if (globalIndex in tracks.indices) globalIndex else startIndex
        val startTrack = queue[startIndex]
        recordPlayedTrack(startTrack.uri)
        loadLyricsForCurrentTrack()
        loadArtwork(startTrack.uri, startTrack.album, startTrack.artist)
        val items = queue.map { track ->
            MediaItem.Builder().setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build())
                .build()
        }
        c.setMediaItems(items, startIndex, 0L)
        refreshAudioInfo(startTrack.uri)
        c.prepare()
        c.play()
    }

    override fun onStop() {
        controller?.let { persistLastPlayback(it) }
        super.onStop()
    }

    override fun onDestroy() {
        controller?.let { persistLastPlayback(it) }
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
    onImport: () -> Unit, scanStatus: String, onScan: () -> Unit, onChooseMusicFolder: () -> Unit,
    onSelect: (Int) -> Unit, onPlayQueue: (List<Track>, Int) -> Unit, onFavorite: (Int) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    eqLevels: List<Float>, eqEnabled: Boolean, eqBandCount: Int, eqPreamp: Float, showEqualizer: Boolean,
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
    limiterEnabled: Boolean, onLimiterChange: (Boolean) -> Unit,
    onDspChange: (Int, Int, Int, Int, String) -> Unit,
    onShowEqualizer: (Boolean) -> Unit, onEqToggle: (Boolean) -> Unit, onEqBandCount: (Int) -> Unit,
    onEqPreampChange: (Float) -> Unit, onEqBassBoostChange: (Int) -> Unit,
    onEqCustomPresetApply: (List<Float>) -> Unit,
    onEqChange: (Int, Float) -> Unit, onEqPreset: (String) -> Unit,
    onParametricApply: (List<Float>, List<Float>, List<Float>) -> Unit
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val compactLayout = screenHeightDp < 760
    val artworkFraction = when {
        screenHeightDp < 700 -> 0.60f
        screenHeightDp < 800 -> 0.70f
        else -> 0.80f
    }
    var showLibrary by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Lagu") }
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Judul A–Z") }
    var showSettings by remember { mutableStateOf(false) }
    var slider by remember(position) { mutableFloatStateOf(position.toFloat()) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepTimerEndMs by remember { mutableLongStateOf(0L) }
    val currentPlaying by rememberUpdatedState(playing)
    val currentOnPlayPause by rememberUpdatedState(onPlayPause)
    LaunchedEffect(sleepTimerEndMs) {
        if (sleepTimerEndMs > 0L) {
            delay((sleepTimerEndMs - System.currentTimeMillis()).coerceAtLeast(0L))
            if (sleepTimerEndMs > 0L && currentPlaying) currentOnPlayPause()
            sleepTimerEndMs = 0L
        }
    }

    val visibleTracks = tracks.withIndex().filter { (_, track) ->
        (!favoritesOnly || track.isFavorite) &&
            (tab != "Riwayat" || track.uri.toString() in recentTrackUris) &&
            (groupFilter == null || when (tab) {
                "Album" -> track.album == groupFilter
                "Artis" -> track.artist == groupFilter
                "Folder" -> track.folderPath == groupFilter
                else -> true
            }) &&
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
    val selectedFolderName = if (tab == "Folder" && groupFilter != null)
        tracks.firstOrNull { it.folderPath == groupFilter }?.folderName?.ifBlank { "Tidak diketahui" } ?: "Tidak diketahui"
    else ""

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
            preamp = eqPreamp, bassBoost = dspBass, limiterEnabled = limiterEnabled, onLimiterChange = onLimiterChange,
            onPreampChange = onEqPreampChange, onBassBoostChange = onEqBassBoostChange,
            onPreset = onEqPreset, onCustomPresetApply = onEqCustomPresetApply, onParametricApply = onParametricApply
        )
    }
    if (showSettings) {
        ShyntaxSettingsDialog(
            versionName = BuildConfig.VERSION_NAME,
            onDismiss = { showSettings = false },
            onOpenLibrary = { showSettings = false; showLibrary = true },
            onSleepTimer = { showSettings = false; showSleepTimer = true },
            onOpenEqualizer = { showSettings = false; onShowEqualizer(true) },
            onOpenDsp = { showSettings = false; onShowDsp(true) },
            onOpenVisualizer = { showSettings = false; onShowVisualizer(true) },
            onOpenAudioOutput = { showSettings = false; onShowAudioOutputSettings(true) },
            onOpenHiRes = { showSettings = false; onShowHiResDialog(true); onRefreshAudioInfo() },
            onOpenLyrics = { showSettings = false; onShowLyricsControls(true) },
            onScan = { showSettings = false; onScan() },
            onChooseMusicFolder = { showSettings = false; onChooseMusicFolder() },
            onImport = { showSettings = false; onImport() },
            onOpenBackup = { showSettings = false; onShowBackupDialog(true) }
        )
    }
    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            containerColor = Panel,
            title = { Text("Timer Tidur", color = Color.White) },
            text = {
                Column {
                    Text(
                        if (sleepTimerEndMs > 0L) "Timer sedang aktif. Pemutaran akan berhenti otomatis saat waktu habis."
                        else "Pilih durasi. Pemutaran akan berhenti otomatis saat waktu habis.",
                        color = MutedText, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            sleepTimerEndMs = System.currentTimeMillis() + minutes * 60_000L
                            showSleepTimer = false
                        }) { Text("$minutes menit", color = Ice) }
                    }
                    if (sleepTimerEndMs > 0L) {
                        TextButton(onClick = { sleepTimerEndMs = 0L; showSleepTimer = false }) {
                            Text("Matikan timer", color = Color(0xFFFFA3A3))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimer = false }) { Text("Tutup", color = Ice) }
            }
        )
    }
    if (showLibrary) {
    Column(Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0B0F14), Color(0xFF000000), Background))
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
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, "Pengaturan", tint = Ice)
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
                focusedBorderColor = Ice, unfocusedBorderColor = Color(0xFF2A343E),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("Lagu", "Album", "Artis", "Folder", "Riwayat").forEach {
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
                tab == "Folder" && groupFilter != null -> selectedFolderName.uppercase()
                groupFilter != null -> groupFilter!!.uppercase()
                tab == "Riwayat" -> "TERAKHIR DIPUTAR"
                else -> when (tab) { "Album" -> "ALBUM"; "Artis" -> "ARTIS"; "Folder" -> "FOLDER"; else -> "SEMUA LAGU" }
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
        } else if (tab in listOf("Album", "Artis", "Folder") && groupFilter == null && !favoritesOnly) {
            val groups = when (tab) {
                "Album" -> tracks.groupBy { it.album }
                "Folder" -> tracks.groupBy { it.folderPath }
                else -> tracks.groupBy { it.artist }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups.keys.sorted().chunked(2)) { rowNames ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowNames.forEach { name ->
                            val groupTracks = groups[name].orEmpty()
                            Surface(color = Color(0xFF10151C), shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).clickable { groupFilter = name }) {
                                Column(Modifier.padding(10.dp)) {
                                    val coverUri = groupTracks.firstOrNull()?.uri
                                    if (coverUri != null && tab == "Album") {
                                        TrackArtwork(coverUri, Modifier.fillMaxWidth().aspectRatio(1f))
                                    } else {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF14425C), Color(0xFF0A0D11), Color(0xFF123240))),
                                            RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                            Icon(if (tab == "Folder") Icons.Default.Folder else Icons.Default.Person, null, tint = Ice, modifier = Modifier.size(48.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(9.dp))
                                    Text(
                                        if (tab == "Folder") groupTracks.firstOrNull()?.folderName?.ifBlank { "Tidak diketahui" } ?: "Tidak diketahui" else name,
                                        color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                    Text("${groupTracks.size} lagu", color = MutedText, fontSize = 11.sp)
                                }
                            }
                        }
                        if (rowNames.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            // Inside a folder, playback queues exactly the displayed folder list;
            // everywhere else the historical whole-library behaviour is kept.
            val playIndexed: (IndexedValue<Track>) -> Unit = { indexed ->
                if (tab == "Folder" && groupFilter != null) {
                    val positionInFolder = visibleTracks.indexOfFirst { it.value.uri == indexed.value.uri }
                    if (positionInFolder >= 0) onPlayQueue(visibleTracks.map { it.value }, positionInFolder)
                    else onSelect(indexed.index)
                } else onSelect(indexed.index)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groupFilter != null) item {
                    TextButton(onClick = { groupFilter = null }) { Icon(Icons.Default.ArrowBack, null); Text("Kembali ke ${tab.lowercase()}") }
                }
                items(visibleTracks, key = { it.value.uri.toString() }) { indexed ->
                    val track = indexed.value
                    val isActiveTrack = indexed.index == playingTrackIndex
                    Surface(color = if (isActiveTrack) PanelRaised else Color(0xFF0A0D11), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackArtwork(track.uri, Modifier.size(48.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f).clickable { playIndexed(indexed) }.padding(vertical = 5.dp)) {
                                Text(track.title, color = if (isActiveTrack) Ice else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${track.artist}  •  ${formatDurationMs(track.durationMs)}", color = MutedText, fontSize = 11.sp, maxLines = 1)
                            }
                            if (isActiveTrack) {
                                Icon(Icons.Default.GraphicEq, "Sedang diputar", tint = Ice, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(2.dp))
                            }
                            IconButton(onClick = { onFavorite(indexed.index) }) {
                                Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit", tint = if (track.isFavorite) Ice else Color.LightGray)
                            }
                            IconButton(onClick = { playIndexed(indexed) }) { Icon(Icons.Default.PlayArrow, "Putar", tint = Ice) }
                        }
                    }
                }
                if (visibleTracks.isEmpty()) item {
                    Text(if (tab == "Riwayat") "Belum ada riwayat pemutaran." else "Tidak ada lagu yang cocok.", color = Color.LightGray, modifier = Modifier.padding(18.dp))
                }
            }
        }
        Surface(color = Color(0xFF12181F), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
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
        var dynamicBackground by remember { mutableStateOf(true) }
        fun timeLabel(ms: Long): String {
            val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
            return "%02d:%02d".format(total / 60, total % 60)
        }
        val waveform = visualizerWave.takeIf { it.isNotEmpty() } ?: List(40) { i -> 0.16f + ((i * 17 + 9) % 23) / 30f }
        Box(Modifier.fillMaxSize().background(Background)) {
            if (dynamicBackground && artwork != null) {
                Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize().blur(42.dp), contentScale = ContentScale.Crop, alpha = 0.38f)
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, null, tint = Ice, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("SHYNTAX", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                            Text(" AUDIO", color = Ice, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        }
                        Text("NOW PLAYING  •  HI-RES", color = MutedText, fontSize = 9.sp, letterSpacing = 1.3.sp)
                    }
                    IconButton(onClick = { dynamicBackground = !dynamicBackground }) {
                        Icon(if (dynamicBackground) Icons.Default.ColorLens else Icons.Default.DarkMode, "Ganti tampilan latar", tint = Ice)
                    }
                    Box {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Pustaka Musik") }, onClick = { menuExpanded = false; showLibrary = true })
                            DropdownMenuItem(text = { Text("Pengaturan Output Audio") }, onClick = { menuExpanded = false; onShowAudioOutputSettings(true) })
                            DropdownMenuItem(text = { Text("Visualizer") }, onClick = { menuExpanded = false; onShowVisualizer(true) })
                            DropdownMenuItem(text = { Text("Equalizer") }, onClick = { menuExpanded = false; onShowEqualizer(true) })
                            DropdownMenuItem(text = { Text("Analyzer Hi-Res") }, onClick = { menuExpanded = false; onShowHiResDialog(true); onRefreshAudioInfo() })
                            DropdownMenuItem(text = { Text("Pengaturan") }, onClick = { menuExpanded = false; showSettings = true })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(0.76f).aspectRatio(1f),
                    shape = RoundedCornerShape(18.dp), color = PanelRaised, shadowElevation = 18.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    if (artwork != null) Image(artwork.asImageBitmap(), "Sampul album", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF14425C), Color(0xFF0E1A24), Color(0xFF123240)))
                    ), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = Ice, modifier = Modifier.size(76.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0x990A0D11), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(activeTrack?.title ?: "Belum ada lagu", color = Color.White, fontSize = if (compactLayout) 17.sp else 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(activeTrack?.artist ?: "Pilih lagu dari pustaka", color = MutedText, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { if (activeIndex in tracks.indices) onFavorite(activeIndex) }) {
                            Icon(if (activeTrack?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                "Favorit", tint = if (activeTrack?.isFavorite == true) Ice else Color.LightGray)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        Triple("EQUALIZER", Icons.Default.Tune) { onShowEqualizer(true) },
                        Triple("DAFTAR", Icons.Default.QueueMusic) { showLibrary = true },
                        Triple("LIRIK", Icons.Default.Lyrics) { onShowLyricsControls(true) },
                        Triple("TIMER", Icons.Default.Timer) { showSleepTimer = true },
                        Triple("VISUAL", Icons.Default.Waves) { onShowVisualizer(true) }
                    ).forEachIndexed { index, item ->
                        val active = index == 3 && sleepTimerEndMs > 0L
                        Surface(
                            modifier = Modifier.weight(1f).clickable { item.third() },
                            color = if (active) Color(0xFF123240) else Color(0xAA12181F),
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
                        ) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.second, null, tint = if (active) Ice else Color.LightGray, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(item.first, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    waveform.take(44).forEachIndexed { index, sample ->
                        val h = (8f + sample.coerceIn(0f, 1f) * 31f).dp
                        Box(Modifier.weight(1f).height(h).background(
                            if (duration > 0 && index.toFloat() / 44f <= position.toFloat() / duration.toFloat()) Ice else Color(0xFF55626E),
                            RoundedCornerShape(4.dp)
                        ))
                    }
                }
                Slider(
                    value = if (duration > 0) sliderNow.coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = { sliderNow = it }, onValueChangeFinished = { onSeek(sliderNow.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ice, inactiveTrackColor = Color(0xFF3A4652))
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(color = Color(0xAA000000), shape = RoundedCornerShape(50)) {
                        Text(timeLabel(position), color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                    Surface(color = Color(0xAA000000), shape = RoundedCornerShape(50)) {
                        Text(timeLabel(duration), color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onSetAbStart, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(if (abRepeatStart == null) "A SET" else "A ${timeLabel(abRepeatStart!!)}", color = Ice, fontSize = 9.sp)
                    }
                    TextButton(onClick = onSetAbEnd, enabled = abRepeatStart != null && position > (abRepeatStart ?: Long.MAX_VALUE), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(if (abRepeatEnd == null) "B SET" else "B ${timeLabel(abRepeatEnd!!)}", color = Ice, fontSize = 9.sp)
                    }
                    TextButton(onClick = onClearAbRepeat, enabled = abRepeatStart != null || abRepeatEnd != null, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("RESET A–B", color = Color(0xFFFFA3A3), fontSize = 9.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Shuffle, "Acak", tint = if (shuffleEnabled) Ice else Color.LightGray, modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(74.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5F8FF), contentColor = Color(0xFF0B141B))) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar/Jeda", modifier = Modifier.size(42.dp))
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = onCycleRepeat, modifier = Modifier.size(48.dp)) {
                        Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Ulang", tint = if (repeatMode != Player.REPEAT_MODE_OFF) Ice else Color.LightGray, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(7.dp))
                Surface(color = Color(0xAA0A0D11), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SUMBER", color = MutedText, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(audioFileInfo.sampleRate?.let { "${it / 1000.0} kHz" } ?: "—", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.width(1.dp).height(26.dp).background(PanelRaised))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("OUTPUT", color = MutedText, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(audioOutputs.firstOrNull()?.name ?: "Android AudioTrack", color = Ice, fontSize = 10.sp, maxLines = 1)
                        }
                        IconButton(onClick = { onShowHiResDialog(true); onRefreshAudioInfo() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Info, "Detail audio", tint = Ice, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f, fill = true))
                ShyntaxBottomBar(selected = "Pemutar", onPlayer = {}, onLibrary = { showLibrary = true },
                    onLyrics = { onShowLyricsControls(true) }, onAudio = { onShowAudioOutputSettings(true) })
            }
        }
    }}



@Composable
private fun ShyntaxSettingsDialog(
    versionName: String,
    onDismiss: () -> Unit,
    onOpenLibrary: () -> Unit,
    onSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenDsp: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    onOpenHiRes: () -> Unit,
    onOpenLyrics: () -> Unit,
    onScan: () -> Unit,
    onChooseMusicFolder: () -> Unit,
    onImport: () -> Unit,
    onOpenBackup: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SHYNTAX", color = Ice, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Pengaturan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Pemutaran, audio, pustaka, dan cadangan", color = MutedText, fontSize = 11.sp)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsSection("PEMUTARAN") {
                    SettingsRow(Icons.Default.QueueMusic, "Pustaka & Daftar Putar", "Jelajahi lagu, album, artis, folder, dan riwayat", onOpenLibrary)
                    SettingsRow(Icons.Default.Timer, "Timer Tidur", "Hentikan pemutaran secara otomatis", onSleepTimer)
                }
                SettingsSection("AUDIO") {
                    SettingsRow(Icons.Default.Tune, "Equalizer", "EQ grafis 31 band, parametrik, dan preset", onOpenEqualizer)
                    SettingsRow(Icons.Default.GraphicEq, "DSP Lanjutan", "Bass, treble, stereo width, dan reverb", onOpenDsp)
                    SettingsRow(Icons.Default.Waves, "Visualizer", "Waveform, spektrum, sirkular, dan partikel", onOpenVisualizer)
                    SettingsRow(Icons.Default.Headphones, "Output Audio", "Driver, resampler, dan format sampel", onOpenAudioOutput)
                    SettingsRow(Icons.Default.Info, "Analyzer Hi-Res", "Info file, analisis PCM, perangkat output", onOpenHiRes)
                }
                SettingsSection("PUSTAKA") {
                    SettingsRow(Icons.Default.FolderOpen, "Pilih Folder Musik", "Tentukan folder dan subfolder yang boleh dipindai", onChooseMusicFolder)
                    SettingsRow(Icons.Default.Refresh, "Pindai Ulang", "Pindai ulang folder musik yang dipilih", onScan)
                    SettingsRow(Icons.Default.Add, "Tambah Lagu", "Impor file audio secara manual", onImport)
                }
                SettingsSection("LIRIK") {
                    SettingsRow(Icons.Default.Lyrics, "Lirik, Tempo & Pitch", "Lirik tersinkron, kecepatan, dan nada", onOpenLyrics)
                }
                SettingsSection("CADANGAN") {
                    SettingsRow(Icons.Default.Settings, "Backup & Pemulihan", "Ekspor dan impor pengaturan (ZIP)", onOpenBackup)
                }
                SettingsSection("TENTANG") {
                    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = Ice, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text("SHYNTAX AUDIO", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Pemutar musik lokal berbasis Media3", color = MutedText, fontSize = 10.sp)
                            }
                            Text("v$versionName", color = Ice, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Ice) } }
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Ice, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        content()
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Ice, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedText, fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MutedText, modifier = Modifier.size(18.dp))
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
                retriever.embeddedPicture?.let { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sampleSize = maxOf(1, maxOf(bounds.outWidth / 512, bounds.outHeight / 512))
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { artworkBitmapCache.put(uri.toString(), it) }
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    Surface(modifier = modifier, color = Color(0xFF12181F), shape = RoundedCornerShape(9.dp)) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Sampul album", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF14425C), Color(0xFF0A0D11)))
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
        containerColor = Color(0xFF05080C),
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
                selectedTextColor = Color(0xFF62D9FF),
                indicatorColor = Color(0xFF62D9FF),
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
                selectedTextColor = Color(0xFF62D9FF),
                indicatorColor = Color(0xFF62D9FF),
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
                selectedTextColor = Color(0xFF62D9FF),
                indicatorColor = Color(0xFF62D9FF),
                unselectedIconColor = Color(0xFF91A5B8),
                unselectedTextColor = Color(0xFF91A5B8)
            )
        )
    }
}

package com.shyntax.audio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.compose.ui.unit.dp

object AppBackupManager {
    private const val ENTRY = "shyntax-backup.json"
    private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
    private val preferenceFiles = listOf(
        "shyntax_library", "shyntax_lyrics", "shyntax_playback",
        "shyntax_visualizer", "shyntax_dsp", "shyntax_eq"
    )

    fun export(context: Context, uri: Uri): Result<Unit> = runCatching {
        val root = JSONObject()
            .put("app", "SHYNTAX AUDIO")
            .put("schemaVersion", 1)
            .put("createdAt", System.currentTimeMillis())
        val allPrefs = JSONObject()
        preferenceFiles.forEach { fileName ->
            val source = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).all
            val values = JSONObject()
            source.forEach { (key, value) ->
                val encoded = when (value) {
                    is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                    is Int -> JSONObject().put("type", "int").put("value", value)
                    is Long -> JSONObject().put("type", "long").put("value", value)
                    is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                    is String -> JSONObject().put("type", "string").put("value", value)
                    is Set<*> -> JSONObject().put("type", "string_set")
                        .put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                    else -> null
                }
                if (encoded != null) values.put(key, encoded)
            }
            allPrefs.put(fileName, values)
        }
        root.put("preferences", allPrefs)
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "Ukuran data backup melebihi batas aman." }
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Tidak dapat membuka lokasi penyimpanan.")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    fun restore(context: Context, uri: Uri): Result<Unit> = runCatching {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Tidak dapat membaca file backup.")
        val bytes = ZipInputStream(input).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == ENTRY) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BACKUP_BYTES) { "File backup terlalu besar." }
                        out.write(buffer, 0, count)
                    }
                    found = out.toByteArray()
                    break
                }
            }
            found ?: error("Isi backup SHYNTAX tidak ditemukan.")
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("app") == "SHYNTAX AUDIO") { "File bukan backup SHYNTAX AUDIO." }
        require(root.optInt("schemaVersion", -1) == 1) { "Versi backup tidak didukung." }
        val prefs = root.optJSONObject("preferences") ?: error("Data pengaturan tidak ditemukan.")

        // Validate every value before changing any local preferences.
        val validated = mutableMapOf<String, Map<String, Any>>()
        preferenceFiles.forEach { fileName ->
            val values = prefs.optJSONObject(fileName) ?: JSONObject()
            val map = mutableMapOf<String, Any>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = values.optJSONObject(key) ?: error("Data pengaturan rusak.")
                val value: Any = when (item.optString("type")) {
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat()
                    "string" -> item.getString("value")
                    "string_set" -> {
                        val array = item.getJSONArray("value")
                        buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
                    }
                    else -> error("Tipe data backup tidak dikenal.")
                }
                map[key] = value
            }
            validated[fileName] = map
        }
        validated.forEach { (fileName, values) ->
            val editor = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Gagal menyimpan pengaturan $fileName." }
        }
    }
}

@androidx.compose.runtime.Composable
fun BackupDialog(
    status: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Backup & Pemulihan") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.material3.Text(
                    "Simpan atau pulihkan pengaturan SHYNTAX AUDIO dalam satu file ZIP."
                )
                androidx.compose.material3.Text(
                    "Termasuk EQ, DSP, tempo/pitch, visualizer, playlist/perpustakaan, favorit, dan lirik. File musik tidak disalin."
                )
                androidx.compose.material3.Text(
                    "Setelah pemulihan, buka ulang aplikasi agar semua efek dan pengaturan diterapkan."
                )
                if (status.isNotBlank()) {
                    androidx.compose.material3.Text(status, color = androidx.compose.ui.graphics.Color(0xFF69D8FF))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onExport) {
                androidx.compose.material3.Text("Buat backup")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(onClick = onImport) {
                    androidx.compose.material3.Text("Pulihkan")
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text("Tutup")
                }
            }
        }
    )
}

package com.shyntax.audio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val sampleRates = listOf("Otomatis", "44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz", "352.8 kHz", "384 kHz")
private val sampleFormats = listOf("Otomatis", "16 bit", "24 bit", "32 bit", "Float32")
private val resamplerRates = listOf(44100, 48000, 88200, 96000, 176400, 192000)

@Composable
fun AudioOutputSettingsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("shyntax_output", android.content.Context.MODE_PRIVATE) }
    var sampleRate by remember { mutableStateOf(prefs.getString("sample_rate", "Otomatis") ?: "Otomatis") }
    var sampleFormat by remember { mutableStateOf(prefs.getString("sample_format", "Otomatis") ?: "Otomatis") }
    var buffer by remember { mutableFloatStateOf(prefs.getInt("buffer", 500).toFloat()) }
    var reconfigure by remember { mutableFloatStateOf(prefs.getInt("reconfigure", 1000).toFloat()) }
    var lyricDelay by remember { mutableFloatStateOf(prefs.getInt("lyric_delay", 0).toFloat()) }
    var dvc by remember { mutableStateOf(prefs.getBoolean("dvc", false)) }
    var headroom by remember { mutableStateOf(prefs.getBoolean("headroom", false)) }
    var noEq by remember { mutableStateOf(prefs.getBoolean("no_eq", false)) }
    var gapless by remember { mutableStateOf(prefs.getBoolean("gapless", false)) }
    var resamplerMode by remember { mutableStateOf(prefs.getString("resampler_mode", "Otomatis") ?: "Otomatis") }
    var resamplerRate by remember { mutableIntStateOf(prefs.getInt("resampler_rate", 48000)) }
    var resamplerQuality by remember { mutableStateOf(prefs.getString("resampler_quality", "Seimbang") ?: "Seimbang") }

    fun save() {
        prefs.edit()
            .putString("sample_rate", sampleRate)
            .putString("sample_format", sampleFormat)
            .putInt("buffer", buffer.toInt())
            .putInt("reconfigure", reconfigure.toInt())
            .putInt("lyric_delay", lyricDelay.toInt())
            .putBoolean("dvc", dvc)
            .putBoolean("headroom", headroom)
            .putBoolean("no_eq", noEq)
            .putBoolean("gapless", gapless)
            .putString("resampler_mode", resamplerMode)
            .putInt("resampler_rate", resamplerRate)
            .putString("resampler_quality", resamplerQuality)
            .apply()
    }

    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        title = { Text("Pengaturan Output Audio") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Driver Output", style = MaterialTheme.typography.titleSmall)
                Text("Android AudioTrack / Media3", color = MaterialTheme.colorScheme.primary)
                Text("Driver yang benar-benar aktif saat ini adalah AudioTrack melalui Media3. AAudio dan OpenSL ES belum tersedia sebagai pilihan driver.", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Resampler PCM", style = MaterialTheme.typography.titleSmall)
                Text("Mode Otomatis mempertahankan laju sampel sumber. Mode Manual mengubah laju sampel PCM 16-bit sebelum dikirim ke AudioTrack.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Nonaktif", "Otomatis", "Manual").forEach { option ->
                        FilterChip(selected = resamplerMode == option, onClick = { resamplerMode = option; save() }, label = { Text(option) })
                    }
                }
                if (resamplerMode == "Manual") {
                    Text("Target sample rate", style = MaterialTheme.typography.labelLarge)
                    resamplerRates.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { rate ->
                                FilterChip(
                                    selected = resamplerRate == rate,
                                    onClick = { resamplerRate = rate; save() },
                                    label = { Text(if (rate >= 100000) "${rate / 1000} kHz" else "${rate / 1000.0} kHz") }
                                )
                            }
                        }
                    }
                    Text("Kualitas interpolasi", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Cepat", "Seimbang", "Tinggi").forEach { option ->
                            FilterChip(selected = resamplerQuality == option, onClick = { resamplerQuality = option; save() }, label = { Text(option) })
                        }
                    }
                }
                Text("Resampler hanya memproses PCM 16-bit. Format PCM lain dan audio encoded/passthrough dilewatkan tanpa perubahan. Pengaturan diterapkan saat layanan pemutar dibuat ulang; tutup paksa aplikasi lalu buka kembali setelah mengubah mode.", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Nilai Sampel", style = MaterialTheme.typography.titleSmall)
                Text("Preferensi laju sampel (tidak memaksa DAC)", style = MaterialTheme.typography.bodySmall)
                sampleRates.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = sampleRate == option,
                                onClick = { sampleRate = option; save() },
                                label = { Text(option, maxLines = 1) }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text("Format Sampel", style = MaterialTheme.typography.titleSmall)
                sampleFormats.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = sampleFormat == option,
                                onClick = { sampleFormat = option; save() },
                                label = { Text(option, maxLines = 1) }
                            )
                        }
                    }
                }
                Text("Format keluaran aktual tetap mengikuti decoder, Android, dan perangkat audio.", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Ukuran Buffer: ${buffer.toInt()} ms", style = MaterialTheme.typography.titleSmall)
                Slider(value = buffer, onValueChange = { buffer = it }, onValueChangeFinished = { save() }, valueRange = 100f..1000f, steps = 17)
                Text("Penundaan Rekonfigurasi: ${reconfigure.toInt()} ms", style = MaterialTheme.typography.titleSmall)
                Slider(value = reconfigure, onValueChange = { reconfigure = it }, onValueChangeFinished = { save() }, valueRange = 0f..3000f, steps = 29)
                Text("Delay Visualisasi / Lirik: ${lyricDelay.toInt()} ms", style = MaterialTheme.typography.titleSmall)
                Slider(value = lyricDelay, onValueChange = { lyricDelay = it }, onValueChangeFinished = { save() }, valueRange = -1000f..1000f, steps = 39)

                HorizontalDivider()
                SettingSwitch("Tanpa DVC", "Preferensi untuk menghindari kontrol volume langsung. Perubahan jalur DVC belum diterapkan oleh mesin pemutar.", dvc) { dvc = it; save() }
                SettingSwitch("Pengurangan Gain Headroom", "Preferensi headroom; pemrosesan gain otomatis belum dihubungkan.", headroom) { headroom = it; save() }
                SettingSwitch("Tanpa EQ / Nada", "Simpan preferensi untuk menonaktifkan EQ. Gunakan kontrol Equalizer saat ini untuk mengubah efek yang aktif.", noEq) { noEq = it; save() }
                SettingSwitch("Pemutaran Tanpa Jeda", "Preferensi gapless; transisi tanpa jeda khusus belum diaktifkan.", gapless) { gapless = it; save() }

                HorizontalDivider()
                Text("Catatan kompatibilitas", style = MaterialTheme.typography.titleSmall)
                Text("Driver aktif adalah Android AudioTrack. Resampler manual bekerja pada PCM 16-bit melalui AudioProcessor Media3. Pemilihan format sampel, ukuran buffer, DVC, dan gapless di bagian lain masih berupa preferensi dan belum mengubah jalur output. Hi-Res aktual tetap bergantung pada format sumber, Android, perangkat, dan DAC.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { save(); onDismiss() }) { Text("Selesai") }
        }
    )
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

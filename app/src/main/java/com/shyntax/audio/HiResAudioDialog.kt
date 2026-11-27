package com.shyntax.audio

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class AudioFileInfo(
    val mimeType: String = "Belum tersedia",
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val pcmEncoding: Int? = null,
    val detail: String = ""
)

data class AudioOutputInfo(
    val name: String,
    val type: String,
    val isUsb: Boolean,
    val supportedSampleRates: List<Int> = emptyList(),
    val supportedChannelCounts: List<Int> = emptyList(),
    val supportedEncodings: List<Int> = emptyList()
)

@Composable
fun HiResAudioDialog(
    fileInfo: AudioFileInfo,
    outputs: List<AudioOutputInfo>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced Audio Analyzer", color = Color(0xFF62D9FF)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("INFORMASI FILE", color = Color(0xFF62D9FF), style = MaterialTheme.typography.labelMedium)
                InfoRow("Codec / MIME", fileInfo.mimeType)
                InfoRow("Profil sumber", when (fileInfo.mimeType.lowercase()) {
                    "audio/flac", "audio/alac", "audio/wav", "audio/x-wav", "audio/ape", "audio/wavpack" -> "Format lossless"
                    "audio/mpeg", "audio/aac", "audio/mp4", "audio/ogg", "audio/opus" -> "Format terkompresi"
                    else -> "Format belum diklasifikasikan"
                })
                InfoRow("Sample rate sumber", fileInfo.sampleRate?.let { "${it} Hz" } ?: "Tidak dilaporkan decoder")
                InfoRow("Channel", fileInfo.channels?.toString() ?: "Tidak tersedia")
                InfoRow("Bitrate", fileInfo.bitrate?.let { "${it / 1000} kbps" } ?: "Tidak tersedia")
                InfoRow("PCM bit depth", pcmLabel(fileInfo.pcmEncoding))
                if (fileInfo.detail.isNotBlank()) Text(fileInfo.detail, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                HorizontalDivider()
                Text("PERANGKAT OUTPUT", color = Color(0xFF62D9FF), style = MaterialTheme.typography.labelMedium)
                if (outputs.isEmpty()) {
                    Text("Belum ada perangkat output yang terdeteksi.", color = Color.LightGray)
                } else {
                    outputs.forEach { output ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            InfoRow(output.name, if (output.isUsb) "USB • ${output.type}" else output.type)
                            InfoRow(
                                "Rate yang didukung",
                                output.supportedSampleRates.takeIf { it.isNotEmpty() }
                                    ?.joinToString { if (it >= 100000) "${it / 1000} kHz" else "${it / 1000.0} kHz" }
                                    ?: "Tidak dilaporkan Android"
                            )
                            InfoRow(
                                "Channel maksimum/opsi",
                                output.supportedChannelCounts.takeIf { it.isNotEmpty() }?.joinToString() ?: "Tidak dilaporkan Android"
                            )
                            InfoRow(
                                "Encoding",
                                output.supportedEncodings.takeIf { it.isNotEmpty() }
                                    ?.joinToString { encodingLabel(it) } ?: "Tidak dilaporkan Android"
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text("ANALISIS PCM REAL-TIME", color = Color(0xFF62D9FF), style = MaterialTheme.typography.labelMedium)
                InfoRow("Peak PCM", String.format("%.1f dBFS", AudioAnalysisState.peakDb))
                InfoRow("RMS PCM", String.format("%.1f dBFS", AudioAnalysisState.rmsDb))
                InfoRow("Sampel mendekati clipping", AudioAnalysisState.clippingSamples.toString())
                Text("ANALISIS SUMBER", color = Color(0xFF62D9FF), style = MaterialTheme.typography.labelMedium)
                Text(
                    when {
                        fileInfo.sampleRate == null -> "Decoder tidak melaporkan sample rate sumber."
                        fileInfo.sampleRate >= 88200 -> "Sample rate sumber termasuk kategori high sample rate. Ini tidak otomatis menjamin kualitas rekaman atau mastering."
                        else -> "Sumber berada pada sample rate standar. Kualitas akhir tetap bergantung pada rekaman, mastering, codec, dan perangkat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Text(
                    "Daftar rate, channel, dan encoding di atas adalah kapabilitas yang dilaporkan Android untuk perangkat yang terdeteksi, bukan bukti perangkat sedang memakai format tersebut. Peak dan clipping dihitung dari PCM 16-bit yang melewati mesin pemutar. Sample rate keluaran aktual dan bit-perfect DAC tetap belum diverifikasi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("Perbarui") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

private fun pcmLabel(encoding: Int?): String = when (encoding) {
    2 -> "16-bit PCM"
    4 -> "32-bit Float PCM"
    21 -> "24-bit packed PCM"
    22 -> "32-bit PCM"
    null -> "Tidak dilaporkan decoder"
    else -> "Encoding PCM $encoding"
}

fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker internal"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphone kabel"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset kabel"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset / DAC"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out analog"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line out digital"
    else -> "Output audio lain ($type)"
}


private fun encodingLabel(encoding: Int): String = when (encoding) {
    2 -> "PCM 16-bit"
    4 -> "PCM Float"
    21 -> "PCM 24-bit packed"
    22 -> "PCM 32-bit"
    5 -> "AC3"
    6 -> "E-AC3"
    7 -> "DTS"
    8 -> "DTS-HD"
    else -> "Encoding $encoding"
}

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
    val isUsb: Boolean
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
        title = { Text("Hi-Res & Audio Output", color = Color(0xFF69D8FF)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("INFORMASI FILE", color = Color(0xFF69D8FF), style = MaterialTheme.typography.labelMedium)
                InfoRow("Codec / MIME", fileInfo.mimeType)
                InfoRow("Sample rate", fileInfo.sampleRate?.let { "${it} Hz" } ?: "Tidak dilaporkan decoder")
                InfoRow("Channel", fileInfo.channels?.toString() ?: "Tidak tersedia")
                InfoRow("Bitrate", fileInfo.bitrate?.let { "${it / 1000} kbps" } ?: "Tidak tersedia")
                InfoRow("PCM bit depth", pcmLabel(fileInfo.pcmEncoding))
                if (fileInfo.detail.isNotBlank()) Text(fileInfo.detail, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                HorizontalDivider()
                Text("PERANGKAT OUTPUT", color = Color(0xFF69D8FF), style = MaterialTheme.typography.labelMedium)
                if (outputs.isEmpty()) {
                    Text("Belum ada perangkat output yang terdeteksi.", color = Color.LightGray)
                } else {
                    outputs.forEach { output ->
                        InfoRow(output.name, if (output.isUsb) "USB • ${output.type}" else output.type)
                    }
                }
                Text(
                    "USB DAC mengikuti routing Android. Sample rate/bit depth output aktual dan bit-perfect belum dapat dijamin oleh informasi ini.",
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
    4 -> "24-bit packed PCM"
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

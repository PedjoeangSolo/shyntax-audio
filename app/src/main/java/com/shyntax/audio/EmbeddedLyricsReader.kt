package com.shyntax.audio

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Reads common embedded lyric tags without loading the whole audio file.
 * Supported here: ID3v2 USLT/TXXX (MP3) and FLAC Vorbis-comment LYRICS.
 */
object EmbeddedLyricsReader {
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    fun read(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.openInputStream(uri)?.use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(4)
                val header = ByteArray(4)
                val count = input.read(header)
                input.reset()
                when {
                    count >= 4 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC" ->
                        readFlac(input)
                    count >= 3 && header.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" ->
                        readId3(input)
                    else -> null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun readId3(input: BufferedInputStream): String? {
        val header = ByteArray(10)
        if (input.read(header) != 10 || header.copyOfRange(0, 3).toString(Charsets.US_ASCII) != "ID3") return null
        val major = header[3].toInt() and 0xff
        if (major !in 2..4) return null
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val tag = ByteArray(tagSize)
        var offset = 0
        while (offset < tag.size) {
            val n = input.read(tag, offset, tag.size - offset)
            if (n <= 0) break
            offset += n
        }
        var pos = 0
        while (pos < offset) {
            val headerSize = if (major == 2) 6 else 10
            if (pos + headerSize > offset) break
            val idLength = if (major == 2) 3 else 4
            val id = tag.copyOfRange(pos, pos + idLength).toString(Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (major == 2) {
                ((tag[pos + 3].toInt() and 0xff) shl 16) or
                    ((tag[pos + 4].toInt() and 0xff) shl 8) or (tag[pos + 5].toInt() and 0xff)
            } else if (major == 4) {
                synchsafe(tag, pos + 4)
            } else {
                ((tag[pos + 4].toInt() and 0xff) shl 24) or
                    ((tag[pos + 5].toInt() and 0xff) shl 16) or
                    ((tag[pos + 6].toInt() and 0xff) shl 8) or (tag[pos + 7].toInt() and 0xff)
            }
            if (frameSize <= 0 || frameSize > offset - pos - headerSize) break
            val payloadStart = pos + headerSize
            val payload = tag.copyOfRange(payloadStart, payloadStart + frameSize)
            val lyric = when (id) {
                "USLT", "USL" -> decodeUnsynchronizedLyrics(payload, major == 2)
                "TXXX", "TXX" -> decodeLyricsUserText(payload)
                else -> null
            }
            if (!lyric.isNullOrBlank()) return lyric.trim()
            pos = payloadStart + frameSize
        }
        return null
    }

    private fun decodeUnsynchronizedLyrics(data: ByteArray, oldFrame: Boolean): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xff
        val descriptorStart = if (oldFrame) 4 else 4 // encoding byte + 3-byte language
        val terminator = findTerminator(data, descriptorStart, encoding)
        if (terminator < 0) return null
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        val textStart = (terminator + width).coerceAtMost(data.size)
        return decodeText(data.copyOfRange(textStart, data.size), encoding)
    }

    private fun decodeLyricsUserText(data: ByteArray): String? {
        if (data.size < 2) return null
        val encoding = data[0].toInt() and 0xff
        val start = findTerminator(data, 1, encoding)
        if (start < 0) return null
        val description = decodeText(data.copyOfRange(1, start), encoding).orEmpty()
        if (!description.contains("lyrics", ignoreCase = true)) return null
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        return decodeText(data.copyOfRange((start + width).coerceAtMost(data.size), data.size), encoding)
    }

    private fun findTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        val wide = encoding == 1 || encoding == 2
        var i = start
        while (i < data.size) {
            if (data[i].toInt() == 0 && (!wide || (i + 1 < data.size && data[i + 1].toInt() == 0))) return i
            i += if (wide) 2 else 1
        }
        return -1
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String? {
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> Charset.forName("UTF-16")
            2 -> Charset.forName("UTF-16BE")
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
        return runCatching { String(bytes, charset).trim('\u0000', '\uFEFF', ' ', '\r', '\n') }.getOrNull()
    }

    private fun readFlac(input: BufferedInputStream): String? {
        val signature = ByteArray(4)
        if (input.read(signature) != 4 || signature.toString(Charsets.US_ASCII) != "fLaC") return null
        var last = false
        while (!last) {
            val blockHeader = input.read()
            if (blockHeader < 0) return null
            last = (blockHeader and 0x80) != 0
            val type = blockHeader and 0x7f
            val sizeBytes = ByteArray(3)
            if (input.read(sizeBytes) != 3) return null
            val size = ((sizeBytes[0].toInt() and 0xff) shl 16) or
                ((sizeBytes[1].toInt() and 0xff) shl 8) or (sizeBytes[2].toInt() and 0xff)
            if (size > MAX_TAG_BYTES) return null
            if (type == 4) {
                val block = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(block, read, size - read)
                    if (n <= 0) return null
                    read += n
                }
                return parseVorbisComments(block)
            } else {
                var remaining = size
                while (remaining > 0) {
                    val skipped = input.skip(remaining.toLong()).toInt()
                    if (skipped <= 0) {
                        if (input.read() < 0) return null
                        remaining--
                    } else remaining -= skipped
                }
            }
        }
        return null
    }

    private fun parseVorbisComments(block: ByteArray): String? {
        var p = 0
        fun readInt(): Int {
            if (p + 4 > block.size) return -1
            val value = (block[p].toInt() and 0xff) or
                ((block[p + 1].toInt() and 0xff) shl 8) or
                ((block[p + 2].toInt() and 0xff) shl 16) or
                ((block[p + 3].toInt() and 0xff) shl 24)
            p += 4
            return value
        }
        val vendorLength = readInt()
        if (vendorLength < 0 || vendorLength > block.size - p) return null
        p += vendorLength
        val count = readInt()
        if (count < 0 || count > 10000) return null
        repeat(count) {
            val length = readInt()
            if (length < 0 || length > block.size - p) return@repeat
            val entry = String(block, p, length, StandardCharsets.UTF_8)
            p += length
            val split = entry.indexOf('=')
            if (split > 0 && entry.substring(0, split).equals("LYRICS", true)) {
                return entry.substring(split + 1).replace("\\n", "\n").trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)
    }
}

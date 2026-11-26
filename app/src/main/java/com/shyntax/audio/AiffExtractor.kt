package com.shyntax.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.roundToInt

/**
 * Extractor for AIFF and AIFF-C files (.aif / .aiff / .aifc).
 *
 * Media3 1.5.1 (the version this project builds against) ships no AIFF extractor, and the
 * bundled FFmpeg extension is an audio decoder only, so it cannot demux the AIFF container.
 * This extractor fills the gap for the common cases, modelled on Media3's WavExtractor:
 * uncompressed PCM ('NONE' big-endian and 'sowt' little-endian, at 8/16/24/32 bits) plus
 * pass-through mu-law/A-law AIFF-C. Big-endian PCM is byte-swapped to the little-endian
 * layout Android decoders expect, and 8-bit PCM is converted from AIFF's signed layout to
 * the unsigned layout of [C.ENCODING_PCM_8BIT]. Other AIFF-C compressions (IMA4, fl32,
 * MACE, ...) are rejected with an unsupported-feature error.
 */
@UnstableApi
class AiffExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var state = STATE_READING_FORM

    // COMM chunk fields.
    private var commParsed = false
    private var channelCount = 0
    private var sampleRate = 0
    private var bitsPerSample = 0
    private var numSampleFrames = 0L
    private var compressionType = COMPRESSION_NONE
    private var isAifc = false

    // SSND chunk bounds (positions in the stream).
    private var dataStartPosition = -1L
    private var dataEndPosition = -1L

    // Derived stream properties, computed by setUpTrack().
    private var bytesPerSample = 0
    private var frameSize = 0
    private var targetSampleSizeBytes = 0

    // Sample writer state (reset on every seek).
    private var startTimeUs = 0L
    private var pendingOutputBytes = 0
    private var outputFrameCount = 0L
    private var carryCount = 0

    private val scratch = ByteArray(SCRATCH_SIZE_BYTES)
    private val scratchParsable = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(FORM_HEADER_SIZE_BYTES)
        if (!input.peekFully(header, 0, FORM_HEADER_SIZE_BYTES, true)) return false
        if (!header.matchesAscii(0, "FORM")) return false
        return header.matchesAscii(8, "AIFF") || header.matchesAscii(8, "AIFC")
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun seek(position: Long, timeUs: Long) {
        state = if (position == 0L) STATE_READING_FORM else STATE_READING_SAMPLE_DATA
        resetWriter(timeUs)
    }

    override fun release() {
        // No resources to release.
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return when (state) {
            STATE_READING_FORM -> readFormType(input, seekPosition)
            STATE_READING_CHUNKS -> readChunks(input, seekPosition)
            STATE_READING_SAMPLE_DATA -> readSampleData(input)
            else -> throw IllegalStateException()
        }
    }

    private fun resetWriter(timeUs: Long) {
        startTimeUs = timeUs
        pendingOutputBytes = 0
        outputFrameCount = 0
        carryCount = 0
    }

    private fun readFormType(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (dataStartPosition != -1L) {
            // The chunks were parsed already (e.g. the player seeked back to the start):
            // jump straight to the sample data.
            state = STATE_READING_SAMPLE_DATA
            resetWriter(0)
            seekPosition.position = dataStartPosition
            return Extractor.RESULT_SEEK
        }
        val header = ByteArray(FORM_HEADER_SIZE_BYTES)
        if (!input.readFully(header, 0, FORM_HEADER_SIZE_BYTES, true) ||
            !header.matchesAscii(0, "FORM")
        ) {
            throw ParserException.createForMalformedContainer(
                "Unsupported or unrecognized AIFF file type.", null)
        }
        isAifc = header.matchesAscii(8, "AIFC")
        if (!isAifc && !header.matchesAscii(8, "AIFF")) {
            throw ParserException.createForMalformedContainer(
                "Unsupported or unrecognized AIFF file type.", null)
        }
        state = STATE_READING_CHUNKS
        return Extractor.RESULT_CONTINUE
    }

    private fun readChunks(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE_BYTES)
        while (true) {
            if (!input.readFully(chunkHeader, 0, CHUNK_HEADER_SIZE_BYTES, true)) break
            val chunkId = chunkHeader.fourCc(0)
            val chunkSize = chunkHeader.readInt32BigEndian(4)
            val chunkStart = input.position
            when (chunkId) {
                CHUNK_ID_COMM -> {
                    parseCommChunk(input, chunkSize)
                    skipChunkRemainder(input, chunkStart, chunkSize)
                }
                CHUNK_ID_SSND -> {
                    readSsndBounds(input, chunkSize, chunkStart)
                    if (commParsed) {
                        // The usual layout: COMM came first. Advance to the start of the
                        // audio data (the input sits right after the SSND offset and
                        // blockSize fields) and start producing samples.
                        val skip = (dataStartPosition - input.position).toInt()
                        if (skip > 0) input.skipFully(skip)
                        setUpTrack(input)
                        state = STATE_READING_SAMPLE_DATA
                        return Extractor.RESULT_CONTINUE
                    }
                    // SSND before COMM: the bounds are remembered; keep scanning for COMM.
                    skipChunkRemainder(input, chunkStart, chunkSize)
                }
                else -> skipChunkRemainder(input, chunkStart, chunkSize)
            }
        }
        if (commParsed && dataStartPosition != -1L) {
            // COMM was found after SSND; the input is past the audio data, so ask for it to
            // be repositioned at the start of the SSND data.
            setUpTrack(input)
            state = STATE_READING_SAMPLE_DATA
            resetWriter(0)
            seekPosition.position = dataStartPosition
            return Extractor.RESULT_SEEK
        }
        throw ParserException.createForMalformedContainer(
            "AIFF file is missing its COMM or SSND chunk.", null)
    }

    private fun parseCommChunk(input: ExtractorInput, chunkSize: Int) {
        if (chunkSize < COMM_MIN_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer("AIFF COMM chunk is too small.", null)
        }
        val comm = ByteArray(COMM_MIN_SIZE_BYTES)
        if (!input.readFully(comm, 0, COMM_MIN_SIZE_BYTES, true)) {
            throw ParserException.createForMalformedContainer("AIFF COMM chunk is truncated.", null)
        }
        channelCount = comm.readInt16BigEndian(0)
        numSampleFrames = comm.readUnsignedInt32BigEndian(2)
        bitsPerSample = comm.readInt16BigEndian(6)
        sampleRate = parseExtended80BitFloat(comm, 8)
        compressionType = COMPRESSION_NONE
        if (isAifc && chunkSize >= COMM_MIN_SIZE_BYTES + 4) {
            val compression = ByteArray(4)
            if (!input.readFully(compression, 0, 4, true)) {
                throw ParserException.createForMalformedContainer(
                    "AIFF-C COMM chunk is truncated.", null)
            }
            compressionType = compression.fourCc(0)
        }
        if (channelCount <= 0 || sampleRate <= 0) {
            throw ParserException.createForMalformedContainer("Invalid AIFF COMM chunk.", null)
        }
        when (compressionType) {
            COMPRESSION_NONE, COMPRESSION_SOWT -> {
                if (bitsPerSample != 8 && bitsPerSample != 16 &&
                    bitsPerSample != 24 && bitsPerSample != 32
                ) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Unsupported AIFF PCM sample size: $bitsPerSample.")
                }
            }
            COMPRESSION_ULAW, COMPRESSION_ALAW -> {
                if (bitsPerSample != 8) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Unsupported compressed AIFF sample size: $bitsPerSample.")
                }
            }
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported AIFF-C compression type.")
        }
        commParsed = true
    }

    private fun readSsndBounds(input: ExtractorInput, chunkSize: Int, chunkStart: Long) {
        if (chunkSize < SSND_HEADER_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer("AIFF SSND chunk is too small.", null)
        }
        val header = ByteArray(SSND_HEADER_SIZE_BYTES)
        if (!input.readFully(header, 0, SSND_HEADER_SIZE_BYTES, true)) {
            throw ParserException.createForMalformedContainer("AIFF SSND chunk is truncated.", null)
        }
        val offset = header.readUnsignedInt32BigEndian(0)
        dataStartPosition = chunkStart + SSND_HEADER_SIZE_BYTES + offset
        dataEndPosition = chunkStart + chunkSize
        if (dataEndPosition <= dataStartPosition && input.length != C.LENGTH_UNSET) {
            // Some writers leave the SSND size unset or bogus; fall back to the stream length.
            dataEndPosition = input.length
        }
    }

    private fun skipChunkRemainder(input: ExtractorInput, chunkStart: Long, chunkSize: Int) {
        var remaining = chunkStart + chunkSize - input.position
        // Chunk data is padded to an even byte boundary.
        if (chunkSize % 2 != 0) remaining += 1
        while (remaining > 0) {
            val step = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            remaining -= step
        }
    }

    private fun setUpTrack(input: ExtractorInput) {
        val inputLength = input.length
        if (inputLength != C.LENGTH_UNSET && dataEndPosition > inputLength) {
            dataEndPosition = inputLength
        }
        bytesPerSample = when (compressionType) {
            COMPRESSION_ULAW, COMPRESSION_ALAW -> 1
            else -> bitsPerSample / 8
        }
        frameSize = channelCount * bytesPerSample
        val dataFrames = (dataEndPosition - dataStartPosition) / frameSize
        val totalFrames = if (dataFrames > 0) dataFrames else numSampleFrames
        val durationUs =
            Util.scaleLargeTimestamp(totalFrames, C.MICROS_PER_SECOND, sampleRate.toLong())
        targetSampleSizeBytes = maxOf(
            frameSize,
            (sampleRate / TARGET_SAMPLES_PER_SECOND).coerceAtLeast(1) * frameSize
        )
        val formatBuilder = Format.Builder()
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setAverageBitrate(sampleRate * frameSize * 8)
            .setPeakBitrate(sampleRate * frameSize * 8)
            .setMaxInputSize(targetSampleSizeBytes)
        when (compressionType) {
            COMPRESSION_ULAW -> formatBuilder.setSampleMimeType(MimeTypes.AUDIO_MLAW)
            COMPRESSION_ALAW -> formatBuilder.setSampleMimeType(MimeTypes.AUDIO_ALAW)
            else -> formatBuilder
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(
                    when (bitsPerSample) {
                        8 -> C.ENCODING_PCM_8BIT
                        24 -> C.ENCODING_PCM_24BIT
                        32 -> C.ENCODING_PCM_32BIT
                        else -> C.ENCODING_PCM_16BIT
                    }
                )
        }
        checkNotNull(trackOutput).format(formatBuilder.build())
        checkNotNull(extractorOutput).seekMap(
            AiffSeekMap(sampleRate, frameSize, dataStartPosition, totalFrames, durationUs)
        )
    }

    private fun readSampleData(input: ExtractorInput): Int {
        var bytesLeft = dataEndPosition - input.position
        // Append sample data to the track output until a ~100ms sample is buffered or the
        // SSND data runs out. Reads are funnelled through a scratch buffer so big-endian
        // samples can be converted, keeping a partial sample (if a read splits one) in the
        // carry bytes at the start of the buffer for the next round.
        while (pendingOutputBytes < targetSampleSizeBytes) {
            val toRead = minOf(
                (targetSampleSizeBytes - pendingOutputBytes - carryCount).toLong(),
                (scratch.size - carryCount).toLong(),
                bytesLeft
            ).toInt()
            var read = 0
            if (toRead > 0) {
                read = input.read(scratch, carryCount, toRead)
                if (read == C.RESULT_END_OF_INPUT) {
                    read = 0
                    bytesLeft = 0
                } else {
                    bytesLeft -= read
                }
            }
            val available = carryCount + read
            val usable = available - available % bytesPerSample
            if (usable > 0) {
                transformSamples(scratch, usable)
                scratchParsable.reset(scratch, usable)
                checkNotNull(trackOutput).sampleData(scratchParsable, usable)
                pendingOutputBytes += usable
            }
            val remainder = available - usable
            if (remainder > 0) {
                scratch.copyInto(scratch, 0, usable, usable + remainder)
            }
            carryCount = remainder
            if (read == 0) break
        }
        // Commit the whole frames buffered so far as one sample.
        val pendingFrames = pendingOutputBytes / frameSize
        if (pendingFrames > 0) {
            val timeUs = startTimeUs + Util.scaleLargeTimestamp(
                outputFrameCount, C.MICROS_PER_SECOND, sampleRate.toLong()
            )
            val size = pendingFrames * frameSize
            val offset = pendingOutputBytes - size
            checkNotNull(trackOutput)
                .sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset, null)
            outputFrameCount += pendingFrames
            pendingOutputBytes = offset
        }
        return if (bytesLeft <= 0L) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    /** Converts the first [length] bytes of [buffer] (a whole number of samples) in place. */
    private fun transformSamples(buffer: ByteArray, length: Int) {
        if (compressionType != COMPRESSION_NONE) {
            // 'sowt' is already little-endian; mu-law/A-law pass through untouched.
            return
        }
        if (bitsPerSample == 8) {
            // AIFF stores signed 8-bit samples; ENCODING_PCM_8BIT expects unsigned samples.
            for (i in 0 until length) {
                buffer[i] = (buffer[i].toInt() xor 0x80).toByte()
            }
        } else {
            // Big-endian to little-endian: reverse the bytes of each sample.
            var i = 0
            while (i < length) {
                var lo = i
                var hi = i + bytesPerSample - 1
                while (lo < hi) {
                    val tmp = buffer[lo]
                    buffer[lo] = buffer[hi]
                    buffer[hi] = tmp
                    lo++
                    hi--
                }
                i += bytesPerSample
            }
        }
    }

    /** Seek map for the constant-frame-size stream, mirroring Media3's WavSeekMap. */
    private class AiffSeekMap(
        private val sampleRate: Int,
        private val frameSize: Int,
        private val dataStartPosition: Long,
        private val totalFrames: Long,
        private val durationUs: Long
    ) : SeekMap {

        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val lastFrame = (totalFrames - 1).coerceAtLeast(0)
            val frameIndex = ((timeUs * sampleRate) / C.MICROS_PER_SECOND).coerceIn(0, lastFrame)
            val first = SeekPoint(frameTimeUs(frameIndex), dataStartPosition + frameIndex * frameSize)
            if (first.timeUs >= timeUs || frameIndex == lastFrame) {
                return SeekMap.SeekPoints(first)
            }
            val nextIndex = frameIndex + 1
            return SeekMap.SeekPoints(
                first,
                SeekPoint(frameTimeUs(nextIndex), dataStartPosition + nextIndex * frameSize)
            )
        }

        private fun frameTimeUs(frameIndex: Long): Long =
            Util.scaleLargeTimestamp(frameIndex, C.MICROS_PER_SECOND, sampleRate.toLong())
    }

    private companion object {
        const val STATE_READING_FORM = 0
        const val STATE_READING_CHUNKS = 1
        const val STATE_READING_SAMPLE_DATA = 2

        const val FORM_HEADER_SIZE_BYTES = 12
        const val CHUNK_HEADER_SIZE_BYTES = 8
        const val COMM_MIN_SIZE_BYTES = 18
        const val SSND_HEADER_SIZE_BYTES = 8
        const val SCRATCH_SIZE_BYTES = 64 * 1024 + 4

        /** Group frames into ~100ms samples, like Media3's WavExtractor. */
        const val TARGET_SAMPLES_PER_SECOND = 10

        val CHUNK_ID_COMM = fourCcOf("COMM")
        val CHUNK_ID_SSND = fourCcOf("SSND")

        val COMPRESSION_NONE = fourCcOf("NONE")
        val COMPRESSION_SOWT = fourCcOf("sowt")
        val COMPRESSION_ULAW = fourCcOf("ulaw")
        val COMPRESSION_ALAW = fourCcOf("alaw")
    }
}

private fun fourCcOf(value: String): Int {
    var result = 0
    for (ch in value) result = (result shl 8) or (ch.code and 0xFF)
    return result
}

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
    if (size < offset + value.length) return false
    for (i in value.indices) {
        if (this[offset + i] != value[i].code.toByte()) return false
    }
    return true
}

private fun ByteArray.fourCc(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readInt16BigEndian(offset: Int): Int =
    (this[offset].toInt() shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.readInt32BigEndian(offset: Int): Int =
    (this[offset].toInt() shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readUnsignedInt32BigEndian(offset: Int): Long =
    readInt32BigEndian(offset).toLong() and 0xFFFFFFFFL

/** Parses the 80-bit IEEE 754 extended float used for the AIFF sample rate. */
private fun parseExtended80BitFloat(bytes: ByteArray, offset: Int): Int {
    val exponent = ((bytes[offset].toInt() and 0x7F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    var mantissa = 0.0
    for (i in 2 until 10) {
        mantissa = mantissa * 256.0 + (bytes[offset + i].toInt() and 0xFF)
    }
    if (exponent == 0 && mantissa == 0.0) return 0
    val value = mantissa * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
    return value.roundToInt()
}

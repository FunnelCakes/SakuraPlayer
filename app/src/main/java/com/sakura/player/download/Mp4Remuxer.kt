package com.sakura.player.download

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Remuxes a list of downloaded MPEG-TS segments into a single VALID MP4 file.
 *
 * The old `mergeSegments` simply concatenated raw TS bytes and slapped a `.mp4`
 * extension on them — the result had no moov/mdat boxes, played only in tolerant
 * players, and confused the Android media scanner. This class does a proper
 * TS -> MP4 remux:
 *
 *  1. Concatenate the decrypted TS segments into a single temp stream (matches how
 *     the old code fed them, so cross-segment PID continuity is preserved).
 *  2. Pass 1: drive a media3 [TsExtractor] to discover the track formats
 *     (H.264 video + AAC audio) without writing any samples.
 *  3. Pass 2: drive a fresh [TsExtractor] (with a [TimestampAdjuster] to unwrap the
 *     33-bit PTS) and feed the demuxed samples into a media3 [Mp4Muxer], which
 *     writes a real MP4 with moov/mdat. Mp4Muxer handles Annex-B -> AVCC
 *     conversion and B-frame presentation reordering (the source streams use
 *     H.264 High profile with B-frames).
 *
 * Segment-boundary discontinuities (HLS segments often restart their PTS) are
 * handled by a per-track [DiscontinuityGuard] that shifts the PTS timeline forward
 * when a sample's timestamp drops by more than [DISCONTINUITY_THRESHOLD_US], while
 * leaving small backwards jumps (B-frame reordering) untouched.
 *
 * Output extension: [EXTENSION] (`.svideo`), a custom extension that Android's
 * media scanner will not index as a video file.
 */
object Mp4Remuxer {
    private const val TAG = "Mp4Remuxer"

    /**
     * Custom output extension. Chosen so the Android media scanner / gallery does
     * not index downloaded episodes, while the app's own local file browser
     * (LocalFileManager / JsBridge) and player (ExoPlayer sniffs content, not
     * extension) continue to recognize and play them.
     */
    const val EXTENSION = "svideo"

    /**
     * A backward PTS jump larger than this is treated as a segment boundary in the
     * concatenated HLS stream and the timeline is shifted forward. B-frame
     * reordering produces much smaller backward jumps (a few frame durations),
     * which must never trigger a shift.
     */
    private const val DISCONTINUITY_THRESHOLD_US = 1_000_000L // 1 second

    /** Build the output file name for a downloaded episode. */
    fun downloadFileName(title: String, epIndex: Int): String =
        "${title}_第${epIndex}集.$EXTENSION"

    /**
     * Remux [segments] into [outFile]. Returns true on success. On failure the
     * caller may fall back to the historical raw concatenation; nothing here throws
     * to the caller.
     */
    fun remux(segments: List<File>, outFile: File): Boolean {
        if (segments.isEmpty()) return false
        val parent = outFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Cannot create output directory: $parent")
        }
        val tempTs = File(parent, ".${outFile.name}.combined.ts")
        return try {
            concatSegments(segments, tempTs)
            if (tempTs.length() == 0L) {
                Log.e(TAG, "Concatenated TS is empty")
                return false
            }
            val tracks = extractFormats(tempTs)
            if (tracks.isEmpty()) {
                Log.e(TAG, "TsExtractor found no tracks in the concatenated stream")
                return false
            }
            Log.e(TAG, "Remuxing ${tracks.size} track(s): " +
                tracks.joinToString { "${it.format.sampleMimeType} ${it.format.width}x${it.format.height}" })
            mux(tempTs, tracks, outFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TS->MP4 remux failed", e)
            false
        } finally {
            tempTs.delete()
        }
    }

    // ---------------------------------------------------------------------------
    // Stream plumbing
    // ---------------------------------------------------------------------------

    private fun concatSegments(segments: List<File>, out: File) {
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(262144)
            for (seg in segments) {
                seg.inputStream().use { input ->
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) fos.write(buf, 0, r)
                }
            }
        }
    }

    /** Minimal seekable [DataReader] over a [RandomAccessFile]. */
    private class RandomAccessFileReader(private val raf: RandomAccessFile) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            raf.read(buffer, offset, length)
    }

    /**
     * Drive a [TsExtractor] over [tsFile] from start to finish, routing its output to
     * [output]. Handles `RESULT_SEEK` (re-align to a sync byte) by seeking the
     * underlying file and rebuilding the [DefaultExtractorInput] at the new position.
     */
    private fun demux(tsFile: File, output: ExtractorOutput) {
        val extractor = TsExtractor(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            TimestampAdjuster(0L),
            DefaultTsPayloadReaderFactory()
        )
        val raf = RandomAccessFile(tsFile, "r")
        val reader = RandomAccessFileReader(raf)
        val length = raf.length()
        var position = 0L
        var input = DefaultExtractorInput(reader, position, length)
        val positionHolder = PositionHolder()
        extractor.init(output)
        try {
            while (true) {
                when (extractor.read(input, positionHolder)) {
                    Extractor.RESULT_CONTINUE -> {
                        // Normal: input position advanced.
                    }
                    Extractor.RESULT_SEEK -> {
                        position = positionHolder.position
                        raf.seek(position)
                        extractor.seek(position, 0L)
                        input = DefaultExtractorInput(reader, position, length)
                    }
                    else -> break // RESULT_END_OF_INPUT
                }
            }
        } finally {
            extractor.release()
            raf.close()
        }
    }

    /** Read up to [length] bytes from [input] in chunks, forwarding each chunk to [onChunk]. */
    private fun readSampleChunk(
        input: DataReader,
        buf: ByteArray,
        length: Int,
        allowEndOfInput: Boolean,
        onChunk: (Int) -> Unit
    ): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buf, 0, minOf(buf.size, length - total))
            if (n < 0) {
                if (!allowEndOfInput && total < length) throw EOFException("Premature end of TS input")
                break
            }
            onChunk(n)
            total += n
        }
        return total
    }

    // ---------------------------------------------------------------------------
    // Pass 1: discover track formats
    // ---------------------------------------------------------------------------

    private data class TrackFormat(val id: Int, val type: Int, val format: Format)

    private fun extractFormats(tsFile: File): List<TrackFormat> {
        val formats = LinkedHashMap<Int, TrackFormat>()
        val discardBuf = ByteArray(8192)
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                return object : TrackOutput {
                    override fun format(format: Format) {
                        if (!formats.containsKey(id)) {
                            formats[id] = TrackFormat(id, type, format)
                            Log.d(TAG, "Pass 1: track id=$id type=$type mime=${format.sampleMimeType}")
                        }
                    }

                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                        readSampleChunk(input, discardBuf, length, allowEndOfInput) {}

                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
                        readSampleChunk(input, discardBuf, length, allowEndOfInput) {}

                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        data.skipBytes(length)
                    }

                    override fun sampleData(data: ParsableByteArray, length: Int) {
                        data.skipBytes(length)
                    }

                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
                }
            }

            override fun endTracks() {}
            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {}
        }
        demux(tsFile, output)
        return formats.values.toList()
    }

    // ---------------------------------------------------------------------------
    // Pass 2: demux + mux
    // ---------------------------------------------------------------------------

    private fun mux(tsFile: File, tracks: List<TrackFormat>, outFile: File) {
        val fos = FileOutputStream(outFile)
        val muxer = Mp4Muxer.Builder(fos).build()
        try {
            val output = object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val pass1 = tracks.firstOrNull { it.id == id }
                    if (pass1 == null) {
                        Log.w(TAG, "Pass 2: unexpected track id=$id (not seen in pass 1); discarding")
                        return DiscardTrackOutput
                    }
                    return MuxerTrackOutput(muxer, DiscontinuityGuard(DISCONTINUITY_THRESHOLD_US))
                }

                override fun endTracks() {}
                override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {}
            }
            demux(tsFile, output)
        } finally {
            try {
                muxer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Mp4Muxer.close failed", e)
            }
            try {
                fos.close()
            } catch (e: Exception) {
                Log.e(TAG, "Output stream close failed", e)
            }
        }
    }

    /** Shared sink for tracks we do not want in the MP4 (unknown MIME, subtitles, ...). */
    private object DiscardTrackOutput : TrackOutput {
        private val discardBuf = ByteArray(8192)

        override fun format(format: Format) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            readSampleChunk(input, discardBuf, length, allowEndOfInput) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            readSampleChunk(input, discardBuf, length, allowEndOfInput) {}

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleData(data: ParsableByteArray, length: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
    }

    /**
     * A [TrackOutput] that lazily registers its track with the [Mp4Muxer] on the
     * first [format] call, accumulates sample bytes, applies a per-track
     * [DiscontinuityGuard], and writes each complete sample into the muxer.
     */
    private class MuxerTrackOutput(
        private val muxer: Mp4Muxer,
        private val guard: DiscontinuityGuard
    ) : TrackOutput {
        private var trackIndex = -1
        private val accumulator = SampleAccumulator()
        private val readBuf = ByteArray(64 * 1024)

        override fun format(format: Format) {
            if (trackIndex != -1) return
            val mime = format.sampleMimeType ?: return
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) return
            trackIndex = muxer.addTrack(format)
            Log.d(TAG, "Pass 2: added track index=$trackIndex mime=$mime")
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            readSampleChunk(input, readBuf, length, allowEndOfInput) { n -> accumulator.append(readBuf, n) }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            readSampleChunk(input, readBuf, length, allowEndOfInput) { n -> accumulator.append(readBuf, n) }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            accumulator.append(bytes, length)
        }

        override fun sampleData(data: ParsableByteArray, length: Int) {
            sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (trackIndex == -1 || timeUs == C.TIME_UNSET || size <= 0) return
            val ptsUs = guard.adjust(timeUs)
            // media3's H264Reader reports the sample as the `size` bytes ending at
            // (totalWritten - offset) in the absolute stream. The accumulator holds
            // bytes [droppedBase, totalWritten), so the sample's local range is
            // [sampleStart - droppedBase, sampleEnd - droppedBase).
            val sampleEnd = accumulator.totalWritten - offset
            val sampleStart = sampleEnd - size
            val localStart = (sampleStart - accumulator.droppedBase).toInt()
            val localEnd = (sampleEnd - accumulator.droppedBase).toInt()
            if (localStart < 0 || localEnd > accumulator.length) {
                Log.e(TAG, "Bad sample range start=$localStart end=$localEnd total=${accumulator.totalWritten} size=$size off=$offset base=${accumulator.droppedBase}")
                return
            }
            val buffer = accumulator.sampleBuffer(sampleStart, size)
            val flagsOut = if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) C.BUFFER_FLAG_KEY_FRAME else 0
            try {
                muxer.writeSampleData(trackIndex, buffer, BufferInfo(ptsUs, size, flagsOut))
            } finally {
                // Drop everything up to and including this sample; keep any bytes that
                // already arrived for the next sample (the NAL that triggered output).
                accumulator.drop(localEnd, sampleEnd)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Enforces a monotonically non-decreasing PTS timeline per track, while
     * preserving small backwards jumps that are caused by B-frame presentation
     * reordering (decode order PTS is not presentation order). When a sample's PTS
     * drops by more than [thresholdUs] below the running maximum, the whole track
     * timeline is shifted forward to bridge a segment-boundary discontinuity.
     */
    private class DiscontinuityGuard(private val thresholdUs: Long) {
        private var lastPtsUs = C.TIME_UNSET
        private var offsetUs = 0L

        fun adjust(ptsUs: Long): Long {
            if (ptsUs == C.TIME_UNSET) return ptsUs
            var adjusted = ptsUs + offsetUs
            if (lastPtsUs != C.TIME_UNSET && adjusted < lastPtsUs - thresholdUs) {
                // Segment boundary: shift the timeline forward so this track resumes
                // where it left off instead of overlapping the previous segment.
                offsetUs += lastPtsUs - adjusted
                adjusted = lastPtsUs
            }
            if (adjusted > lastPtsUs) lastPtsUs = adjusted
            return adjusted
        }
    }

    /** Growable heap buffer that accumulates the bytes of the current sample. */
    private class SampleAccumulator {
        private var data = ByteArray(65536)
        private var size = 0
        /** Total bytes appended across the whole stream (never reset by drop). */
        var totalWritten: Long = 0
        /** Absolute stream offset of the first byte currently held in [data]. */
        var droppedBase: Long = 0

        /** Number of bytes currently held locally. */
        val length: Int get() = size

        fun append(src: ByteArray, len: Int) {
            if (size + len > data.size) {
                var newCap = data.size * 2
                while (newCap < size + len) newCap *= 2
                data = data.copyOf(newCap)
            }
            System.arraycopy(src, 0, data, size, len)
            size += len
            totalWritten += len
        }

        /** Wrap [sampleSize] bytes at absolute stream offset [absOffset] in a direct [ByteBuffer]. */
        fun sampleBuffer(absOffset: Long, sampleSize: Int): ByteBuffer {
            val local = (absOffset - droppedBase).toInt()
            val buffer = ByteBuffer.allocateDirect(sampleSize)
            buffer.put(data, local, sampleSize)
            buffer.flip()
            return buffer
        }

        /**
         * Drop local bytes [0, localCount) and advance [droppedBase] accordingly. Keeps
         * any remainder (bytes that belong to a not-yet-complete sample).
         */
        fun drop(localCount: Int, absEnd: Long) {
            if (localCount >= size) {
                size = 0
            } else {
                System.arraycopy(data, localCount, data, 0, size - localCount)
                size -= localCount
            }
            droppedBase = absEnd
        }
    }
}

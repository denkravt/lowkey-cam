package com.justan.lowkeycam.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stitches every clip of a project into a single MP4.
 *
 * Video/audio samples are copied straight through (no re-encode), so the export is
 * lossless and takes seconds rather than minutes. Clips must share orientation —
 * the dominant orientation of the project wins and the odd ones out are reported
 * as skipped, because one MP4 carries a single rotation matrix.
 */
object VlogExporter {

    data class Outcome(
        val file: File?,
        val used: Int,
        val skippedOrientation: Int = 0,
        val skippedIncompatible: Int = 0,
        val error: String? = null,
    ) {
        val skipped: Int get() = skippedOrientation + skippedIncompatible
    }

    private const val FRAME_US = 33_000L

    suspend fun export(
        context: Context,
        projectId: String,
        projectName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        val all = Store.clips(context, projectId)
        if (all.isEmpty()) return@withContext Outcome(null, 0, error = "No clips in this project yet")

        val landscape = all.filter { it.lowKey }
        val portrait = all.filterNot { it.lowKey }
        val useLandscape = landscape.size >= portrait.size
        val group = if (useLandscape) landscape else portrait
        val skippedOrientation = all.size - group.size
        var skippedIncompatible = 0

        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val output = File(exportsDir, "vlog_" + safeName(projectName) + "_" + System.currentTimeMillis() + ".mp4")

        val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        var muxer: MediaMuxer? = null
        var videoOut = -1
        var audioOut = -1
        var started = false
        var cursorUs = 0L
        var used = 0

        var refWidth = 0
        var refHeight = 0
        var refVideoCsd: ByteArray? = null
        var refSampleRate = 0
        var refChannels = 0

        try {
            for ((index, clip) in group.withIndex()) {
                onProgress(index, group.size)
                val file = File(Store.clipsDir(context), clip.fileName)
                if (!file.exists()) {
                    Log.w("VlogExport", "skip " + clip.fileName + ": missing file")
                    skippedIncompatible++
                    continue
                }

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)

                    var videoIn = -1
                    var audioIn = -1
                    for (t in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") && videoIn < 0) videoIn = t
                        if (mime.startsWith("audio/") && audioIn < 0) audioIn = t
                    }
                    if (videoIn < 0) {
                        Log.w("VlogExport", "skip " + clip.fileName + ": no video track")
                        skippedIncompatible++
                        continue
                    }

                    val videoFormat = extractor.getTrackFormat(videoIn)
                    val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    val csd = videoFormat.getByteBuffer("csd-0")?.let { it.duplicate().toBytes() }
                    val sps = csd?.let { firstNal(it) } ?: csd
                    val audioFormat = if (audioIn >= 0) extractor.getTrackFormat(audioIn) else null
                    val sampleRate = audioFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0
                    val channels = audioFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0

                    if (muxer == null) {
                        refWidth = width
                        refHeight = height
                        refVideoCsd = sps
                        refSampleRate = sampleRate
                        refChannels = channels
                        val m = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        m.setOrientationHint(if (useLandscape) 0 else 270)
                        videoOut = m.addTrack(videoFormat)
                        audioOut = if (audioFormat != null) m.addTrack(audioFormat) else -1
                        muxer = m
                    } else if (width != refWidth || height != refHeight || !sameBytes(sps, refVideoCsd) ||
                        (audioFormat != null && (sampleRate != refSampleRate || channels != refChannels))
                    ) {
                        // Different encoder settings: mixing them would corrupt the container.
                        Log.w(
                            "VlogExport",
                            "skip " + clip.fileName + " fmt mismatch: " + width + "x" + height +
                                " vs " + refWidth + "x" + refHeight +
                                " sps=" + sameBytes(sps, refVideoCsd) +
                                " audio=" + sampleRate + "/" + channels + " vs " + refSampleRate + "/" + refChannels,
                        )
                        skippedIncompatible++
                        continue
                    }

                    if (!started) {
                        muxer.start()
                        started = true
                    }

                    extractor.selectTrack(videoIn)
                    if (audioIn >= 0 && audioOut >= 0) extractor.selectTrack(audioIn)

                    var firstPts = Long.MIN_VALUE
                    var clipLengthUs = 0L

                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = extractor.sampleTime
                        if (pts < 0) {
                            extractor.advance()
                            continue
                        }
                        if (firstPts == Long.MIN_VALUE) firstPts = pts

                        val offsetUs = pts - firstPts
                        if (offsetUs > clipLengthUs) clipLengthUs = offsetUs

                        val outTrack = when (extractor.sampleTrackIndex) {
                            videoIn -> videoOut
                            audioIn -> audioOut
                            else -> -1
                        }
                        if (outTrack >= 0) {
                            info.set(0, size, cursorUs + offsetUs, extractor.sampleFlags)
                            muxer.writeSampleData(outTrack, buffer, info)
                        }
                        extractor.advance()
                    }

                    if (clipLengthUs > 0) cursorUs += clipLengthUs + FRAME_US
                    used++
                } finally {
                    extractor.release()
                }
            }

            onProgress(group.size, group.size)

            if (!started) {
                muxer?.release()
                output.delete()
                return@withContext Outcome(
                    null, 0, skippedOrientation, skippedIncompatible, "Nothing playable to export",
                )
            }

            muxer?.stop()
            muxer?.release()
            return@withContext Outcome(output, used, skippedOrientation, skippedIncompatible, null)
        } catch (t: Throwable) {
            runCatching { muxer?.release() }
            output.delete()
            return@withContext Outcome(
                null, used, skippedOrientation, skippedIncompatible, t.message ?: "Export failed",
            )
        }
    }

    /** First NAL unit (the SPS) of an avcC-style csd buffer. */
    private fun firstNal(csd: ByteArray): ByteArray {
        var start = 0
        // skip a 4- or 3-byte start code
        if (csd.size > 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte() && csd[2] == 0.toByte() && csd[3] == 1.toByte()) {
            start = 4
        } else if (csd.size > 3 && csd[0] == 0.toByte() && csd[1] == 0.toByte() && csd[2] == 1.toByte()) {
            start = 3
        }
        var end = csd.size
        var i = start + 1
        while (i + 2 < csd.size) {
            if (csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() && csd[i + 2] == 1.toByte()) {
                end = i
                if (end > start && csd[end - 1] == 0.toByte()) end -= 1
                break
            }
            i++
        }
        return csd.copyOfRange(start, end)
    }

    private fun ByteBuffer.toBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && a.contentEquals(b)

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "vlog" }
}

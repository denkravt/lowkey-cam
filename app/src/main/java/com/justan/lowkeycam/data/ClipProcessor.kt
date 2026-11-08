package com.justan.lowkeycam.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAudioMixer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Low-key clips are captured portrait so the preview stays the right way up; this trims each one
 * down to the 16:9 band that was framed, giving a normal landscape video with the world upright.
 */
object ClipProcessor {

    /**
     * Largest 16:9 rectangle that fits inside [w]x[h] - the same band the low-key preview shows.
     * Both sides are rounded to even numbers because H.264 encoders require it.
     */
    internal fun band169(w: Int, h: Int): Pair<Int, Int> {
        val bandW: Int
        val bandH: Int
        if (w * 9 >= h * 16) {
            bandH = h
            bandW = h * 16 / 9
        } else {
            bandW = w
            bandH = w * 9 / 16
        }
        return (bandW - bandW % 2).coerceAtLeast(2) to (bandH - bandH % 2).coerceAtLeast(2)
    }

    /**
     * The widest band any of [sources] can fill, so nothing is upscaled past its own detail and
     * smaller clips are scaled up to match the best one.
     */
    internal fun largestBand(sources: List<File>): Pair<Int, Int> =
        sources.map { bandFor(it) }.maxByOrNull { it.first } ?: (1280 to 720)

    /** Reads a clip's displayed size (rotation applied) and returns the 16:9 band it can yield. */
    internal fun bandFor(source: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (vw, vh) = if (rotation % 180 != 0) h to w else w to h
            if (vw <= 0 || vh <= 0) 1280 to 720 else band169(vw, vh)
        } catch (t: Throwable) {
            1280 to 720
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Re-encodes the clips into one 16:9 video with [music] mixed underneath the clips' own audio.
     * The music sequence loops so it always covers the whole vlog.
     */
    @OptIn(markerClass = [UnstableApi::class])
    suspend fun concatWithMusic(
        context: Context,
        sources: List<File>,
        music: File,
        output: File,
        musicVolume: Float = 0.35f,
    ): Boolean {
        if (sources.isEmpty()) return false
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val (outW, outH) = withContext(Dispatchers.IO) { largestBand(sources) }

        withContext(Dispatchers.Main) {
            val clipItems = sources.map { source ->
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    outW,
                                    outH,
                                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                )
                            ),
                        )
                    )
                    .build()
            }

            val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music)))
                .setRemoveVideo(true)
                .setEffects(
                    Effects(
                        listOf(VolumeAudioProcessor(musicVolume.coerceIn(0f, 1f))),
                        emptyList(),
                    )
                )
                .build()

            val composition = Composition.Builder(
                listOf(
                    EditedMediaItemSequence(clipItems),
                    EditedMediaItemSequence(listOf(musicItem), true),
                )
            ).build()

            val transformer = Transformer.Builder(context)
                .setAudioMixerFactory(DefaultAudioMixer.Factory())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure.set(exportException)
                        latch.countDown()
                    }
                })
                .build()

            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure {
                    failure.set(it)
                    latch.countDown()
                }
        }

            withContext(Dispatchers.IO) { latch.await(15, TimeUnit.MINUTES) }
        return failure.get() == null && output.exists() && output.length() > 0L
    }

    /**
     * Re-encodes any number of clips into one 16:9 video, cropping each to the same band.
     * Used when the clips cannot be stitched losslessly (different encoder settings).
     */
    @OptIn(markerClass = [UnstableApi::class])
    suspend fun concatTo16x9(context: Context, sources: List<File>, output: File): Boolean {
        if (sources.isEmpty()) return false
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val (outW, outH) = withContext(Dispatchers.IO) { largestBand(sources) }

        withContext(Dispatchers.Main) {
            val items = sources.map { source ->
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    outW,
                                    outH,
                                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                )
                            ),
                        )
                    )
                    .build()
            }

            val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure.set(exportException)
                        latch.countDown()
                    }
                })
                .build()

            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure {
                    failure.set(it)
                    latch.countDown()
                }
        }

            withContext(Dispatchers.IO) { latch.await(10, TimeUnit.MINUTES) }
        return failure.get() == null && output.exists() && output.length() > 0L
    }

    /** Cuts [source] down to [startMs]..[endMs], keeping everything else (crop, rotation) as is. */
    @OptIn(markerClass = [UnstableApi::class])
    suspend fun trim(context: Context, source: File, startMs: Long, endMs: Long): File? {
        val output = File(source.parentFile, "trim_" + System.currentTimeMillis() + ".mp4")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs.coerceAtLeast(0L))
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val item = EditedMediaItem.Builder(mediaItem).build()

            val composition = Composition.Builder(EditedMediaItemSequence(listOf(item))).build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure.set(exportException)
                        latch.countDown()
                    }
                })
                .build()

            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure {
                    failure.set(it)
                    latch.countDown()
                }
        }

            withContext(Dispatchers.IO) { latch.await(5, TimeUnit.MINUTES) }
        return if (failure.get() == null && output.exists() && output.length() > 0L) {
            output
        } else {
            output.delete()
            null
        }
    }

    @OptIn(markerClass = [UnstableApi::class])
    suspend fun cropTo16x9(context: Context, source: File): File? {
        val (outW, outH) = withContext(Dispatchers.IO) { bandFor(source) }
        val output = File(source.parentFile, "lk_" + System.currentTimeMillis() + ".mp4")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        withContext(Dispatchers.Main) {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            Presentation.createForWidthAndHeight(
                                    outW,
                                    outH,
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                            )
                        ),
                    )
                )
                .build()

            val composition = Composition.Builder(
                EditedMediaItemSequence(listOf(item))
            ).build()

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure.set(exportException)
                        latch.countDown()
                    }
                })
                .build()

            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure {
                    failure.set(it)
                    latch.countDown()
                }
        }

            withContext(Dispatchers.IO) { latch.await(5, TimeUnit.MINUTES) }

        return if (failure.get() == null && output.exists() && output.length() > 0L) {
            output
        } else {
            output.delete()
            null
        }
    }
}

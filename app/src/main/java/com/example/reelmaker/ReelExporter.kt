package com.example.reelmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

/** Trims, crops to 9:16 (1080x1920) and adds an optional logo. Call on the main thread. */
object ReelExporter {

    fun export(
        ctx: Context,
        video: Uri,
        startMs: Long,
        durationMs: Long,
        logo: Uri?,
        logoPos: Int,
        logoWidthFrac: Float,
        out: File,
        onProgress: (Int) -> Unit,
        onResult: (Throwable?) -> Unit
    ) {
        if (out.exists()) out.delete()

        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(startMs + durationMs)
            .build()
        val item = MediaItem.Builder().setUri(video).setClippingConfiguration(clip).build()

        val effects = mutableListOf<Effect>(
            Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        )
        if (logo != null) {
            buildLogoOverlay(ctx, logo, logoPos, logoWidthFrac)?.let {
                effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(it)))
            }
        }

        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList(), effects))
            .build()

        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        lateinit var transformer: Transformer
        var finished = false

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 300)
            }
        }

        transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                    onResult(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished = true
                    onResult(exportException)
                }
            })
            .build()

        transformer.start(edited, out.absolutePath)
        handler.post(poll)
    }

    private fun buildLogoOverlay(ctx: Context, uri: Uri, pos: Int, widthFrac: Float): BitmapOverlay? {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val w = (1080 * widthFrac).toInt().coerceAtLeast(40)
        val h = (src.height * w / src.width.toFloat()).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createScaledBitmap(src, w, h, true)

        // pos: 0 top-right, 1 top-left, 2 bottom-right, 3 bottom-left
        val right = pos == 0 || pos == 2
        val top = pos == 0 || pos == 1
        val settings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(if (right) 1f else -1f, if (top) 1f else -1f)
            .setBackgroundFrameAnchor(
                if (right) 0.92f else -0.92f,
                if (top) 0.94f else -0.80f // keep clear of Reels/Shorts bottom UI
            )
            .build()
        return BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
    }
}

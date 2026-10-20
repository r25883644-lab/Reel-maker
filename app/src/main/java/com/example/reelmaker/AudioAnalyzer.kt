package com.example.reelmaker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Finds the loudest / most dynamic window in a video's audio track. */
object AudioAnalyzer {

    fun durationSec(ctx: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
        } finally {
            r.release()
        }
    }

    /** Returns best start second for a [windowSec]-long clip. */
    fun findBestStartSec(ctx: Context, uri: Uri, windowSec: Int, onProgress: (Int) -> Unit): Int {
        val totalSec = durationSec(ctx, uri)
        if (totalSec <= windowSec) return 0
        val fallback = (totalSec * 0.4).toInt().coerceAtMost(totalSec - windowSec)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) return fallback
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val durUs = totalSec * 1_000_000L

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val sumSq = DoubleArray(totalSec + 2)
            val cnt = LongArray(totalSec + 2)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var lastPct = -1

            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.asShortBuffer()
                        val bucket = (info.presentationTimeUs / 1_000_000L).toInt().coerceIn(0, totalSec + 1)
                        var k = 0
                        var s = 0.0
                        var c = 0L
                        while (sb.remaining() > 0) {
                            val v = sb.get().toDouble()
                            if (k % 8 == 0) { s += v * v; c++ }
                            k++
                        }
                        sumSq[bucket] += s
                        cnt[bucket] += c
                        val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }

            // loudness per second (0..1)
            val rms = DoubleArray(totalSec + 2) { if (cnt[it] > 0) sqrt(sumSq[it] / cnt[it]) / 32768.0 else 0.0 }
            val p1 = DoubleArray(rms.size + 1)
            val p2 = DoubleArray(rms.size + 1)
            for (i in rms.indices) { p1[i + 1] = p1[i] + rms[i]; p2[i + 1] = p2[i] + rms[i] * rms[i] }

            // skip intro (first 5%) and credits (last 8%)
            var lo = (totalSec * 0.05).toInt()
            var hi = totalSec - windowSec - (totalSec * 0.08).toInt()
            if (hi < lo) { lo = 0; hi = totalSec - windowSec }

            var best = fallback
            var bestScore = -1.0
            for (s in lo..hi) {
                val mean = (p1[s + windowSec] - p1[s]) / windowSec
                val variance = (p2[s + windowSec] - p2[s]) / windowSec - mean * mean
                // loud AND dynamic (action / dialogue peaks) beats a flat loud section
                val score = mean + 0.5 * sqrt(variance.coerceAtLeast(0.0))
                if (score > bestScore) { bestScore = score; best = s }
            }
            return best
        } catch (e: Exception) {
            return fallback
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}

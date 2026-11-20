package com.fescobill.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the reference number from a bill photo on the phone (no internet needed), carefully:
 *
 *  1. The whole photo is read (turned if needed; with contrast and sharpening if it is dull or blurry).
 *  2. The app then zooms into the number, enlarges it and reads it again several ways (as is,
 *     sharpened, strongly sharpened, black-and-white).
 *  3. The readings vote digit by digit ([ReadingVote]); digits they disagree on, or that were hard
 *     to read, are reported so the person can compare them with the enlarged picture.
 * The Consumer ID printed on the bill is read too, so the downloaded bill can be checked against it.
 */
class OcrScanner(context: Context) {

    /** The number read from a photo, with the enlarged, sharpened picture of it as proof. */
    class Verified(val candidate: Candidate, val consensus: ReadingVote.Consensus, val proof: Bitmap?) {
        val query: BillQuery get() = BillQuery(consensus.digits, candidate.mode)
    }

    class ScanResult(
        /** The most likely number, read several times; null if no number was found. */
        val best: Verified?,
        /** Other numbers seen on the photo, in case the best one is not the right one. */
        val others: List<Candidate>,
        /** The Consumer ID printed on the bill (10 digits), if it could be read. */
        val consumerId: String?,
    )

    private val appContext = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun scan(uri: Uri, onResult: (ScanResult) -> Unit, onError: (Throwable) -> Unit) {
        executor.execute {
            val result = try {
                try {
                    read(decode(uri, MAX_SIDE))
                } catch (oom: OutOfMemoryError) {
                    // Low-memory phone: read a smaller copy of the photo instead.
                    read(decode(uri, SMALL_SIDE))
                }
            } catch (t: Throwable) {
                main.post { onError(t) }
                return@execute
            }
            main.post { onResult(result) }
        }
    }

    fun close() {
        recognizer.close()
        executor.shutdown()
    }

    // ---- reading ------------------------------------------------------------------------------

    /** A number seen on the photo, and how the photo was turned / scaled when it was seen. */
    private class Seen(val candidate: Candidate, val rotation: Int, val scale: Double)

    private fun recognize(bitmap: Bitmap, rotation: Int): List<OcrLine> =
        toLines(Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)), 30, TimeUnit.SECONDS))

    private fun read(photo: Bitmap): ScanResult {
        // 1. The whole photo, turned until a clear number is found.
        val found = HashMap<String, Seen>()
        var bestRotation = 0
        var bestScore = Int.MIN_VALUE
        for (r in ROTATIONS) {
            val ranked = RefExtractor.extract(recognize(photo, r))
            merge(found, ranked, r, 1.0)
            val top = ranked.firstOrNull()?.score ?: Int.MIN_VALUE
            if (top > bestScore) {
                bestScore = top
                bestRotation = r
            }
            if (top >= RefExtractor.CONFIDENT_SCORE) break
        }
        if (bestScore < RefExtractor.CONFIDENT_SCORE) {
            // Dull or blurry photo: also read a contrast-boosted, sharpened copy of the whole page.
            val upright = rotate(photo, bestRotation)
            val scale = min(1.0, 2400.0 / max(upright.width, upright.height))
            val small = if (scale < 1.0) {
                Bitmap.createScaledBitmap(upright, (upright.width * scale).roundToInt(), (upright.height * scale).roundToInt(), true)
            } else {
                upright
            }
            val enhanced = enhance(small, max(1, max(small.width, small.height) / 1000), 1.2f)
            runCatching { merge(found, RefExtractor.extract(recognize(enhanced, 0)), bestRotation, scale) }
            enhanced.recycle()
            if (small !== upright) small.recycle()
            if (upright !== photo) upright.recycle()
        }

        val ranked = RefExtractor.rank(found.values.map { it.candidate })
        val primary = ranked.firstOrNull { it.mode == SearchMode.REFERENCE } ?: ranked.firstOrNull()
            ?: return ScanResult(null, emptyList(), null)
        val consumerId = ranked.firstOrNull { it.mode == SearchMode.CUSTOMER_ID && it.label == LabelKind.CONSUMER_ID }?.digits
        val seen = found[primary.digits]!!

        // 2. Zoom into the number and read it again in several ways.
        val readings = arrayListOf(ReadingVote.Reading(primary.digits, primary.confidence))
        var proof: Bitmap? = null
        var suffix = primary.suffix
        val box = primary.box?.let { b ->
            val k = 1.0 / seen.scale
            Box((b.left * k).toInt(), (b.top * k).toInt(), (b.right * k).toInt(), (b.bottom * k).toInt())
        }
        if (box != null) {
            val upright = rotate(photo, seen.rotation)
            for ((name, crop) in zoomedCopies(upright, box)) {
                val again = runCatching { RefExtractor.extract(recognize(crop, 0)) }.getOrDefault(emptyList())
                    .filter { it.mode == primary.mode }
                    .minByOrNull { ReadingVote.hamming(it.digits, primary.digits) }
                if (again != null && ReadingVote.hamming(again.digits, primary.digits) <= 3) {
                    readings += ReadingVote.Reading(again.digits, again.confidence)
                    if (suffix == null) suffix = again.suffix
                }
                if (name == PROOF) proof = crop else crop.recycle()
            }
            if (upright !== photo) upright.recycle()
        }
        photo.recycle()

        // 3. Vote digit by digit.
        val consensus = ReadingVote.combine(primary.digits, readings)
        val best = Verified(primary.copy(digits = consensus.digits, suffix = suffix), consensus, proof)
        val others = ranked.filter { it.digits != primary.digits && it.digits != consensus.digits }
        return ScanResult(best, others, consumerId)
    }

    private fun merge(into: MutableMap<String, Seen>, ranked: List<Candidate>, rotation: Int, scale: Double) {
        for (c in ranked) {
            val prev = into[c.digits]
            if (prev == null || c.score > prev.candidate.score) into[c.digits] = Seen(c, rotation, scale)
        }
    }

    /**
     * The number's line cut out of the photo and enlarged so the digits are about 64 pixels tall,
     * in several versions that each help with different kinds of blur.
     */
    private fun zoomedCopies(upright: Bitmap, box: Box): List<Pair<String, Bitmap>> {
        val h = box.height
        val left = (box.left - box.width * 0.15 - h * 2).toInt().coerceAtLeast(0)
        val right = (box.right + box.width * 0.25 + h * 2).toInt().coerceAtMost(upright.width)
        val top = (box.top - h * 0.8).toInt().coerceAtLeast(0)
        val bottom = (box.bottom + h * 0.8).toInt().coerceAtMost(upright.height)
        if (right - left < 8 || bottom - top < 8) return emptyList()
        val cut = Bitmap.createBitmap(upright, left, top, right - left, bottom - top)
        val scale = (64.0 / h).coerceIn(1.0, 4.0).let { s -> min(s, 2400.0 / cut.width).coerceAtLeast(1.0) }
        val big = Bitmap.createScaledBitmap(cut, (cut.width * scale).roundToInt(), (cut.height * scale).roundToInt(), true)
        if (big !== cut) cut.recycle()
        val stroke = max(1, (h * scale / 14).roundToInt())
        return listOf(
            "enlarged" to big,
            PROOF to enhance(big, stroke, 1.2f),
            "strongly sharpened" to enhance(big, stroke, 2.2f),
            "black and white" to blackAndWhite(big, (h * scale * 1.5).roundToInt()),
        )
    }

    /** Contrast stretch + unsharp mask. */
    private fun enhance(source: Bitmap, sharpenRadius: Int, amount: Float): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        var gray = ImageEnhancer.stretch(ImageEnhancer.toGray(px))
        gray = ImageEnhancer.sharpen(gray, w, h, sharpenRadius, amount)
        return Bitmap.createBitmap(ImageEnhancer.toArgb(gray), w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blackAndWhite(source: Bitmap, window: Int): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        val gray = ImageEnhancer.binarize(ImageEnhancer.stretch(ImageEnhancer.toGray(px)), w, h, max(15, window))
        return Bitmap.createBitmap(ImageEnhancer.toArgb(gray), w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap = if (degrees == 0) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    /** Lines with the reader's confidence for every character. */
    private fun toLines(text: Text): List<OcrLine> =
        text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val conf = FloatArray(line.text.length) { 1f }
                var pos = 0
                var any = false
                for (el in line.elements) {
                    val at = line.text.indexOf(el.text, pos)
                    if (at < 0) continue
                    val symbols = el.symbols
                    for (k in el.text.indices) {
                        val c = if (symbols.size == el.text.length) symbols[k].confidence else el.confidence
                        conf[at + k] = c
                        if (c > 0f) any = true
                    }
                    pos = at + el.text.length
                }
                OcrLine(
                    line.text,
                    line.boundingBox?.let { Box(it.left, it.top, it.right, it.bottom) },
                    conf.takeIf { any },
                )
            }
        }

    /** Loads the photo upright and small enough to be safe on low-memory phones. */
    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Cannot open the photo")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file is not a photo")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

        var bitmap: Bitmap? = null
        while (bitmap == null) {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            bitmap = try {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IOException("Cannot read the photo")
            } catch (oom: OutOfMemoryError) {
                if (sample >= 8) throw IOException("The photo is too large")
                sample *= 2
                null
            }
        }

        val degrees = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) {
            0
        }
        if (degrees == 0) return bitmap
        val rotated = rotate(bitmap, degrees)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        /** Full resolution of most phone cameras is kept; small digits need every pixel. */
        const val MAX_SIDE = 4200
        const val SMALL_SIDE = 2200
        const val PROOF = "sharpened"
        val ROTATIONS = intArrayOf(0, 90, 270, 180)
    }
}

package com.fescobill.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max

/** Reads a bill photo on the phone (no internet needed) and returns the likely reference numbers. */
class OcrScanner(context: Context) {

    private val appContext = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun scan(uri: Uri, onResult: (List<Candidate>) -> Unit, onError: (Throwable) -> Unit) {
        executor.execute {
            val bitmap = try {
                decode(uri)
            } catch (t: Throwable) {
                main.post { onError(t) }
                return@execute
            }
            main.post {
                // Try the photo as taken; if no clear number is found, try it turned sideways / upside down.
                recognize(bitmap, ROTATIONS, 0, HashMap(), onResult, onError)
            }
        }
    }

    fun close() {
        recognizer.close()
        executor.shutdown()
    }

    private fun recognize(
        bitmap: Bitmap,
        rotations: IntArray,
        index: Int,
        found: MutableMap<String, Candidate>,
        onResult: (List<Candidate>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        recognizer.process(InputImage.fromBitmap(bitmap, rotations[index]))
            .addOnSuccessListener { text ->
                for (c in RefExtractor.extract(toLines(text))) {
                    val prev = found[c.digits]
                    if (prev == null || c.score > prev.score) found[c.digits] = c
                }
                val ranked = RefExtractor.rank(found.values)
                val good = (ranked.firstOrNull()?.score ?: 0) >= RefExtractor.CONFIDENT_SCORE
                if (good || index == rotations.lastIndex) {
                    onResult(ranked)
                } else {
                    recognize(bitmap, rotations, index + 1, found, onResult, onError)
                }
            }
            .addOnFailureListener { e ->
                if (found.isNotEmpty()) onResult(RefExtractor.rank(found.values)) else onError(e)
            }
    }

    private fun toLines(text: Text): List<OcrLine> =
        text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                OcrLine(line.text, line.boundingBox?.let { Box(it.left, it.top, it.right, it.bottom) })
            }
        }

    /** Loads the photo upright and small enough to be safe on low-memory phones. */
    private fun decode(uri: Uri): Bitmap {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Cannot open the photo")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file is not a photo")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2

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
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val MAX_SIDE = 3300
        val ROTATIONS = intArrayOf(0, 90, 270, 180)
    }
}

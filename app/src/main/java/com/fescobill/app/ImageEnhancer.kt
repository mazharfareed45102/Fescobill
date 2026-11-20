package com.fescobill.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes blurry or dull photos of printed numbers easier to read. Works on grey levels (0 = black,
 * 255 = white) stored row by row, so it runs the same on the phone and in tests.
 */
object ImageEnhancer {

    /** Grey level of ARGB pixels (the way the eye sees brightness). */
    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    fun toArgb(gray: IntArray): IntArray = IntArray(gray.size) { i ->
        val v = gray[i].coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Spreads the grey levels so the darkest 1 % become black and the brightest 1 % white. */
    fun stretch(gray: IntArray, lowFraction: Double = 0.01, highFraction: Double = 0.99): IntArray {
        if (gray.isEmpty()) return gray
        val histogram = IntArray(256)
        for (v in gray) histogram[v.coerceIn(0, 255)]++
        val lowCount = (gray.size * lowFraction).toLong()
        val highCount = (gray.size * highFraction).toLong()
        var low = 0
        var high = 255
        var seen = 0L
        for (v in 0..255) {
            seen += histogram[v]
            if (seen > lowCount) {
                low = v
                break
            }
        }
        seen = 0L
        for (v in 0..255) {
            seen += histogram[v]
            if (seen >= highCount) {
                high = v
                break
            }
        }
        if (high - low < 8) return gray.copyOf()
        val range = (high - low).toDouble()
        return IntArray(gray.size) { i -> (((gray[i] - low) / range) * 255.0).roundToInt().coerceIn(0, 255) }
    }

    /** A soft blur (three box blurs in a row look almost exactly like a Gaussian blur). */
    fun blur(gray: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return gray.copyOf()
        var a = gray.copyOf()
        val tmp = IntArray(gray.size)
        repeat(3) {
            boxHorizontal(a, tmp, width, height, radius)
            boxVertical(tmp, a, width, height, radius)
        }
        return a
    }

    /**
     * Unsharp mask: subtracts a blurred copy to make the edges of the digits crisp again.
     * [radius] should be about the stroke width of the digits; [amount] 1.0 = normal, 2.0 = strong.
     */
    fun sharpen(gray: IntArray, width: Int, height: Int, radius: Int, amount: Float): IntArray {
        val blurred = blur(gray, width, height, radius)
        return IntArray(gray.size) { i ->
            (gray[i] + amount * (gray[i] - blurred[i])).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Turns the picture into pure black and white, comparing every pixel with the average of its
     * neighbourhood ([window] pixels wide), so uneven light and shadows do not matter.
     */
    fun binarize(gray: IntArray, width: Int, height: Int, window: Int, bias: Int = 12): IntArray {
        val w1 = width + 1
        val integral = LongArray(w1 * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                integral[(y + 1) * w1 + x + 1] = integral[y * w1 + x + 1] + rowSum
            }
        }
        val half = max(1, window / 2)
        val out = IntArray(gray.size)
        for (y in 0 until height) {
            val y0 = max(0, y - half)
            val y1 = min(height, y + half + 1)
            for (x in 0 until width) {
                val x0 = max(0, x - half)
                val x1 = min(width, x + half + 1)
                val sum = integral[y1 * w1 + x1] - integral[y0 * w1 + x1] - integral[y1 * w1 + x0] + integral[y0 * w1 + x0]
                val mean = sum / ((x1 - x0) * (y1 - y0))
                out[y * width + x] = if (gray[y * width + x] < mean - bias) 0 else 255
            }
        }
        return out
    }

    private fun boxHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, r: Int) {
        val size = 2 * r + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                dst[row + x] = sum / size
                val add = (x + r + 1).coerceAtMost(width - 1)
                val remove = (x - r).coerceAtLeast(0)
                sum += src[row + add] - src[row + remove]
            }
        }
    }

    private fun boxVertical(src: IntArray, dst: IntArray, width: Int, height: Int, r: Int) {
        val size = 2 * r + 1
        for (x in 0 until width) {
            var sum = 0
            for (k in -r..r) sum += src[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                dst[y * width + x] = sum / size
                val add = (y + r + 1).coerceAtMost(height - 1)
                val remove = (y - r).coerceAtLeast(0)
                sum += src[add * width + x] - src[remove * width + x]
            }
        }
    }
}

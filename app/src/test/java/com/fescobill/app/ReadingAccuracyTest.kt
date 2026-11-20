package com.fescobill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAccuracyTest {

    private fun conf(vararg pairs: Pair<Int, Float>, n: Int = 14) = FloatArray(n) { 0.95f }.also { a ->
        for ((i, v) in pairs) a[i] = v
    }

    @Test
    fun allReadingsAgree() {
        val r = ReadingVote.combine(
            "07135230628703",
            List(4) { ReadingVote.Reading("07135230628703", conf()) },
        )
        assertEquals("07135230628703", r.digits)
        assertTrue(r.sure)
        assertEquals(4, r.agreeing)
    }

    @Test
    fun majorityFixesAMisreadDigit() {
        // The blurry photo read the "8" as "3" once; the enlarged, sharpened copies read "8".
        val r = ReadingVote.combine(
            "07135230623703",
            listOf(
                ReadingVote.Reading("07135230623703", conf(10 to 0.45f)),
                ReadingVote.Reading("07135230628703", conf()),
                ReadingVote.Reading("07135230628703", conf()),
                ReadingVote.Reading("07135230628703", conf()),
            ),
        )
        assertEquals("07135230628703", r.digits)
        assertTrue(r.uncertain.isEmpty())
        assertTrue(r.sure)
    }

    @Test
    fun disagreementIsFlaggedForThePerson() {
        val r = ReadingVote.combine(
            "07135230628703",
            listOf(
                ReadingVote.Reading("07135230628703", conf(9 to 0.6f)),
                ReadingVote.Reading("07135230623703", conf(9 to 0.6f)),
            ),
        )
        assertEquals(setOf(10), r.uncertain)
        assertFalse(r.sure)
    }

    @Test
    fun lowConfidenceIsFlaggedEvenIfAllAgree() {
        val r = ReadingVote.combine(
            "07135230628703",
            List(3) { ReadingVote.Reading("07135230628703", conf(4 to 0.3f)) },
        )
        assertEquals(setOf(4), r.uncertain)
        assertFalse(r.sure)
    }

    @Test
    fun otherNumbersOnTheBillAreIgnored() {
        // The old account number (16135231566107) is far from the reference number and must not vote.
        val r = ReadingVote.combine(
            "07135230628703",
            listOf(
                ReadingVote.Reading("07135230628703", conf()),
                ReadingVote.Reading("16135231566107", conf()),
                ReadingVote.Reading("07135230628703", conf()),
            ),
        )
        assertEquals(2, r.readings)
        assertTrue(r.sure)
    }

    @Test
    fun singleReadingIsNeverSure() {
        val r = ReadingVote.combine("07135230628703", listOf(ReadingVote.Reading("07135230628703", conf())))
        assertFalse(r.sure)
        assertTrue(r.uncertain.isEmpty())
    }

    @Test
    fun stretchUsesTheFullRange() {
        val dull = IntArray(100) { 100 + (it % 50) } // greys between 100 and 149 only
        val out = ImageEnhancer.stretch(dull)
        assertEquals(0, out.minOrNull())
        assertEquals(255, out.maxOrNull())
    }

    @Test
    fun sharpenRestoresBlurredEdge() {
        // A dark digit stroke on white paper, blurred like an out-of-focus photo.
        val w = 40
        val h = 10
        val crisp = IntArray(w * h) { i -> if (i % w in 18..21) 0 else 255 }
        val blurred = ImageEnhancer.blur(crisp, w, h, 2)
        val sharp = ImageEnhancer.sharpen(blurred, w, h, 2, 1.5f)
        val mid = 5 * w
        // Contrast between the stroke and the paper next to it grows back.
        assertTrue(sharp[mid + 15] - sharp[mid + 19] > blurred[mid + 15] - blurred[mid + 19])
    }

    @Test
    fun binarizeGivesBlackTextOnWhite() {
        val w = 30
        val h = 30
        // Uneven light: the paper gets darker to the right; the stroke stays darker than its surroundings.
        val gray = IntArray(w * h) { i ->
            val x = i % w
            val paper = 230 - x * 3
            if (x in 14..15) paper - 90 else paper
        }
        val bw = ImageEnhancer.binarize(gray, w, h, window = 15)
        assertTrue(bw.all { it == 0 || it == 255 })
        assertEquals(0, bw[10 * w + 14])
        assertEquals(255, bw[10 * w + 5])
        assertEquals(255, bw[10 * w + 25])
    }

    @Test
    fun extractorKeepsDigitConfidence() {
        val text = "07135230628703R"
        val conf = FloatArray(text.length) { 0.9f }.also { it[3] = 0.2f }
        val c = RefExtractor.extract(listOf(OcrLine(text, Box(0, 0, 300, 20), conf))).first()
        assertEquals("07135230628703", c.digits)
        assertEquals(14, c.confidence!!.size)
        assertEquals(0.2f, c.confidence!![3], 0.0001f)
        assertTrue(c.box != null)
    }
}

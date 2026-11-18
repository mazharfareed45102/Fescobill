package com.fescobill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layouts modelled on a real FESCO bill photo (positions in pixels of the upright photo). */
class RefExtractorTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, Box(l, t, r, b))

    /** Every table cell read as its own line, like ML Kit usually does. */
    private val billCells = listOf(
        line("ELECTRIC SUPPLY COMPANY", 340, 600, 850, 635),
        line("21300007253", 230, 640, 335, 657),
        line("Trns:code TIBATRAGSHARIF", 375, 640, 680, 657),
        line("CONNECTION DATE", 52, 692, 170, 707),
        line("16MAY24", 115, 718, 178, 735),
        line("CONSUMER ID", 52, 755, 150, 770),
        line("TARIFF", 238, 755, 285, 770),
        line("LOAD", 344, 755, 380, 770),
        line("OLD A/C NUMBER", 428, 755, 545, 770),
        line("1135484984", 76, 778, 190, 800),
        line("A-1a(01)", 282, 778, 340, 795),
        line("3.00", 383, 778, 410, 795),
        line("16135231566107", 487, 780, 625, 797),
        line("REFERENCE No.", 52, 815, 150, 830),
        line("Transformer Code", 238, 815, 315, 830),
        line("07135230628703R", 78, 838, 262, 862),
        line("Registeryourcomplaintat118/SMS-8118", 320, 836, 668, 858),
        line("CNIC: 923063055712", 340, 925, 520, 942),
        line("3830119105891", 425, 900, 525, 917),
        line("METER No.", 40, 1010, 110, 1025),
        line("S-P7125267", 68, 1035, 150, 1052),
        line("916 922 1 6", 210, 1035, 470, 1052),
    )

    @Test
    fun findsReferenceNumberOnBill() {
        val result = RefExtractor.extract(billCells)
        val best = result.first()
        assertEquals("07135230628703", best.digits)
        assertEquals(SearchMode.REFERENCE, best.mode)
        assertEquals('R', best.suffix)
        assertEquals(LabelKind.REFERENCE, best.label)
        assertEquals("07 13523 0628703 R", best.display)
        assertTrue(RefExtractor.isConfident(result))
    }

    @Test
    fun oldAccountAndConsumerIdRankBelowReference() {
        val result = RefExtractor.extract(billCells)
        val old = result.first { it.digits == "16135231566107" }
        assertEquals(LabelKind.OLD_ACCOUNT, old.label)
        val consumer = result.first { it.digits == "1135484984" }
        assertEquals(SearchMode.CUSTOMER_ID, consumer.mode)
        assertEquals(LabelKind.CONSUMER_ID, consumer.label)
        assertTrue(result.first().score - old.score >= RefExtractor.CONFIDENT_GAP)
    }

    @Test
    fun worksWhenWholeTableRowsAreOneLine() {
        val rows = listOf(
            line("CONSUMER ID TARIFF LOAD OLD A/C NUMBER", 52, 755, 545, 770),
            line("1135484984 A-1a(01) 3.00 16135231566107", 76, 778, 625, 800),
            line("REFERENCE No. Transformer Code GOVT Dept Code", 52, 815, 560, 830),
            line("07135230628703R Registeryourcomplaintat118/SMS-8118", 78, 836, 668, 862),
        )
        val result = RefExtractor.extract(rows)
        assertEquals("07135230628703", result.first().digits)
        assertTrue(RefExtractor.isConfident(result))
    }

    @Test
    fun withoutTheSuffixTheLabelStillWins() {
        val cells = billCells.map {
            if (it.text == "07135230628703R") it.copy(text = "07135230628703") else it
        }
        val result = RefExtractor.extract(cells)
        assertEquals("07135230628703", result.first().digits)
        assertTrue(RefExtractor.isConfident(result))
    }

    @Test
    fun fixesCommonOcrConfusions() {
        val result = RefExtractor.extract(listOf(line("REFERENCE No.", 52, 815, 150, 830), line("O71352306Z8703R", 78, 838, 262, 862)))
        assertEquals("07135230628703", result.first().digits)
    }

    @Test
    fun readsSpacedNumberWithoutPositions() {
        val result = RefExtractor.extract(
            listOf(OcrLine("REFERENCE No.", null), OcrLine("07 13523 0628703 R", null))
        )
        val best = result.first()
        assertEquals("07135230628703", best.digits)
        assertEquals('R', best.suffix)
        assertEquals(LabelKind.REFERENCE, best.label)
    }

    @Test
    fun labelOnSameLine() {
        val result = RefExtractor.extract(listOf(line("REFERENCE No. 07135230628703 U", 10, 10, 400, 30)))
        assertEquals("07135230628703", result.first().digits)
        assertEquals('U', result.first().suffix)
    }

    @Test
    fun suffixMisreadAsDigitStillOffersNumber() {
        val result = RefExtractor.extract(listOf(line("REFERENCE No.", 52, 815, 150, 830), line("071352306287038", 78, 838, 262, 862)))
        assertTrue(result.any { it.digits == "07135230628703" })
    }

    @Test
    fun onlyOldAccountNumberIsNotOfferedAsSure() {
        val result = RefExtractor.extract(
            listOf(line("OLD A/C NUMBER", 428, 755, 545, 770), line("16135231566107", 487, 780, 625, 797))
        )
        assertEquals(LabelKind.OLD_ACCOUNT, result.single().label)
        assertFalse(RefExtractor.isConfident(result))
    }

    @Test
    fun ignoresTextWithoutNumbers() {
        assertTrue(RefExtractor.extract(listOf(OcrLine("Say No to Drugs", null), OcrLine("SS SS 26", null))).isEmpty())
    }

    @Test
    fun cnicAndPhoneAreNotCandidates() {
        val result = RefExtractor.extract(listOf(OcrLine("CNIC 3830119105891", null), OcrLine("Mob 923063055712", null)))
        assertTrue(result.isEmpty())
    }
}

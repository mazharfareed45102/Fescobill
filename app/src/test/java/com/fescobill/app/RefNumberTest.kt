package com.fescobill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefNumberTest {
    @Test
    fun parsesReferenceWithSuffixAndSpaces() {
        val q = RefNumber.parse("07 13523 0628703 R")!!
        assertEquals("07135230628703", q.digits)
        assertEquals(SearchMode.REFERENCE, q.mode)
        assertEquals("07 13523 0628703", q.display)
    }

    @Test
    fun parsesPrintedForm() {
        assertEquals("07135230628703", RefNumber.parse("07135230628703R")!!.digits)
        assertEquals("07135230628703", RefNumber.parse("07-13523-0628703")!!.digits)
    }

    @Test
    fun parsesUrduDigits() {
        val q = RefNumber.parse("۰۷۱۳۵۲۳۰۶۲۸۷۰۳")!!
        assertEquals("07135230628703", q.digits)
    }

    @Test
    fun parsesCustomerId() {
        val q = RefNumber.parse("1135484984")!!
        assertEquals(SearchMode.CUSTOMER_ID, q.mode)
        assertEquals("appno", q.mode.param)
    }

    @Test
    fun rejectsWrongLengths() {
        assertNull(RefNumber.parse(""))
        assertNull(RefNumber.parse("0713523062870"))
        assertNull(RefNumber.parse("071352306287031"))
        assertNull(RefNumber.parse("3830119105891")) // CNIC
    }

    @Test
    fun findsAllNumbersInAMessage() {
        val message = """
            Assalam o alaikum beta, ye 6 bills hain:
            1) 07135230628703R
            2) 07 13523 0628704 U
            3. 07-13523-0628705
            Ghar: 07135230628706  Dukan: 07135230628707
            Customer ID 1135484984
            Phone 03001234567 CNIC 38301-1910589-1
            07135230628703 (same as first)
        """.trimIndent()
        assertEquals(
            listOf("07135230628703", "07135230628704", "07135230628705", "07135230628706", "07135230628707", "1135484984"),
            RefNumber.findAll(message).map { it.digits },
        )
    }

    @Test
    fun findsUrduDigitsAndNothingInPlainText() {
        assertEquals(listOf("07135230628703"), RefNumber.findAll("بل ۰۷۱۳۵۲۳۰۶۲۸۷۰۳").map { it.digits })
        assertTrue(RefNumber.findAll("no numbers here").isEmpty())
    }

    @Test
    fun buildsOfficialUrls() {
        assertEquals(
            "https://bill.pitc.com.pk/fescobill/general?refno=07135230628703",
            Pitc.billUrl(RefNumber.parse("07135230628703R")!!)
        )
        assertEquals(
            "https://bill.pitc.com.pk/fescobill/general?appno=1135484984",
            Pitc.billUrl(RefNumber.parse("1135484984")!!)
        )
    }
}

package com.fescobill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

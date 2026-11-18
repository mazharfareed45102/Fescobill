package com.fescobill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitcHtmlTest {
    @Test
    fun recognisesBillAndForm() {
        assertTrue(PitcHtml.isBill(PitcPages.bill(), "https://bill.pitc.com.pk/fescobill/general?refno=1"))
        assertTrue(PitcHtml.isBill(PitcPages.bill(), "https://bill.pitc.com.pk/anything"))
        // Words inside <script> on the search page must not count as a bill.
        assertFalse(PitcHtml.isBill(PitcPages.form(), "https://bill.pitc.com.pk/fescobill"))
        assertTrue(PitcHtml.hasSearchForm(PitcPages.form()))
        assertFalse(PitcHtml.hasSearchForm(PitcPages.bill()))
    }

    @Test
    fun readsBillMonth() {
        assertEquals("OCT 26", PitcHtml.billMonth(PitcPages.bill()))
        assertEquals("NOV 26", PitcHtml.billMonthFromText("BILL MONTH: NOV-26 DUE DATE 30NOV26"))
        assertNull(PitcHtml.billMonth(PitcPages.form()))
    }

    @Test
    fun fillsSearchFormLikeABrowser() {
        val form = PitcHtml.searchForm(PitcPages.form(token = "a&amp;b"), PitcPages.REF, SearchMode.REFERENCE)
        assertNotNull(form)
        form!!
        assertEquals("/fescobill", form.action)
        assertEquals("post", form.method)
        assertEquals(
            listOf(
                "__RequestVerificationToken" to "a&b",
                "rbSearchByList" to "refno",
                "searchTextBox" to PitcPages.REF,
                "ruCodeTextBox" to "",
                "btnSearch" to "Search",
            ),
            form.fields,
        )
    }

    @Test
    fun customerIdUsesAppno() {
        val form = PitcHtml.searchForm(PitcPages.form(), PitcPages.CID, SearchMode.CUSTOMER_ID)!!
        assertTrue("rbSearchByList" to "appno" in form.fields)
        assertTrue("searchTextBox" to PitcPages.CID in form.fields)
    }

    @Test
    fun readsSiteErrorMessage() {
        assertEquals("Invalid Reference Number", PitcHtml.errorMessage(PitcPages.form(error = "Invalid Reference Number")))
        assertEquals("", PitcHtml.errorMessage(PitcPages.form()))
    }

    @Test
    fun noFormMeansNull() {
        assertNull(PitcHtml.searchForm(PitcPages.bill(), PitcPages.REF, SearchMode.REFERENCE))
    }
}

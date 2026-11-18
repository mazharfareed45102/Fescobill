package com.fescobill.app

/** How the PITC bill site is searched: by 14-digit reference number or by 10-digit customer ID. */
enum class SearchMode(val param: String) {
    REFERENCE("refno"),
    CUSTOMER_ID("appno");

    companion object {
        fun fromParam(param: String?): SearchMode = entries.firstOrNull { it.param == param } ?: REFERENCE
    }
}

data class BillQuery(val digits: String, val mode: SearchMode) {
    val display: String get() = RefNumber.format(digits)
}

object RefNumber {
    const val REFERENCE_LENGTH = 14
    const val CUSTOMER_ID_LENGTH = 10

    /**
     * Reads what the user typed or pasted ("07135230628703R", "07 13523 0628703 R",
     * Urdu/Arabic digits, ...) and returns a query, or null if it is not a valid number.
     * The trailing U/R letter printed on bills is not needed by the website.
     */
    fun parse(input: String): BillQuery? {
        val digits = digitsOf(input)
        return when (digits.length) {
            REFERENCE_LENGTH -> BillQuery(digits, SearchMode.REFERENCE)
            CUSTOMER_ID_LENGTH -> BillQuery(digits, SearchMode.CUSTOMER_ID)
            else -> null
        }
    }

    /** Keeps only digits, converting any Unicode digit (e.g. ۰۷۱۳) to ASCII. */
    fun digitsOf(input: CharSequence): String = buildString {
        for (c in input) {
            val d = Character.digit(c, 10)
            if (d >= 0) append('0' + d)
        }
    }

    /** "07135230628703" -> "07 13523 0628703" (batch, sub-division, account). */
    fun format(digits: String): String =
        if (digits.length == REFERENCE_LENGTH) {
            "${digits.substring(0, 2)} ${digits.substring(2, 7)} ${digits.substring(7)}"
        } else {
            digits
        }
}

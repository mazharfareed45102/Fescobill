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

    private val NUMBER_RUN = Regex("\\d[\\d \\t\\-]*\\d")
    private val SEPARATORS = Regex("[ \\t\\-]+")

    /**
     * Every reference number (14 digits) and customer ID (10 digits) in a text, e.g. a WhatsApp
     * message with several bills, in order and without repeats. Spaces and dashes inside a number
     * ("07 13523 0628703") are allowed; numbers on separate lines or after each other both work.
     */
    fun findAll(text: String): List<BillQuery> {
        val ascii = buildString {
            for (c in text) {
                val d = Character.digit(c, 10)
                append(if (d >= 0 && !c.isWhitespace()) '0' + d else c)
            }
        }
        val out = LinkedHashMap<String, BillQuery>()
        for (m in NUMBER_RUN.findAll(ascii)) {
            val groups = m.value.split(SEPARATORS).filter { it.isNotEmpty() }
            val whole = parse(groups.joinToString(""))
            if (whole != null) {
                out.putIfAbsent(whole.digits, whole)
                continue
            }
            // Several numbers on one line: take each group, joining split-up ones ("07 13523 0628703").
            var i = 0
            while (i < groups.size) {
                var joined = ""
                var used = 0
                for (j in i until groups.size) {
                    joined += groups[j]
                    if (joined.length > REFERENCE_LENGTH) break
                    if (joined.length == REFERENCE_LENGTH || (joined.length == CUSTOMER_ID_LENGTH && j == i)) used = j - i + 1
                }
                if (used > 0) {
                    val q = parse(groups.subList(i, i + used).joinToString(""))!!
                    out.putIfAbsent(q.digits, q)
                    i += used
                } else {
                    i++
                }
            }
        }
        return out.values.toList()
    }

    /** "07135230628703" -> "07 13523 0628703" (batch, sub-division, account). */
    fun format(digits: String): String =
        if (digits.length == REFERENCE_LENGTH) {
            "${digits.substring(0, 2)} ${digits.substring(2, 7)} ${digits.substring(7)}"
        } else {
            digits
        }
}

package com.fescobill.app

import kotlin.math.min

/**
 * Reads pages of the PITC bill site without a browser (used by [BillFetcher]).
 * The bill markers must stay in step with assets/pitc_detect.js, which does the same in the WebView.
 */
object PitcHtml {

    val MARKERS = listOf(
        "CONSUMER ID", "REFERENCE NO", "BILL MONTH", "DUE DATE", "ISSUE DATE",
        "READING DATE", "TARIFF", "PAYABLE WITHIN DUE DATE", "PAYABLE AFTER DUE DATE",
        "UNITS CONSUMED", "METER NO", "GRAND TOTAL", "CONSUMER DETAIL", "CURRENT BILL", "ARREARS",
        "FEEDER", "SANCTIONED LOAD", "SAN LOAD", "ENERGY CHARGES", "LP SURCHARGE", "L.P SURCHARGE",
    )

    private val IC_DOT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val HIDDEN_BLOCKS = Regex("<(script|style|noscript|template)\\b[^>]*>.*?</\\1\\s*>", IC_DOT)
    private val TAGS = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")
    private val BILL_PATH = Regex("/(general|industrial)\\b")
    private val BILL_QUERY = Regex("(refno|appno)=")
    private val SEARCH_BOX = Regex("name\\s*=\\s*[\"']?searchTextBox\\b", RegexOption.IGNORE_CASE)
    private val FORM = Regex("<form\\b([^>]*)>(.*?)</form\\s*>", IC_DOT)
    private val INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val BUTTON = Regex("<button\\b([^>]*)>(.*?)</button\\s*>", IC_DOT)
    private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")
    private val ERROR_BY_CLASS = Regex(
        "<(div|span|p|ul|li|label|strong|b|font|td|h\\d)\\b[^>]*class\\s*=\\s*[\"'][^\"']*" +
            "(validation-summary-errors|field-validation-error|alert-danger|alert-warning|text-danger|error-message)" +
            "[^\"']*[\"'][^>]*>(.*?)</\\1\\s*>",
        IC_DOT,
    )
    private val ERROR_BY_ID = Regex("<(span|div|p|label)\\b[^>]*id\\s*=\\s*[\"']lblMessage[\"'][^>]*>(.*?)</\\1\\s*>", IC_DOT)
    private val MONTH = Regex("(?:^|[^A-Z0-9])(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[ -]?(\\d{2})(?![0-9])")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    /** The text a person would read on the page, on one line. */
    fun visibleText(html: String): String {
        var s = COMMENTS.replace(html, " ")
        s = HIDDEN_BLOCKS.replace(s, " ")
        s = TAGS.replace(s, " ")
        return decodeEntities(s).replace(SPACES, " ").trim()
    }

    fun markerCount(html: String): Int {
        val text = visibleText(html).uppercase()
        return MARKERS.count { text.contains(it) }
    }

    /** True when the page is an electricity bill (not the search page or an error page). */
    fun isBill(html: String, url: String): Boolean {
        val found = markerCount(html)
        val u = url.lowercase()
        val billUrl = BILL_PATH.containsMatchIn(u) && BILL_QUERY.containsMatchIn(u)
        return found >= 6 || (billUrl && found >= 3)
    }

    fun hasSearchForm(html: String): Boolean = SEARCH_BOX.containsMatchIn(html)

    /** The bill month printed on the bill, e.g. "OCT 26", or null. */
    fun billMonth(html: String): String? = billMonthFromText(visibleText(html).uppercase())

    fun billMonthFromText(upperText: String): String? {
        val i = upperText.indexOf("BILL MONTH")
        if (i < 0) return null
        val window = upperText.substring(i + "BILL MONTH".length, min(upperText.length, i + 400))
        val m = MONTH.find(window) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2]}"
    }

    /** The site's own error text on the search page, e.g. "Invalid Reference Number", or "". */
    fun errorMessage(html: String): String {
        val byClass = ERROR_BY_CLASS.findAll(html).map { visibleText(it.groupValues[3]) }.firstOrNull { it.isNotBlank() }
        val text = byClass ?: ERROR_BY_ID.findAll(html).map { visibleText(it.groupValues[2]) }.firstOrNull { it.isNotBlank() }
        return text?.take(200).orEmpty()
    }

    data class SearchForm(val action: String, val method: String, val fields: List<Pair<String, String>>)

    /** The search form with the number filled in, exactly as a browser would submit it, or null. */
    fun searchForm(html: String, digits: String, mode: SearchMode): SearchForm? {
        val form = FORM.findAll(html).firstOrNull { hasSearchForm(it.value) } ?: return null
        val formAttrs = attributes(form.groupValues[1])
        val body = form.groupValues[2]
        val fields = ArrayList<Pair<String, String>>()
        var sawRadio = false
        var sawSearchBox = false
        var submit: Pair<String, String>? = null

        for (m in INPUT.findAll(body)) {
            val a = attributes(m.groupValues[1])
            val name = a["name"] ?: continue
            if ("disabled" in a) continue
            when (a["type"]?.lowercase() ?: "text") {
                "hidden" -> fields += name to a["value"].orEmpty()
                "radio" -> if (name == "rbSearchByList") {
                    if (!sawRadio) {
                        fields += name to mode.param
                        sawRadio = true
                    }
                } else if ("checked" in a) {
                    fields += name to (a["value"] ?: "on")
                }
                "checkbox" -> if ("checked" in a) fields += name to (a["value"] ?: "on")
                "submit", "image" -> if (submit == null || name == "btnSearch") submit = name to a["value"].orEmpty()
                "button", "reset", "file" -> Unit
                else -> if (name == "searchTextBox") {
                    fields += name to digits
                    sawSearchBox = true
                } else {
                    fields += name to a["value"].orEmpty()
                }
            }
        }
        if (submit == null) {
            for (m in BUTTON.findAll(body)) {
                val a = attributes(m.groupValues[1])
                val name = a["name"] ?: continue
                if ((a["type"]?.lowercase() ?: "submit") == "submit") {
                    submit = name to (a["value"] ?: visibleText(m.groupValues[2]))
                    break
                }
            }
        }
        if (!sawSearchBox) return null
        if (!sawRadio) fields += "rbSearchByList" to mode.param
        submit?.let { fields += it }
        return SearchForm(formAttrs["action"].orEmpty(), (formAttrs["method"] ?: "post").lowercase(), fields)
    }

    private fun attributes(tagInner: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(tagInner)) {
            val name = m.groupValues[1].lowercase()
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            if (name !in map) map[name] = decodeEntities(value)
        }
        return map
    }

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            val code = when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
                e.startsWith("#") -> e.substring(1).toIntOrNull()
                else -> null
            }
            when {
                code != null -> runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e == "nbsp" -> " "
                else -> m.value
            }
        }
    }
}

package com.fescobill.app

/**
 * The official web-bill site run by PITC (Power Information Technology Company) for FESCO.
 *
 * Searching on https://bill.pitc.com.pk/fescobill posts the form and then opens
 * https://bill.pitc.com.pk/fescobill/general?refno=<14 digits> (or ?appno=<customer id>).
 * The app opens that address directly and, if the site wants the form instead, fills in
 * the form on the page and presses Search, exactly like a person would.
 */
object Pitc {
    const val FORM_URL = "https://bill.pitc.com.pk/fescobill"

    fun billUrl(q: BillQuery): String = "$FORM_URL/general?${q.mode.param}=${q.digits}"

    fun isPitcHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "pitc.com.pk" || h.endsWith(".pitc.com.pk")
    }
}

package com.fescobill.app

/** Sample pages shaped like the PITC bill site, shared by the tests. */
object PitcPages {
    const val REF = "07135230628703"
    const val CID = "1135484984"

    fun form(token: String = "tok123", error: String = "", mode: String = "refno") = """
        <!doctype html><html><head><title>FESCO Web Bill</title>
        <script>var x = "BILL MONTH DUE DATE TARIFF ARREARS FEEDER CURRENT BILL";</script></head><body>
        <h2>Search Your Electricity Bill</h2>
        <form id="SubmitForm" method="post" action="/fescobill">
          <input name="__RequestVerificationToken" type="hidden" value="$token" />
          <label><input type="radio" name="rbSearchByList" value="refno" ${if (mode == "refno") "checked" else ""}> Reference No</label>
          <label><input type="radio" name="rbSearchByList" value="appno" ${if (mode == "appno") "checked" else ""}> Customer ID</label>
          <input type="text" id="searchTextBox" name="searchTextBox" maxlength="14">
          <input type="hidden" id="ruCodeTextBox" name="ruCodeTextBox" value="">
          <input type="submit" id="btnSearch" name="btnSearch" value="Search">
          ${if (error.isNotEmpty()) "<div class=\"validation-summary-errors\"><ul><li>$error</li></ul></div>" else ""}
        </form></body></html>
    """.trimIndent()

    /** The search form with a code picture (captcha), which only a person can type. */
    fun formWithCode(error: String = "") = form(error = error).replace(
        "<input type=\"submit\"",
        "<img id=\"captchaImage\" src=\"/captcha.png\" alt=\"code\"><input type=\"text\" name=\"CaptchaCode\" id=\"CaptchaCode\">\n          <input type=\"submit\"",
    )

    fun bill() = """
        <!doctype html><html><head><title>FESCO Bill</title></head><body>
        <table><tr><td>CONNECTION DATE</td><td>CONNECTED LOAD</td><td>BILL MONTH</td><td>READING DATE</td><td>ISSUE DATE</td><td>DUE DATE</td></tr>
        <tr><td>16MAY24</td><td>0.00</td><td>OCT26</td><td>09OCT26</td><td>13OCT26</td><td>30OCT26</td></tr>
        <tr><td>CONSUMER ID</td><td>TARIFF</td><td>LOAD</td><td>OLD A/C NUMBER</td></tr>
        <tr><td>$CID</td><td>A-1a(01)</td><td>3.00</td><td>16135231566107</td></tr>
        <tr><td>REFERENCE No.</td><td>${REF}R</td><td>FEEDER</td></tr>
        <tr><td>METER No.</td><td>UNITS CONSUMED</td><td>ARREARS</td><td>CURRENT BILL</td></tr>
        <tr><td>PAYABLE WITHIN DUE DATE</td><td>922</td><td>PAYABLE AFTER DUE DATE</td><td>956</td></tr></table>
        </body></html>
    """.trimIndent()
}

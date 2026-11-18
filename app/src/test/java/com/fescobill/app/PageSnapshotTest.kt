package com.fescobill.app

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PageSnapshotTest {
    private lateinit var server: MockWebServer
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/css/bill.css" -> MockResponse().setHeader("Content-Type", "text/css")
                    .setBody(".logo{background:url('../img/bg.png')} @font-face{src:url(/fonts/a.woff2)}")
                "/img/bg.png", "/img/logo.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                "/fonts/a.woff2" -> MockResponse().setBody("font")
                "/js/qr.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                    .setBody("var s = '</script>'; drawQr();")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val page = """
        <html><head>
        <link rel="stylesheet" href="/css/bill.css">
        <link rel="icon" href="/favicon.ico">
        <link rel="stylesheet" href="/css/missing.css">
        <script src="/js/qr.js" type="text/javascript"></script>
        <style>.x{background:url(/img/bg.png)}</style>
        </head><body>
        <img src="/img/logo.png" alt="FESCO"> <img src='data:image/gif;base64,R0lGOD' alt="dot">
        <img src="/img/none.png">
        <table><tr><td>REFERENCE No.</td></tr></table>
        </body></html>
    """.trimIndent()

    @Test
    fun inlinesStylesScriptsAndPictures() {
        val fetcher = HttpResourceFetcher(OkHttpClient(), "test")
        val out = PageSnapshot.build(page, server.url("/fescobill/general?refno=1").toString(), fetcher)

        assertFalse(out.contains("href=\"/css/bill.css\""))
        assertTrue(out.contains("<style>.logo{background:url(\"data:image/png;base64,"))
        assertTrue(out.contains("src:url(\"data:font/woff2;base64,"))
        assertTrue(out.contains("<script type=\"text/javascript\">var s = '<\\/script>'; drawQr();</script>"))
        assertTrue(out.contains("<img src=\"data:image/png;base64,"))
        assertTrue(out.contains(".x{background:url(\"data:image/png;base64,"))
        // Things that could not be downloaded keep their address; data: pictures are untouched.
        assertTrue(out.contains("href=\"/css/missing.css\""))
        assertTrue(out.contains("<img src=\"/img/none.png\">"))
        assertTrue(out.contains("src='data:image/gif;base64,R0lGOD'"))
        assertTrue(out.contains("rel=\"icon\""))
        assertTrue(out.contains("REFERENCE No."))
    }

    @Test
    fun nothingReachableLeavesPageAsIs() {
        val out = PageSnapshot.build(page, server.url("/").toString(), { null })
        assertTrue(out == page)
    }
}

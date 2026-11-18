package com.fescobill.app

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs the fetcher against a local imitation of the PITC site, including a slow, flaky one. */
class BillFetcherTest {

    private lateinit var server: MockWebServer
    private val requests = AtomicInteger()
    private val posts = mutableListOf<Map<String, String>>()

    /** How the imitation site behaves. */
    private var needsSession = false
    private var hangFirst = 0
    private var failFirst = 0
    private var alwaysFail = false

    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = requests.incrementAndGet()
                if (n <= hangFirst) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (alwaysFail || n <= hangFirst + failFirst) return MockResponse().setResponseCode(503).setBody("busy")
                val path = request.path.orEmpty()
                val hasSession = request.getHeader("Cookie")?.contains("sid=ok") == true
                return when {
                    request.method == "GET" && path.startsWith("/fescobill/general") -> {
                        val known = path.endsWith("refno=${PitcPages.REF}") || path.endsWith("appno=${PitcPages.CID}")
                        when {
                            needsSession && !hasSession -> MockResponse().setResponseCode(302).setHeader("Location", "/fescobill")
                            known -> html(PitcPages.bill())
                            else -> html("<html><body>No record</body></html>")
                        }
                    }
                    request.method == "GET" && path == "/fescobill" -> html(PitcPages.form())
                    request.method == "POST" && path == "/fescobill" -> {
                        val f = parseForm(request.body.readUtf8())
                        synchronized(posts) { posts += f }
                        val mode = f["rbSearchByList"]
                        val value = f["searchTextBox"]
                        val ok = f["__RequestVerificationToken"] == "tok123" && f.containsKey("btnSearch") &&
                            ((mode == "refno" && value == PitcPages.REF) || (mode == "appno" && value == PitcPages.CID))
                        if (ok) {
                            MockResponse().setResponseCode(302)
                                .setHeader("Location", "/fescobill/general?$mode=$value")
                                .addHeader("Set-Cookie", "sid=ok; Path=/")
                        } else {
                            html(PitcPages.form(error = "Invalid Reference Number / Customer ID"))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
        val (k, v) = (it.split('=', limit = 2) + "").take(2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun fetcher(
        hedge: LongArray = longArrayOf(0, 300, 600),
        maxAttempts: Int = 6,
        overallMs: Long = 20_000,
    ) = BillFetcher(
        formUrl = server.url("/fescobill").toString(),
        userAgent = "test",
        hedgeDelaysMs = hedge,
        maxAttempts = maxAttempts,
        retryDelayMs = 50,
        overallTimeoutMs = overallMs,
        baseClient = client,
    )

    private fun run(f: BillFetcher, q: BillQuery): Pair<BillFetcher.Result, Long> {
        val latch = CountDownLatch(1)
        var result: BillFetcher.Result? = null
        val t0 = System.nanoTime()
        f.fetch(q, deliver = { it.run() }, onAttempt = {}, onDone = { result = it; latch.countDown() })
        assertTrue("fetch did not finish", latch.await(30, TimeUnit.SECONDS))
        return result!! to (System.nanoTime() - t0) / 1_000_000
    }

    private val ref = BillQuery(PitcPages.REF, SearchMode.REFERENCE)

    @Test
    fun directLinkGivesBill() {
        val (r, _) = run(fetcher(), ref)
        r as BillFetcher.Result.Bill
        assertTrue(r.url.endsWith("/fescobill/general?refno=${PitcPages.REF}"))
        assertTrue(PitcHtml.isBill(r.html, r.url))
    }

    @Test
    fun fillsSearchFormWhenSiteNeedsIt() {
        needsSession = true
        val (r, _) = run(fetcher(hedge = longArrayOf(0)), ref)
        r as BillFetcher.Result.Bill
        assertTrue(r.cookies.any { it.name == "sid" && it.value == "ok" })
        val post = synchronized(posts) { posts.first() }
        assertEquals("tok123", post["__RequestVerificationToken"])
        assertEquals("refno", post["rbSearchByList"])
        assertEquals(PitcPages.REF, post["searchTextBox"])
        assertEquals("Search", post["btnSearch"])
    }

    @Test
    fun customerIdSearch() {
        needsSession = true
        val (r, _) = run(fetcher(hedge = longArrayOf(0)), BillQuery(PitcPages.CID, SearchMode.CUSTOMER_ID))
        r as BillFetcher.Result.Bill
        assertTrue(r.url.endsWith("appno=${PitcPages.CID}"))
    }

    @Test
    fun wrongNumberReportsSiteMessage() {
        val (r, _) = run(fetcher(hedge = longArrayOf(0)), BillQuery("07135230000000", SearchMode.REFERENCE))
        r as BillFetcher.Result.NotFound
        assertEquals("Invalid Reference Number / Customer ID", r.message)
    }

    @Test
    fun stuckRequestIsOvertakenBySecondConnection() {
        hangFirst = 1 // the first request never gets an answer
        val (r, ms) = run(fetcher(), ref)
        assertTrue(r is BillFetcher.Result.Bill)
        assertTrue("took $ms ms", ms < 5_000) // far below the 10 s read timeout
    }

    @Test
    fun serverErrorsAreRetried() {
        failFirst = 3
        val (r, _) = run(fetcher(hedge = longArrayOf(0)), ref)
        assertTrue(r is BillFetcher.Result.Bill)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        alwaysFail = true
        val (r, _) = run(fetcher(hedge = longArrayOf(0), maxAttempts = 4), ref)
        r as BillFetcher.Result.Failed
        assertTrue(r.reason, r.reason.contains("503") || r.reason.contains("server error"))
        assertEquals(BillFetcher.Problem.SERVER, r.problem)
        assertEquals(4, requests.get())
    }

    @Test
    fun unreachableServerIsReportedAsSuch() {
        // Nothing listens on this port: every connection is refused, like the FESCO server today.
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/fescobill").toString()
        dead.shutdown()
        val f = BillFetcher(formUrl = url, userAgent = "test", hedgeDelaysMs = longArrayOf(0), maxAttempts = 3,
            retryDelayMs = 50, overallTimeoutMs = 20_000, baseClient = client)
        val (r, _) = run(f, ref)
        r as BillFetcher.Result.Failed
        assertEquals(BillFetcher.Problem.UNREACHABLE, r.problem)
    }

    @Test
    fun spreadsConnectionsOverRoutes() {
        val used = java.util.Collections.synchronizedList(ArrayList<String>())
        hangFirst = 2
        val routes = listOf("Wi-Fi", "mobile data").map { name ->
            BillFetcher.Route(name) { b -> b.addInterceptor { chain -> used += name; chain.proceed(chain.request()) } }
        }
        val f = BillFetcher(formUrl = server.url("/fescobill").toString(), userAgent = "test",
            hedgeDelaysMs = longArrayOf(0, 200, 400), retryDelayMs = 50, baseClient = client, routes = { routes })
        val (r, _) = run(f, ref)
        r as BillFetcher.Result.Bill
        assertTrue(used.toString(), used.contains("Wi-Fi") && used.contains("mobile data"))
        assertTrue(r.route == "Wi-Fi")
    }

    @Test
    fun overallTimeout() {
        hangFirst = 100
        val (r, ms) = run(fetcher(overallMs = 1_500), ref)
        assertTrue(r is BillFetcher.Result.Failed)
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun cancelledJobStaysQuiet() {
        hangFirst = 100
        var done: BillFetcher.Result? = null
        val job = fetcher(overallMs = 800).fetch(ref, deliver = { it.run() }, onAttempt = {}, onDone = { done = it })
        Thread.sleep(200)
        job.cancel()
        Thread.sleep(1_200)
        assertNull(done)
        assertFalse(requests.get() == 0)
    }
}

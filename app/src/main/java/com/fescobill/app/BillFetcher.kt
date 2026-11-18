package com.fescobill.app

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Gets the bill page from the PITC site directly (no browser), over several connections.
 *
 * The site is often slow, hangs on single requests, or answers with server errors. So instead of
 * waiting on one stuck request, another request starts if nothing has arrived after a few
 * seconds ([hedgeDelaysMs]), failed requests are retried straight away, and the first complete
 * bill wins. Each attempt does what a person does: open .../general?refno=..., and if the site
 * wants its search form instead, fill it in and press Search.
 */
class BillFetcher(
    private val formUrl: String = Pitc.FORM_URL,
    private val userAgent: String,
    private val hedgeDelaysMs: LongArray = longArrayOf(0, 4_000, 10_000),
    private val maxAttempts: Int = 8,
    private val retryDelayMs: Long = 1_500,
    private val overallTimeoutMs: Long = 100_000,
    baseClient: OkHttpClient? = null,
) {
    sealed class Result {
        class Bill(val html: String, val url: String, val cookies: List<Cookie>) : Result()
        class NotFound(val message: String) : Result()
        class Failed(val reason: String) : Result()
    }

    private sealed class Outcome {
        class Done(val result: Result) : Outcome()
        class Retry(val reason: String) : Outcome()
    }

    private class Page(val code: Int, val html: String, val url: String) {
        val ok: Boolean get() = code in 200..299
    }

    private val base: OkHttpClient = baseClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Starts fetching. [deliver] moves callbacks to the caller's thread. [onAttempt] gets the
     * number of connections started so far; [onDone] is called exactly once (unless cancelled).
     */
    fun fetch(
        query: BillQuery,
        deliver: (Runnable) -> Unit,
        onAttempt: (Int) -> Unit,
        onDone: (Result) -> Unit,
        onLog: (String) -> Unit = {},
    ): Job = Job(query, deliver, onAttempt, onDone, onLog).also { it.start() }

    inner class Job internal constructor(
        private val query: BillQuery,
        private val deliver: (Runnable) -> Unit,
        private val onAttempt: (Int) -> Unit,
        private val onDone: (Result) -> Unit,
        private val onLog: (String) -> Unit,
    ) {
        private val lock = Any()
        private val calls = HashSet<Call>()
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
        private val workers: ExecutorService = Executors.newCachedThreadPool()
        private var started = 0
        private var running = 0
        private var finished = false
        private var lastProblem = "no answer"

        internal fun start() {
            for (delay in hedgeDelaysMs) {
                scheduler.schedule({ launch(hedge = true) }, delay, TimeUnit.MILLISECONDS)
            }
            scheduler.schedule({ finish(Result.Failed("no answer in ${overallTimeoutMs / 1000} s ($lastProblem)")) },
                overallTimeoutMs, TimeUnit.MILLISECONDS)
        }

        /** Stops all requests; [onDone] will not be called. */
        fun cancel() {
            synchronized(lock) {
                if (finished) return
                finished = true
                calls.forEach { it.cancel() }
                calls.clear()
            }
            shutdown()
        }

        private fun shutdown() {
            scheduler.shutdownNow()
            workers.shutdown()
        }

        private fun launch(hedge: Boolean) {
            val number: Int
            synchronized(lock) {
                if (finished || started >= maxAttempts) return
                if (hedge && running >= hedgeDelaysMs.size) return
                started++
                running++
                number = started
            }
            deliver(Runnable { onAttempt(number) })
            log("connection $number started")
            try {
                workers.execute {
                    val t0 = System.currentTimeMillis()
                    val outcome = try {
                        attempt()
                    } catch (e: IOException) {
                        Outcome.Retry(e.message ?: e.javaClass.simpleName)
                    } catch (e: RuntimeException) {
                        Outcome.Retry(e.toString())
                    }
                    val took = (System.currentTimeMillis() - t0) / 100 / 10.0
                    log("connection $number after $took s: " + when (outcome) {
                        is Outcome.Retry -> outcome.reason
                        is Outcome.Done -> when (val r = outcome.result) {
                            is Result.Bill -> "bill received"
                            is Result.NotFound -> "site says: ${r.message}"
                            is Result.Failed -> r.reason
                        }
                    })
                    handle(outcome)
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                synchronized(lock) { running-- }
            }
        }

        private fun handle(outcome: Outcome) {
            var retry = false
            synchronized(lock) {
                running--
                if (finished) return
                when (outcome) {
                    is Outcome.Done -> Unit
                    is Outcome.Retry -> {
                        lastProblem = outcome.reason
                        if (started < maxAttempts) {
                            retry = true
                        } else if (running > 0) {
                            return
                        }
                    }
                }
            }
            when {
                outcome is Outcome.Done -> finish(outcome.result)
                retry -> try {
                    scheduler.schedule({ launch(hedge = false) }, retryDelayMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    // already finished
                }
                else -> finish(Result.Failed(lastProblem))
            }
        }

        private fun log(message: String) {
            synchronized(lock) { if (finished) return }
            deliver(Runnable { onLog(message) })
        }

        private fun finish(result: Result) {
            synchronized(lock) {
                if (finished) return
                finished = true
                calls.forEach { it.cancel() }
                calls.clear()
            }
            deliver(Runnable { onDone(result) })
            shutdown()
        }

        // ---- one attempt, on its own connection and cookie session ----

        private fun attempt(): Outcome {
            val jar = MemoryCookieJar()
            val client = base.newBuilder()
                .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
                .cookieJar(jar)
                .build()

            var page = get(client, "$formUrl/general?${query.mode.param}=${query.digits}")
            if (page.code >= 500) return Outcome.Retry("server error ${page.code}")
            if (page.ok && PitcHtml.isBill(page.html, page.url)) return bill(page, jar)

            if (!page.ok || !PitcHtml.hasSearchForm(page.html)) {
                page = get(client, formUrl)
                if (!page.ok) return Outcome.Retry("server error ${page.code}")
            }
            repeat(2) {
                val form = PitcHtml.searchForm(page.html, query.digits, query.mode)
                    ?: return Outcome.Retry("search page not recognised")
                val answer = submit(client, page, form)
                if (answer.code >= 500) return Outcome.Retry("server error ${answer.code}")
                if (answer.ok && PitcHtml.isBill(answer.html, answer.url)) return bill(answer, jar)
                if (!PitcHtml.hasSearchForm(answer.html)) return Outcome.Retry("unexpected answer (${answer.code})")
                val message = PitcHtml.errorMessage(answer.html)
                if (message.isNotBlank()) return Outcome.Done(Result.NotFound(message))
                page = answer
            }
            return Outcome.Retry("no bill returned")
        }

        private fun bill(page: Page, jar: MemoryCookieJar) =
            Outcome.Done(Result.Bill(page.html, page.url, jar.all()))

        private fun get(client: OkHttpClient, url: String): Page =
            execute(client, browserRequest(url).get().build())

        private fun submit(client: OkHttpClient, page: Page, form: PitcHtml.SearchForm): Page {
            val pageUrl = page.url.toHttpUrl()
            val target = (if (form.action.isBlank()) pageUrl else pageUrl.resolve(form.action)) ?: pageUrl
            val request = if (form.method == "get") {
                val url = target.newBuilder().apply { form.fields.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                browserRequest(url.toString()).header("Referer", page.url).get()
            } else {
                val body = FormBody.Builder().apply { form.fields.forEach { (k, v) -> add(k, v) } }.build()
                browserRequest(target.toString()).header("Referer", page.url).header("Origin", origin(target)).post(body)
            }
            return execute(client, request.build())
        }

        private fun browserRequest(url: String) = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")

        private fun origin(url: HttpUrl): String =
            url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

        private fun execute(client: OkHttpClient, request: Request): Page {
            val call = client.newCall(request)
            synchronized(lock) {
                if (finished) throw IOException("cancelled")
                calls += call
            }
            try {
                call.execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    return Page(response.code, html, response.request.url.toString())
                }
            } finally {
                synchronized(lock) { calls -= call }
            }
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = ArrayList<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                this.cookies += c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies.filter { it.expiresAt > now && it.matches(url) }
        }

        @Synchronized
        fun all(): List<Cookie> = cookies.toList()
    }
}

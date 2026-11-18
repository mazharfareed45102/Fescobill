package com.fescobill.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Shows the official bill from bill.pitc.com.pk and saves or shares it as a PDF / picture.
 *
 * To be fast even when the FESCO site is slow:
 *  1. The saved copy of this bill (if it was opened before) is shown instantly.
 *  2. [BillFetcher] asks the site over several connections at once and retries failures.
 *  3. At the same time the WebView opens the site like a browser (and fills in the search form
 *     if the site asks for it). Whichever gets the bill first wins.
 * Once the live bill is on screen, its PDF and picture are made in the background and kept as the
 * new saved copy, so the PDF / Image / Share buttons work instantly.
 */
class BillActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DIGITS = "digits"
        private const val MAX_AUTOFILL = 3
        private const val PDF_TIMEOUT_MS = 45_000L
        private const val LOAD_POLL_MS = 1_500L
        private const val LOAD_POLL_LIMIT = 60
        private const val SLOW_AFTER_S = 12
        private val SUBMIT_POLLS_MS = longArrayOf(2_500, 5_000, 8_000, 12_000, 18_000)

        /** Trackers and ads are never needed to show a bill and only slow the page down. */
        private val BLOCKED_HOSTS = listOf(
            "google-analytics.com", "googletagmanager.com", "doubleclick.net", "googlesyndication.com",
            "googleadservices.com", "facebook.net", "facebook.com", "hotjar.com", "clarity.ms",
        )

        fun intent(context: Context, q: BillQuery): Intent =
            Intent(context, BillActivity::class.java).putExtra(EXTRA_DIGITS, q.digits)
    }

    private enum class Step { DIRECT, FORM, SUBMITTED, NATIVE, DONE }

    private enum class Tone(@ColorRes val color: Int) {
        INFO(R.color.banner_info_bg), OK(R.color.banner_ok_bg), WARN(R.color.banner_warn_bg), ERROR(R.color.banner_error_bg)
    }

    private data class PageState(val bill: Boolean, val form: Boolean, val message: String, val month: String)

    private class Files(val pdf: File, val png: File?, val month: String)

    private lateinit var query: BillQuery
    private lateinit var cache: BillCache
    private lateinit var webView: WebView
    private lateinit var cachedView: WebView
    private lateinit var banner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var pdfButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val fetcher by lazy { BillFetcher(userAgent = webView.settings.userAgentString) }

    private var lookupGen = 0
    private var cached: BillCache.Entry? = null
    private var fetchJob: BillFetcher.Job? = null
    private var step = Step.DIRECT
    private var liveShown = false
    private var liveMonth = ""
    private var liveBanner = ""
    private var finalShown = false
    private var nativeDone = false
    private var nativeProblem = ""
    private var nativeNotFound: String? = null
    private var webDone = false
    private var webProblem = ""
    private var webNotFound: String? = null
    private var webHint: String? = null
    private var autofillCount = 0
    private var navigatedSinceSubmit = false
    private var inspectToken = 0
    private var pageLoaded = false
    private var lastHttpStatus = 0
    private var fitted = false
    private var busy = false
    private var startedAt = 0L
    private var attempts = 0
    private var bannerText = ""
    private var bannerTone = Tone.INFO
    private var liveFiles: Files? = null
    private var filesWaiters: MutableList<(Files?) -> Unit>? = null
    private val afterLoad = ArrayList<Runnable>()
    private var pendingStorageAction: (() -> Unit)? = null
    private val events = ArrayList<String>()

    private val detectJs by lazy { asset("pitc_detect.js") }
    private val fillJs by lazy { asset("pitc_fill.js") }
    private val hooksJs by lazy { asset("pitc_hooks.js") }
    private val fitJs by lazy { asset("pitc_fit.js") }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast(R.string.storage_permission_needed)
        }

    /** Updates the "waiting" banner every second while the bill is being fetched. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!searching()) return
            showSearchingBanner()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = RefNumber.parse(intent.getStringExtra(EXTRA_DIGITS).orEmpty())
        if (parsed == null) {
            finish()
            return
        }
        query = parsed
        cache = BillCache(this)

        setContentView(R.layout.activity_bill)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.bill_title, query.display)

        banner = findViewById(R.id.statusBanner)
        progress = findViewById(R.id.pageProgress)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        pdfButton = findViewById(R.id.pdfButton)
        imageButton = findViewById(R.id.imageButton)
        shareButton = findViewById(R.id.shareButton)

        findViewById<View>(R.id.retryButton).setOnClickListener { startLookup() }
        findViewById<View>(R.id.browserButton).setOnClickListener { openInBrowser() }
        pdfButton.setOnClickListener { downloadPdf() }
        imageButton.setOnClickListener { saveImage() }
        shareButton.setOnClickListener { shareBill() }

        setupCachedView()
        setupWebView()
        startLookup()
    }

    override fun onDestroy() {
        lookupGen++
        fetchJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        for (view in listOf(if (::webView.isInitialized) webView else null, if (::cachedView.isInitialized) cachedView else null)) {
            view ?: continue
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bill_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_refresh -> {
            startLookup()
            true
        }
        R.id.action_print -> {
            printBill()
            true
        }
        R.id.action_browser -> {
            openInBrowser()
            true
        }
        R.id.action_details -> {
            showDetails()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- Setup ------------------------------------------------------------------------------

    private fun setupCachedView() {
        cachedView = findViewById(R.id.cachedView)
        with(cachedView.settings) {
            javaScriptEnabled = false
            allowFileAccess = true // only to show our own saved picture of the bill
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        cachedView.webViewClient = WebViewClient()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true          // lay the bill out at its real (paper) width…
            loadWithOverviewMode = true     // …then zoom out so the whole width fits the screen
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = 100                  // a large phone font size must not break the bill layout
            cacheMode = WebSettings.LOAD_DEFAULT // the site's styles and images are reused next time
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(PageBridge(), "FescoApp")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            @Deprecated("Needed for Android 5/6")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUrl(Uri.parse(url))

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host?.lowercase() ?: return null
                if (BLOCKED_HOSTS.none { host == it || host.endsWith(".$it") }) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                event("browser: loading ${shortUrl(url)}")
                inspectToken++ // cancels checks scheduled for the previous page
                pageLoaded = false
                if (step == Step.SUBMITTED) navigatedSinceSubmit = true
                startLoadPolls()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                event("browser: page finished ${shortUrl(url)}")
                view.evaluateJavascript(hooksJs, null)
                pageLoaded = true
                runAfterLoad()
                scheduleInspect(700)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && Build.VERSION.SDK_INT >= 23) {
                    onWebFailure(getString(R.string.error_network, error.description))
                }
            }

            @Deprecated("Needed for Android 5")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < 23) onWebFailure(getString(R.string.error_network, description.orEmpty()))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    lastHttpStatus = response.statusCode
                    event("browser: HTTP ${response.statusCode}")
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never accept a bad certificate
                onWebFailure(getString(R.string.error_ssl))
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFromSite(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "https" || scheme == "http") && Pitc.isPitcHost(uri.host)) return false
        // Anything that is not the official bill site opens outside the app.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // nothing can open it; ignore
        }
        return true
    }

    // ---- Getting the bill -------------------------------------------------------------------

    private fun startLookup() {
        val gen = ++lookupGen
        fetchJob?.cancel()
        fetchJob = null
        handler.removeCallbacksAndMessages(null)
        afterLoad.clear()
        inspectToken++
        step = Step.DIRECT
        liveShown = false
        liveMonth = ""
        liveBanner = ""
        finalShown = false
        nativeDone = false
        nativeProblem = ""
        nativeNotFound = null
        webDone = false
        webProblem = ""
        webNotFound = null
        webHint = null
        autofillCount = 0
        navigatedSinceSubmit = false
        pageLoaded = false
        lastHttpStatus = 0
        fitted = false
        busy = false
        attempts = 0
        liveFiles = null
        filesWaiters = null
        errorPanel.isVisible = false
        events.clear()
        startedAt = SystemClock.elapsedRealtime()

        cached = cache.get(query.digits)
        event("open ${Pitc.billUrl(query)}" + (cached?.let { " (saved copy: ${it.month.ifBlank { "yes" }})" } ?: ""))
        showCached()
        updateButtons()
        ticker.run()

        // Fast path: several direct connections at once.
        fetchJob = fetcher.fetch(
            query,
            deliver = { handler.post(it) },
            onAttempt = { if (gen == lookupGen) attempts = it },
            onDone = { if (gen == lookupGen) onNativeResult(it) },
            onLog = { if (gen == lookupGen) event(it) },
        )
        // Browser path at the same time.
        webView.loadUrl(Pitc.billUrl(query))
    }

    private fun searching() = !liveShown && !finalShown

    private fun showCached() {
        val c = cached
        val png = c?.png
        if (c == null || png == null) {
            cachedView.isVisible = false
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        val w = if (bounds.outWidth > 0) bounds.outWidth else 1654
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=$w, user-scalable=yes\"></head>" +
            "<body style=\"margin:0;background:#ffffff\"><img src=\"${png.name}\" " +
            "style=\"width:${w}px;display:block\"></body></html>"
        cachedView.clearCache(false) // the picture file may have been replaced since it was last shown
        cachedView.loadDataWithBaseURL(Uri.fromFile(png.parentFile).toString() + "/", html, "text/html", "UTF-8", null)
        cachedView.isVisible = true
    }

    private fun cachedLabel(c: BillCache.Entry): String {
        val ago = DateUtils.getRelativeTimeSpanString(c.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return if (c.month.isNotBlank()) getString(R.string.cached_label, c.month, ago) else getString(R.string.cached_label_no_month, ago)
    }

    private fun showSearchingBanner() {
        if (busy) return
        val secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val c = cached
        val first = if (c != null) getString(R.string.status_cached_checking, cachedLabel(c)) else getString(R.string.status_contacting)
        val lines = arrayListOf(first + "   " + getString(R.string.status_seconds, secs))
        if (secs >= SLOW_AFTER_S) lines += getString(R.string.status_slow, max(attempts, 1) + 1)
        webHint?.let { lines += it }
        setBanner(lines.joinToString("\n"), if (secs >= SLOW_AFTER_S && c == null) Tone.WARN else Tone.INFO)
    }

    private fun onNativeResult(result: BillFetcher.Result) {
        fetchJob = null
        if (isFinishing || liveShown) return
        when (result) {
            is BillFetcher.Result.Bill -> {
                step = Step.NATIVE
                inspectToken++
                val cookies = CookieManager.getInstance()
                for (c in result.cookies) cookies.setCookie(result.url, c.toString())
                webView.stopLoading()
                webView.loadDataWithBaseURL(result.url, result.html, "text/html", "UTF-8", result.url)
            }
            is BillFetcher.Result.NotFound -> {
                nativeDone = true
                nativeNotFound = result.message
                showNotFound(result.message) // the browser path keeps going and still wins if it finds a bill
            }
            is BillFetcher.Result.Failed -> {
                nativeDone = true
                nativeProblem = result.reason
                if (webDone) concludeFailure()
            }
        }
    }

    private fun onWebFailure(message: String) {
        event("browser error: $message")
        if (liveShown || step == Step.NATIVE || webDone) return
        webDone = true
        webProblem = message
        if (nativeDone) {
            concludeFailure()
        } else {
            // Hide the browser's error page while the other connections are still trying.
            webView.loadUrl("about:blank")
        }
    }

    private fun webGaveUp(problem: String) {
        event("browser gave up: $problem")
        webDone = true
        webProblem = problem
        if (nativeDone) concludeFailure()
    }

    /** Neither path produced a bill. */
    private fun concludeFailure() {
        if (liveShown || finalShown) return
        val notFound = webNotFound ?: nativeNotFound
        if (notFound != null) {
            showNotFound(notFound)
            return
        }
        finalShown = true
        handler.removeCallbacks(ticker)
        val c = cached
        if (c != null) {
            setBanner(getString(R.string.status_cached_offline, cachedLabel(c)), Tone.WARN)
        } else {
            showError(webProblem.ifBlank { getString(R.string.status_failed, nativeProblem) })
        }
        updateButtons()
    }

    private fun showNotFound(siteMessage: String) {
        if (liveShown) return
        finalShown = true
        handler.removeCallbacks(ticker)
        if (cached == null) cachedView.isVisible = false
        var text = getString(R.string.status_not_found, query.display)
        if (siteMessage.isNotBlank()) text += "\n" + getString(R.string.status_not_found_msg, siteMessage)
        setBanner(text, Tone.WARN)
        updateButtons()
    }

    private fun showError(message: String) {
        errorText.text = message
        errorPanel.isVisible = true
        setBanner(getString(R.string.error_title), Tone.ERROR)
    }

    private fun startLoadPolls() {
        val token = inspectToken
        val poll = object : Runnable {
            var count = 0
            override fun run() {
                if (token != inspectToken || pageLoaded || isFinishing || count++ > LOAD_POLL_LIMIT) return
                inspect(fromPoll = true, lastPoll = false)
                handler.postDelayed(this, LOAD_POLL_MS)
            }
        }
        handler.postDelayed(poll, LOAD_POLL_MS)
    }

    private fun scheduleInspect(delayMs: Long, fromPoll: Boolean = false, lastPoll: Boolean = false) {
        val token = inspectToken
        handler.postDelayed({
            if (token == inspectToken && !isFinishing) inspect(fromPoll, lastPoll)
        }, delayMs)
    }

    private fun inspect(fromPoll: Boolean, lastPoll: Boolean) {
        webView.evaluateJavascript(detectJs) { raw -> onPageState(parseState(raw), fromPoll, lastPoll) }
    }

    private fun parseState(raw: String?): PageState = try {
        val json = JSONTokener(raw ?: "null").nextValue() as? String
        if (json == null) {
            PageState(bill = false, form = false, message = "", month = "")
        } else {
            val o = JSONObject(json)
            PageState(o.optBoolean("bill"), o.optBoolean("form"), o.optString("message"), o.optString("month"))
        }
    } catch (e: JSONException) {
        PageState(bill = false, form = false, message = "", month = "")
    }

    private fun onPageState(s: PageState, fromPoll: Boolean, lastPoll: Boolean) {
        if (isFinishing) return
        if (!fromPoll || s.bill && !liveShown) {
            event("browser page: bill=${s.bill} form=${s.form}" + (if (s.message.isNotBlank()) " message=\"${s.message}\"" else ""))
        }
        if (s.bill) {
            onBillShown(s)
            return
        }
        if (fromPoll) {
            // The form was filled but the page has not moved on (e.g. the site wants a manual tap).
            if (lastPoll && step == Step.SUBMITTED && !navigatedSinceSubmit && !webDone) {
                if (s.form && s.message.isNotBlank()) {
                    webNotFound = s.message
                    webDone = true
                    showNotFound(s.message)
                } else {
                    webHint = getString(R.string.status_tap_search)
                    cachedView.isVisible = false
                    if (searching()) showSearchingBanner()
                }
            }
            return
        }
        if (webDone) return
        when (step) {
            Step.DIRECT -> if (s.form) {
                autofill()
            } else {
                step = Step.FORM
                lastHttpStatus = 0
                webView.loadUrl(Pitc.FORM_URL)
            }
            Step.FORM -> if (s.form) autofill() else webGaveUp(unexpectedText())
            Step.SUBMITTED -> {
                if (!navigatedSinceSubmit) return // a repeated "finished" event for the page we just filled
                when {
                    s.form && s.message.isNotBlank() -> {
                        webNotFound = s.message
                        webDone = true
                        showNotFound(s.message)
                    }
                    s.form && autofillCount < MAX_AUTOFILL -> autofill()
                    else -> webGaveUp(unexpectedText())
                }
            }
            Step.NATIVE, Step.DONE -> Unit
        }
    }

    private fun unexpectedText(): String =
        if (lastHttpStatus >= 500) getString(R.string.error_http, lastHttpStatus) else getString(R.string.status_unexpected)

    private fun autofill() {
        event("browser: filling the search form (${autofillCount + 1})")
        autofillCount++
        step = Step.SUBMITTED
        navigatedSinceSubmit = false
        val call = "($fillJs)(${JSONObject.quote(query.digits)}, ${JSONObject.quote(query.mode.param)})"
        webView.evaluateJavascript(call, null)
        SUBMIT_POLLS_MS.forEachIndexed { i, delay ->
            scheduleInspect(delay, fromPoll = true, lastPoll = i == SUBMIT_POLLS_MS.lastIndex)
        }
    }

    private fun onBillShown(s: PageState) {
        val firstTime = !liveShown
        val viaDirect = step == Step.NATIVE
        step = Step.DONE
        liveShown = true
        finalShown = false
        webHint = null
        if (s.month.isNotBlank()) liveMonth = s.month
        fetchJob?.cancel()
        fetchJob = null
        handler.removeCallbacks(ticker)
        errorPanel.isVisible = false
        cachedView.isVisible = false
        updateButtons()
        if (firstTime) {
            event("bill shown" + (if (liveMonth.isNotBlank()) " ($liveMonth)" else "") + if (viaDirect) " via direct connection" else " via browser")
            val old = cached
            val suffix = if (liveMonth.isNotBlank()) "($liveMonth)" else ""
            liveBanner = if (old != null && old.month.isNotBlank() && liveMonth.isNotBlank() && old.month != liveMonth) {
                getString(R.string.status_new_bill, suffix)
            } else {
                getString(R.string.status_live_ok, suffix)
            }
            SavedBills(this).markOpened(query)
            fitToWidth()
            // Make the PDF and picture now, so the buttons work instantly and the copy is saved for next time.
            requestFiles(null)
        }
        if (!busy) setBanner(liveBanner, Tone.OK)
    }

    private fun fitToWidth() {
        if (fitted) return
        fitted = true
        webView.evaluateJavascript(fitJs) { result ->
            // zoom out as far as allowed, which is exactly "whole bill width on screen"
            if (result?.contains("fitted") == true) handler.postDelayed({ webView.zoomBy(0.02f) }, 300)
        }
    }

    private fun whenPageLoaded(action: () -> Unit) {
        val r = Runnable(action)
        if (pageLoaded) {
            handler.postDelayed(r, 400)
            return
        }
        afterLoad += r
        // Don't wait forever for a slow picture or script on the page.
        handler.postDelayed({ if (afterLoad.remove(r)) r.run() }, 15_000)
    }

    private fun runAfterLoad() {
        val list = ArrayList(afterLoad)
        afterLoad.clear()
        for (r in list) handler.postDelayed(r, 400)
    }

    // ---- Banner and buttons ------------------------------------------------------------------

    private fun setBanner(text: String, tone: Tone) {
        bannerText = text
        bannerTone = tone
        showBanner(text, tone)
    }

    private fun showBanner(text: String, tone: Tone) {
        banner.text = text
        banner.setBackgroundColor(ContextCompat.getColor(this, tone.color))
        banner.isVisible = true
    }

    private fun updateButtons() {
        val enabled = (liveShown || cached != null) && !busy
        pdfButton.isEnabled = enabled
        imageButton.isEnabled = enabled
        shareButton.isEnabled = enabled
    }

    private fun setBusy(isBusy: Boolean, @StringRes message: Int = 0) {
        busy = isBusy
        updateButtons()
        when {
            isBusy && message != 0 -> showBanner(getString(message), Tone.INFO)
            !isBusy && searching() -> showSearchingBanner()
            !isBusy -> showBanner(bannerText, bannerTone)
        }
    }

    // ---- PDF, picture, share ----------------------------------------------------------------

    private fun pdfAttributes(): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()

    private fun displayName(files: Files?, ext: String): String {
        val month = (files?.month ?: liveMonth).replace(" ", "")
        val tag = month.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        return "FESCO-Bill-${query.digits}-$tag.$ext"
    }

    /**
     * The bill's PDF and picture: the live bill's (made once, in the background) or the saved copy's.
     * [cb] gets null if they could not be made.
     */
    private fun requestFiles(cb: ((Files?) -> Unit)?) {
        liveFiles?.let { files ->
            cb?.invoke(files)
            return
        }
        if (!liveShown) {
            cb?.invoke(cached?.let { Files(it.pdf, it.png, it.month) })
            return
        }
        filesWaiters?.let { waiting ->
            cb?.let { waiting += it }
            return
        }
        val waiters = ArrayList<(Files?) -> Unit>()
        cb?.let { waiters += it }
        filesWaiters = waiters
        val gen = lookupGen
        whenPageLoaded {
            if (gen != lookupGen) return@whenPageLoaded
            makePdf { pdf ->
                if (gen != lookupGen) return@makePdf
                if (pdf == null) {
                    event("PDF could not be made directly")
                    finishFiles(null)
                    return@makePdf
                }
                event("PDF ready")
                val month = liveMonth
                worker.execute {
                    val png = File(pdf.parentFile, "work.png")
                    val pngOk = runCatching { BillFiles.pdfToPng(pdf, png) }.isSuccess
                    val entry = runCatching { cache.put(query.digits, pdf, png.takeIf { pngOk }, month) }.getOrNull()
                    runOnUiThread {
                        if (gen != lookupGen || isDestroyed) return@runOnUiThread
                        if (entry != null) cached = entry
                        finishFiles(entry?.let { Files(it.pdf, it.png, it.month) } ?: Files(pdf, png.takeIf { pngOk }, month))
                    }
                }
            }
        }
    }

    private fun finishFiles(files: Files?) {
        if (files != null) liveFiles = files
        val waiters = filesWaiters ?: return
        filesWaiters = null
        waiters.forEach { it(files) }
    }

    /** Prints the live page (as the site's own Print button would) straight into a PDF file. */
    private fun makePdf(onDone: (File?) -> Unit) {
        val out = File(File(cacheDir, "bills").apply { mkdirs() }, "work.pdf")
        var finished = false
        lateinit var timeout: Runnable
        val complete: (File?) -> Unit = { file ->
            runOnUiThread {
                if (!finished && !isDestroyed) {
                    finished = true
                    handler.removeCallbacks(timeout)
                    onDone(file?.takeIf { it.length() > 0 })
                }
            }
        }
        timeout = Runnable { complete(null) }
        handler.postDelayed(timeout, PDF_TIMEOUT_MS)
        try {
            val adapter = webView.createPrintDocumentAdapter("FESCO Bill ${query.digits}")
            PdfPrinter.print(adapter, pdfAttributes(), out, object : PdfPrinter.Callback {
                override fun onSuccess(file: File) = complete(file)
                override fun onFailure(message: String) = complete(null)
            })
        } catch (t: Throwable) {
            // Some phones do not allow printing without the dialog; callers fall back.
            complete(null)
        }
    }

    /** Runs [action] with the bill files, showing "Preparing…" if they are still being made. */
    private fun withFiles(@StringRes preparing: Int, action: (Files?) -> Unit) {
        if (!liveShown && cached == null) {
            toast(R.string.wait_for_bill)
            return
        }
        if (busy) return
        val ready = liveFiles ?: if (!liveShown) cached?.let { Files(it.pdf, it.png, it.month) } else null
        if (ready != null) {
            action(ready)
            return
        }
        setBusy(true, preparing)
        requestFiles { files ->
            setBusy(false)
            action(files)
        }
    }

    private fun downloadPdf() = withStorage {
        withFiles(R.string.preparing_pdf) { files ->
            if (files != null) {
                saveAndOffer(files.pdf, displayName(files, "pdf"), "application/pdf", pictures = false, saved = R.string.saved_pdf)
            } else {
                printWithSystemDialog()
            }
        }
    }

    private fun saveImage() = withStorage {
        withFiles(R.string.preparing_image) { files ->
            val png = files?.png ?: captureScreen()
            if (png != null) saveAndOffer(png, displayName(files, "png"), "image/png", pictures = true, saved = R.string.saved_image)
        }
    }

    private fun shareBill() {
        if (!liveShown && cached == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_as)
            .setItems(arrayOf(getString(R.string.share_pdf), getString(R.string.share_image))) { _, which ->
                if (which == 0) {
                    withFiles(R.string.preparing_pdf) { files ->
                        if (files != null) {
                            shareFile(files.pdf, displayName(files, "pdf"), "application/pdf")
                        } else {
                            captureScreen()?.let { shareFile(it, displayName(null, "png"), "image/png") }
                        }
                    }
                } else {
                    withFiles(R.string.preparing_image) { files ->
                        (files?.png ?: captureScreen())?.let { shareFile(it, displayName(files, "png"), "image/png") }
                    }
                }
            }
            .show()
    }

    /** Last resort: a picture of what is on screen now. */
    private fun captureScreen(): File? = try {
        val view = if (cachedView.isVisible) cachedView else webView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val f = File(File(cacheDir, "bills").apply { mkdirs() }, "screen.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f
    } catch (t: Throwable) {
        toast(getString(R.string.save_failed, t.message ?: t.javaClass.simpleName))
        null
    }

    private fun shareFile(source: File, name: String, mime: String) {
        worker.execute {
            val result = runCatching {
                val target = File(File(cacheDir, "bills").apply { mkdirs() }, name)
                if (source.canonicalPath != target.canonicalPath) source.copyTo(target, overwrite = true)
                target
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { BillFiles.share(this, it, mime, getString(R.string.share)) }
                    .onFailure { toast(getString(R.string.save_failed, it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    private fun saveAndOffer(source: File, name: String, mime: String, pictures: Boolean, @StringRes saved: Int) {
        worker.execute {
            val result = runCatching { BillFiles.saveToPublic(this, source, name, mime, pictures) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    Snackbar.make(webView, saved, Snackbar.LENGTH_INDEFINITE)
                        .setDuration(8_000)
                        .setAnchorView(R.id.bottomBar)
                        .setAction(R.string.open) { BillFiles.open(this, uri, mime) }
                        .show()
                }.onFailure { e ->
                    toast(getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun printBill() {
        when {
            liveShown -> printWithSystemDialog()
            cached != null -> {
                // Open the saved PDF; the PDF viewer can print it.
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cached!!.pdf)
                BillFiles.open(this, uri, "application/pdf")
            }
            else -> toast(R.string.wait_for_bill)
        }
    }

    /** The normal Android print screen; "Save as PDF" there works on every phone. */
    private fun printWithSystemDialog() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        toast(R.string.pdf_fallback)
        try {
            printManager.print(
                displayName(null, "pdf").removeSuffix(".pdf"),
                webView.createPrintDocumentAdapter(displayName(null, "pdf").removeSuffix(".pdf")),
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
            )
        } catch (t: Throwable) {
            toast(getString(R.string.save_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    /** Android 9 and older need permission to write into Downloads / Pictures. */
    private fun withStorage(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingStorageAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    /** Files the website itself offers for download (if any) go to Downloads/FESCO Bills. */
    private fun downloadFromSite(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        withStorage {
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(uri)
                    .setMimeType(mimeType)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${BillFiles.FOLDER}/$name")
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast(R.string.downloading)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    toast(R.string.no_browser)
                }
            }
        }
    }

    private fun openInBrowser() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Pitc.billUrl(query))))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_browser)
        }
    }

    private fun event(message: String) {
        val secs = (SystemClock.elapsedRealtime() - startedAt) / 100 / 10.0
        events += String.format(Locale.US, "%6.1fs  %s", secs, message)
        if (events.size > 300) events.removeAt(0)
    }

    private fun shortUrl(url: String?): String = url.orEmpty().substringAfter("://").take(90)

    /** What each connection did, so a problem can be reported precisely. */
    private fun showDetails() {
        val text = buildString {
            append("FESCO Bill ").append(BuildConfigInfo.versionName(this@BillActivity))
            append(" • Android ").append(Build.VERSION.RELEASE).append('\n')
            events.forEach { append(it).append('\n') }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details_title)
            .setMessage(text)
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FESCO Bill details", text))
                toast(R.string.copied)
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    private fun asset(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    private fun toast(@StringRes res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Lets the website's own "Print" button save the bill through the app. */
    private inner class PageBridge {
        @JavascriptInterface
        fun print() {
            runOnUiThread { if (liveShown) downloadPdf() }
        }
    }
}

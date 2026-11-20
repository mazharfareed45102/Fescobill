package com.fescobill.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Shows the official bill from bill.pitc.com.pk and saves or shares it as a PDF / picture.
 *
 *  1. If the bill was downloaded before, the complete saved copy is shown instantly (no internet
 *     needed) while the app checks the website for a newer one.
 *  2. Otherwise [BillFetcher] asks the site over several connections (Wi-Fi and mobile data) while
 *     the WebView opens the site like a browser. Whichever gets the bill first wins.
 *  3. If the FESCO server cannot be reached at all, [BillRetryWorker] keeps trying in the
 *     background and a notification says when the bill is ready.
 * Every bill shown is saved as a self-contained page plus PDF and picture, so the PDF / Image /
 * Share buttons work instantly and the bill opens offline next time.
 */
class BillActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DIGITS = "digits"
        private const val MAX_AUTOFILL = 3
        private const val PDF_TIMEOUT_MS = 45_000L
        private const val LOAD_POLL_MS = 1_500L
        private const val LOAD_POLL_LIMIT = 60
        private const val SLOW_AFTER_S = 12
        private const val SMS_NUMBER = "8334"
        private val SUBMIT_POLLS_MS = longArrayOf(2_500, 5_000, 8_000, 12_000, 18_000)

        /** Trackers and ads are never needed to show a bill and only slow the page down. */
        private val BLOCKED_HOSTS = listOf(
            "google-analytics.com", "googletagmanager.com", "doubleclick.net", "googlesyndication.com",
            "googleadservices.com", "facebook.net", "facebook.com", "hotjar.com", "clarity.ms",
        )

        private const val EXTRA_BATCH_INDEX = "batchIndex"
        private const val EXTRA_BATCH_TOTAL = "batchTotal"
        /** Consumer ID read from the bill photo: the downloaded bill is checked against it. */
        const val EXTRA_EXPECT_ID = "expectId"
        const val RESULT_VERIFIED = "verified"
        const val VERIFIED_MATCH = "match"
        const val VERIFIED_MISMATCH = "mismatch"
        const val RESULT_DIGITS = "digits"
        const val RESULT_STATUS = "status"
        const val STATUS_OK = "ok"
        const val STATUS_SAVED_COPY = "saved"
        const val STATUS_NOT_FOUND = "notfound"
        const val STATUS_FAILED = "failed"
        const val STATUS_SKIPPED = "skipped"
        /** In "several bills" mode a saved copy younger than this is used without going online. */
        private const val FRESH_MS = 24L * 60 * 60 * 1000

        fun intent(context: Context, q: BillQuery): Intent =
            Intent(context, BillActivity::class.java).putExtra(EXTRA_DIGITS, q.digits)

        /** One bill of several: saves it and returns to [BatchActivity] by itself. */
        fun batchIntent(context: Context, q: BillQuery, index: Int, total: Int, expectConsumerId: String? = null): Intent =
            intent(context, q).putExtra(EXTRA_BATCH_INDEX, index).putExtra(EXTRA_BATCH_TOTAL, total).apply {
                if (expectConsumerId != null) putExtra(EXTRA_EXPECT_ID, expectConsumerId)
            }
    }

    /** What the main WebView is doing. */
    private enum class Step { DIRECT, FORM, SUBMITTED, PERSON, LOADING_NATIVE, LOADING_SAVED, SHOWING }

    /** Which bill is on screen. */
    private enum class Shown { NONE, SAVED, LIVE }

    private enum class Check { RUNNING, SAME, NEWER, FAILED, NOT_FOUND, PERSON }

    private enum class Verify { NONE, PENDING, MATCH, MISMATCH }

    private enum class Tone(@ColorRes val color: Int) {
        INFO(R.color.banner_info_bg), OK(R.color.banner_ok_bg), WARN(R.color.banner_warn_bg), ERROR(R.color.banner_error_bg)
    }

    private data class PageState(
        val bill: Boolean,
        val form: Boolean,
        val message: String,
        val month: String,
        val captcha: Boolean = false,
    )

    private class Files(val pdf: File, val png: File?, val month: String)

    private lateinit var query: BillQuery
    private lateinit var cache: BillCache
    private lateinit var routes: NetworkRoutes
    private lateinit var webView: WebView
    private lateinit var previewView: WebView
    private lateinit var banner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var pdfButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val fetcher by lazy {
        BillFetcher(userAgent = webView.settings.userAgentString, routes = routes::routes)
    }
    private val httpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private var lookupGen = 0
    private var lookupStartedWall = 0L
    private var cached: BillCache.Entry? = null
    private var fetchJob: BillFetcher.Job? = null
    private var step = Step.DIRECT
    private var shown = Shown.NONE
    private var shownMonth = ""
    private var liveBanner = ""
    private var check = Check.RUNNING
    private var browserActive = false
    private var nativeRunning = false
    private var nativeProblem: BillFetcher.Problem? = null
    private var nativeNotFound: String? = null
    private var webDone = false
    private var webNotFound: String? = null
    private var webHint: String? = null
    private var failureText: String? = null
    private var notFoundText: String? = null
    private var autofillCount = 0
    private var navigatedSinceSubmit = false
    private var inspectToken = 0
    private var pageLoaded = false
    private var lastHttpStatus = 0
    private var fitted = false
    private var busy = false
    private var startedAt = 0L
    private var attempts = 0
    private var liveFiles: Files? = null
    private var filesWaiters: MutableList<(Files?) -> Unit>? = null
    private val afterLoad = ArrayList<Runnable>()
    private var pendingStorageAction: (() -> Unit)? = null
    private var askedNotifications = false
    private val events = ArrayList<String>()
    private var batch = false
    private var batchIndex = 0
    private var batchTotal = 0
    private var batchFinishing = false
    private var personMessage = ""
    private var personFilledToken = -1
    private var expectId: String? = null
    private var verify = Verify.NONE

    private val detectJs by lazy { asset("pitc_detect.js") }
    private val fillJs by lazy { asset("pitc_fill.js") }
    private val hooksJs by lazy { asset("pitc_hooks.js") }
    private val fitJs by lazy { asset("pitc_fit.js") }
    private val idsJs by lazy { asset("pitc_ids.js") }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast(R.string.storage_permission_needed)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Refreshes the banner every second while something is still being fetched. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!fetching()) return
            updateBanner()
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
        routes = NetworkRoutes(this).also { it.start() }
        batchTotal = intent.getIntExtra(EXTRA_BATCH_TOTAL, 0)
        batchIndex = intent.getIntExtra(EXTRA_BATCH_INDEX, 0)
        batch = batchTotal > 0
        expectId = intent.getStringExtra(EXTRA_EXPECT_ID)?.takeIf { it.length == RefNumber.CUSTOMER_ID_LENGTH }

        // Lets the whole bill (not only the visible part) be drawn into a picture if a phone cannot make PDFs.
        WebView.enableSlowWholeDocumentDraw()
        setContentView(R.layout.activity_bill)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = if (batch) {
            getString(R.string.batch_bill_title, batchIndex + 1, batchTotal, query.display)
        } else {
            getString(R.string.bill_title, query.display)
        }

        banner = findViewById(R.id.statusBanner)
        progress = findViewById(R.id.pageProgress)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        pdfButton = findViewById(R.id.pdfButton)
        imageButton = findViewById(R.id.imageButton)
        shareButton = findViewById(R.id.shareButton)

        findViewById<View>(R.id.retryButton).setOnClickListener { startLookup() }
        findViewById<View>(R.id.browserButton).setOnClickListener { openInBrowser() }
        findViewById<View>(R.id.smsButton).setOnClickListener { smsBill() }
        pdfButton.setOnClickListener { downloadPdf() }
        imageButton.setOnClickListener { saveImage() }
        shareButton.setOnClickListener { shareBill() }
        val skipButton = findViewById<View>(R.id.batchSkipButton)
        skipButton.setOnClickListener { finishBatch(STATUS_SKIPPED) }
        if (batch) {
            // Several bills: each bill is saved by itself, so the bottom bar only offers "next bill".
            pdfButton.isVisible = false
            imageButton.isVisible = false
            shareButton.isVisible = false
            skipButton.isVisible = true
        }
        banner.setOnClickListener { if (shown == Shown.SAVED && check == Check.PERSON) checkWebsiteNow() }

        setupPreviewView()
        setupWebView()
        Prefs.saveUserAgent(this, webView.settings.userAgentString)

        // If the background download finishes while this screen is open, show the bill at once.
        BillRetryWorker.liveData(this, query.digits).observe(this) {
            val entry = cache.get(query.digits)
            if (shown == Shown.NONE && entry?.page != null && entry.pageAt >= lookupStartedWall) startLookup()
        }

        startLookup()
    }

    override fun onDestroy() {
        lookupGen++
        fetchJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        if (::routes.isInitialized) routes.stop()
        for (view in listOf(if (::webView.isInitialized) webView else null, if (::previewView.isInitialized) previewView else null)) {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_refresh -> startLookup()
            R.id.action_print -> printBill()
            R.id.action_browser -> openInBrowser()
            R.id.action_sms -> smsBill()
            R.id.action_details -> showDetails()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---- Setup ------------------------------------------------------------------------------

    /** Shows the picture of an older saved bill (from app version 1.0.3) while the page loads. */
    private fun setupPreviewView() {
        previewView = findViewById(R.id.cachedView)
        with(previewView.settings) {
            javaScriptEnabled = false
            allowFileAccess = true // only to show our own saved picture of the bill
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        previewView.webViewClient = WebViewClient()
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
            cacheMode = WebSettings.LOAD_DEFAULT
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
                if (step != Step.LOADING_SAVED) event("browser: loading ${shortUrl(url)}")
                inspectToken++ // cancels checks scheduled for the previous page
                pageLoaded = false
                if (step == Step.SUBMITTED) navigatedSinceSubmit = true
                startLoadPolls()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(hooksJs, null)
                pageLoaded = true
                runAfterLoad()
                scheduleInspect(500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && Build.VERSION.SDK_INT >= 23) {
                    onWebFailure("${error.description}")
                }
            }

            @Deprecated("Needed for Android 5")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < 23) onWebFailure(description.orEmpty())
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
        shown = Shown.NONE
        shownMonth = ""
        liveBanner = ""
        check = Check.RUNNING
        browserActive = false
        nativeRunning = false
        nativeProblem = null
        nativeNotFound = null
        webDone = false
        webNotFound = null
        webHint = null
        failureText = null
        notFoundText = null
        autofillCount = 0
        navigatedSinceSubmit = false
        pageLoaded = false
        lastHttpStatus = 0
        busy = false
        attempts = 0
        liveFiles = null
        filesWaiters = null
        errorPanel.isVisible = false
        previewView.isVisible = false
        events.clear()
        startedAt = SystemClock.elapsedRealtime()
        lookupStartedWall = System.currentTimeMillis()

        val c = cache.get(query.digits)
        cached = c
        event("open ${Pitc.billUrl(query)}" + (c?.let { " (saved copy: ${it.month.ifBlank { "yes" }})" } ?: ""))
        personMessage = ""
        personFilledToken = -1
        verify = Verify.NONE
        // Several bills: use a saved copy only if it is recent; otherwise get the bill live.
        val useSaved = !batch || (c != null && System.currentTimeMillis() - c.pageAt < FRESH_MS)
        val savedHtml = if (useSaved) c?.page?.let { runCatching { it.readText() }.getOrNull() } else null
        if (c != null && savedHtml != null) {
            // Instant: the complete bill downloaded earlier.
            step = Step.LOADING_SAVED
            fitted = false
            webView.loadDataWithBaseURL(c.baseUrl.ifBlank { Pitc.billUrl(query) }, savedHtml, "text/html", "UTF-8", null)
        } else {
            if (!batch) showPreview()
            startBrowser()
        }
        if (!(batch && savedHtml != null)) startNative(gen) // a fresh saved copy needs no check
        updateButtons()
        ticker.run()
    }

    /** The saved bill is on screen, but the user wants the website checked now (it asks for a code). */
    private fun checkWebsiteNow() {
        event("checking the website now")
        shown = Shown.NONE
        check = Check.RUNNING
        liveFiles = null
        startBrowser()
        updateButtons()
        ticker.run()
    }

    private fun startBrowser() {
        browserActive = true
        step = Step.DIRECT
        fitted = false
        webView.loadUrl(Pitc.billUrl(query))
    }

    private fun startNative(gen: Int) {
        nativeRunning = true
        fetchJob = fetcher.fetch(
            query,
            deliver = { handler.post(it) },
            onAttempt = { if (gen == lookupGen) attempts = it },
            onDone = { if (gen == lookupGen) onNativeResult(it) },
            onLog = { if (gen == lookupGen) event(it) },
        )
    }

    private fun fetching() = nativeRunning || (browserActive && !webDone && shown == Shown.NONE)

    private fun showPreview() {
        val c = cached
        val png = c?.png
        if (c == null || png == null) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        val w = if (bounds.outWidth > 0) bounds.outWidth else 1654
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=$w, user-scalable=yes\"></head>" +
            "<body style=\"margin:0;background:#ffffff\"><img src=\"${png.name}\" style=\"width:${w}px;display:block\"></body></html>"
        previewView.clearCache(false) // the picture file may have been replaced since it was last shown
        previewView.loadDataWithBaseURL(Uri.fromFile(png.parentFile).toString() + "/", html, "text/html", "UTF-8", null)
        previewView.isVisible = true
    }

    private fun savedLabel(): String {
        val c = cached
        val month = shownMonth.ifBlank { c?.month.orEmpty() }
        val at = c?.pageAt?.takeIf { it > 0 } ?: c?.savedAt ?: System.currentTimeMillis()
        val ago = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return if (month.isNotBlank()) getString(R.string.saved_label, month, ago) else getString(R.string.saved_label_no_month, ago)
    }

    private fun onNativeResult(result: BillFetcher.Result) {
        fetchJob = null
        nativeRunning = false
        if (isFinishing) return
        when (result) {
            is BillFetcher.Result.Bill -> {
                val month = PitcHtml.billMonth(result.html).orEmpty()
                event("bill received over ${result.route}" + if (month.isNotBlank()) " ($month)" else "")
                saveSnapshot(result.html, result.url, result.resources, month)
                when {
                    shown == Shown.LIVE -> Unit
                    shown == Shown.SAVED && month.isNotBlank() && month == shownMonth -> check = Check.SAME
                    else -> {
                        check = Check.NEWER
                        step = Step.LOADING_NATIVE
                        inspectToken++
                        fitted = false
                        val cookies = CookieManager.getInstance()
                        for (c in result.cookies) cookies.setCookie(result.url, c.toString())
                        webView.stopLoading()
                        webView.loadDataWithBaseURL(result.url, result.html, "text/html", "UTF-8", result.url)
                    }
                }
            }
            is BillFetcher.Result.NotFound -> {
                check = Check.NOT_FOUND
                nativeNotFound = result.message
                if (shown == Shown.NONE) showNotFound(result.message) // the browser may still find it
            }
            is BillFetcher.Result.Failed -> {
                check = Check.FAILED
                nativeProblem = result.problem
                if (shown == Shown.NONE && (!browserActive || webDone)) concludeFailure()
            }
            BillFetcher.Result.NeedsPerson -> {
                // The browser view shows the code page; the person types the code there.
                check = Check.PERSON
                if (shown == Shown.NONE && !browserActive) startBrowser()
            }
        }
        updateBanner()
    }

    private fun onWebFailure(message: String) {
        event("browser error: $message")
        if (!browserActive || shown != Shown.NONE || webDone) return
        if (step != Step.DIRECT && step != Step.FORM && step != Step.SUBMITTED && step != Step.PERSON) return
        webDone = true
        if (!nativeRunning) {
            concludeFailure()
        } else {
            // Hide the browser's error page while the other connections are still trying.
            webView.loadUrl("about:blank")
        }
        updateBanner()
    }

    private fun webGaveUp(problem: String) {
        event("browser gave up: $problem")
        webDone = true
        if (!nativeRunning) concludeFailure()
        updateBanner()
    }

    /** Neither path produced a bill and none is saved. */
    private fun concludeFailure() {
        if (shown != Shown.NONE || failureText != null) return
        val notFound = webNotFound ?: nativeNotFound
        if (notFound != null) {
            showNotFound(notFound)
            return
        }
        if (batch && cached?.png != null) {
            // Several bills: the website is not answering, so hand back the saved copy.
            event("website not answering; using the saved copy")
            finishBatch(STATUS_SAVED_COPY)
            return
        }
        handler.removeCallbacks(ticker)
        val why = when (nativeProblem) {
            BillFetcher.Problem.UNREACHABLE, null -> R.string.fail_unreachable
            BillFetcher.Problem.NO_INTERNET -> R.string.fail_no_internet
            BillFetcher.Problem.SECURE -> R.string.error_ssl
            BillFetcher.Problem.SERVER -> R.string.fail_server
            BillFetcher.Problem.UNEXPECTED -> R.string.fail_unexpected
        }
        val canWait = nativeProblem != BillFetcher.Problem.NO_INTERNET
        if (canWait) {
            // Keep trying in the background and tell the user when the bill is downloaded.
            BillRetryWorker.schedule(this, query)
            SavedBills(this).markOpened(query)
            askForNotifications()
            event("background retry scheduled (every 15 min)")
        }
        val text = getString(why) + if (canWait) "\n\n" + getString(R.string.fail_background) else ""
        failureText = text
        if (!previewView.isVisible) {
            errorText.text = text
            errorPanel.isVisible = true
        }
        updateBanner()
        updateButtons()
        finishBatchLater(STATUS_FAILED)
    }

    private fun showNotFound(siteMessage: String) {
        if (shown != Shown.NONE) return
        var text = getString(R.string.status_not_found, query.display)
        if (siteMessage.isNotBlank()) text += "\n" + getString(R.string.status_not_found_msg, siteMessage)
        notFoundText = text
        updateBanner()
        updateButtons()
        finishBatchLater(STATUS_NOT_FOUND)
    }

    /** Several bills: report this bill's outcome and go back for the next one. */
    private fun finishBatch(status: String) {
        if (!batch || batchFinishing) return
        batchFinishing = true
        event("done: $status")
        val verified = when (verify) {
            Verify.MATCH -> VERIFIED_MATCH
            Verify.MISMATCH -> VERIFIED_MISMATCH
            else -> ""
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_DIGITS, query.digits).putExtra(RESULT_STATUS, status)
            .putExtra(RESULT_VERIFIED, verified))
        showBanner(getString(if (status == STATUS_OK || status == STATUS_SAVED_COPY) R.string.batch_saved_next else R.string.batch_next), Tone.OK)
        handler.postDelayed({ if (!isFinishing) finish() }, 700)
    }

    /** Several bills: the bill is on screen; make sure its picture is saved, then move on. */
    private fun finishBatchWith(files: Files?, waited: Int = 0) {
        if (verify == Verify.PENDING && waited < 10) {
            // Let the check against the photo finish first (it takes a moment).
            handler.postDelayed({ finishBatchWith(files, waited + 1) }, 300)
            return
        }
        if (files?.png != null) {
            finishBatch(STATUS_OK)
            return
        }
        val shot = captureScreen()
        val ok = shot != null && runCatching { cached = cache.putPicture(query.digits, shot, shownMonth) }.isSuccess
        finishBatch(if (ok) STATUS_OK else STATUS_FAILED)
    }

    /** Leaves a "not found" / "failed" message on screen for a moment before moving on. */
    private fun finishBatchLater(status: String) {
        if (!batch) return
        handler.postDelayed({ if (shown == Shown.NONE && step != Step.PERSON) finishBatch(status) }, 3_500)
    }

    private fun askForNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            PageState(o.optBoolean("bill"), o.optBoolean("form"), o.optString("message"), o.optString("month"),
                o.optBoolean("captcha"))
        }
    } catch (e: JSONException) {
        PageState(bill = false, form = false, message = "", month = "")
    }

    private fun onPageState(s: PageState, fromPoll: Boolean, lastPoll: Boolean) {
        if (isFinishing) return
        if (s.bill) {
            onBillOnScreen(s)
            return
        }
        if (s.captcha && browserActive && shown == Shown.NONE && step != Step.LOADING_NATIVE) {
            needPerson(s)
            return
        }
        if (fromPoll) {
            // The form was filled but the page has not moved on (e.g. the site wants a manual tap).
            if (lastPoll && browserActive && step == Step.SUBMITTED && !navigatedSinceSubmit && !webDone) {
                if (s.form && s.message.isNotBlank()) {
                    webNotFound = s.message
                    webDone = true
                    showNotFound(s.message)
                } else {
                    webHint = getString(R.string.status_tap_search)
                    previewView.isVisible = false
                    updateBanner()
                }
            }
            return
        }
        if (step == Step.SHOWING || step == Step.LOADING_NATIVE) return
        event("browser page: bill=false form=${s.form}" + if (s.message.isNotBlank()) " message=\"${s.message}\"" else "")
        when (step) {
            Step.LOADING_SAVED -> {
                // The saved copy could not be shown; use the website instead.
                event("saved copy not usable")
                startBrowser()
            }
            Step.DIRECT -> if (!webDone) {
                if (s.form) {
                    autofill()
                } else {
                    step = Step.FORM
                    lastHttpStatus = 0
                    webView.loadUrl(Pitc.FORM_URL)
                }
            }
            Step.FORM -> if (!webDone) {
                if (s.form) autofill() else webGaveUp(unexpectedText())
            }
            Step.PERSON -> if (!webDone) {
                // After the person pressed Search: back on the plain form (no code) means search again.
                if (s.form) autofill() else webGaveUp(unexpectedText())
            }
            Step.SUBMITTED -> if (!webDone && navigatedSinceSubmit) {
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
            Step.LOADING_NATIVE, Step.SHOWING -> Unit
        }
    }

    private fun unexpectedText(): String =
        if (lastHttpStatus >= 500) getString(R.string.error_http, lastHttpStatus) else getString(R.string.status_unexpected)

    /**
     * The site shows a code (captcha). It is meant to be typed by a person, so the app fills in the
     * reference number, puts the cursor in the code box and waits for the person to press Search.
     */
    private fun needPerson(s: PageState) {
        if (step != Step.PERSON) event("site asks for a code (captcha); waiting for the person to type it")
        step = Step.PERSON
        webDone = false
        fetchJob?.cancel()
        fetchJob = null
        nativeRunning = false
        failureText = null
        notFoundText = null
        errorPanel.isVisible = false
        previewView.isVisible = false
        personMessage = s.message
        if (personFilledToken != inspectToken) {
            personFilledToken = inspectToken
            webView.requestFocus()
            webView.evaluateJavascript(
                "($fillJs)(${JSONObject.quote(query.digits)}, ${JSONObject.quote(query.mode.param)}, false)", null
            )
        }
        updateBanner()
        // Keep looking for the bill (some sites show it without loading a new page).
        scheduleInspect(2_000, fromPoll = true)
    }

    private fun autofill() {
        event("browser: filling the search form (${autofillCount + 1})")
        autofillCount++
        step = Step.SUBMITTED
        navigatedSinceSubmit = false
        val call = "($fillJs)(${JSONObject.quote(query.digits)}, ${JSONObject.quote(query.mode.param)}, true)"
        webView.evaluateJavascript(call, null)
        SUBMIT_POLLS_MS.forEachIndexed { i, delay ->
            scheduleInspect(delay, fromPoll = true, lastPoll = i == SUBMIT_POLLS_MS.lastIndex)
        }
    }

    private fun onBillOnScreen(s: PageState) {
        if (step == Step.SHOWING) {
            updateBanner()
            return
        }
        val fromSaved = step == Step.LOADING_SAVED
        val viaBrowser = step == Step.DIRECT || step == Step.FORM || step == Step.SUBMITTED || step == Step.PERSON
        step = Step.SHOWING
        shown = if (fromSaved) Shown.SAVED else Shown.LIVE
        if (s.month.isNotBlank()) shownMonth = s.month else if (fromSaved) shownMonth = cached?.month.orEmpty()
        failureText = null
        notFoundText = null
        webHint = null
        errorPanel.isVisible = false
        previewView.isVisible = false
        event("bill shown" + (if (shownMonth.isNotBlank()) " ($shownMonth)" else "") +
            when {
                fromSaved -> " from the saved copy"
                viaBrowser -> " via browser"
                else -> " via direct connection"
            })

        if (shown == Shown.LIVE) {
            val old = cached
            val suffix = if (shownMonth.isNotBlank()) "($shownMonth)" else ""
            liveBanner = if (old != null && old.month.isNotBlank() && shownMonth.isNotBlank() && old.month != shownMonth) {
                getString(R.string.status_new_bill, suffix)
            } else {
                getString(R.string.status_live_ok, suffix)
            }
            fetchJob?.cancel()
            fetchJob = null
            nativeRunning = false
            BillRetryWorker.cancel(this, query.digits)
            SavedBills(this).markOpened(query)
            liveFiles = null
            // Make the PDF and picture now, so the buttons work instantly (and the next bill can start).
            requestFiles { files -> if (batch) finishBatchWith(files) }
            if (viaBrowser) saveSnapshotFromBrowser()
        } else {
            val c = cached
            val pdf = c?.pdf
            liveFiles = if (c != null && pdf != null && c.filesCurrent && c.png != null) Files(pdf, c.png, c.month) else null
            requestFiles { files -> if (batch) finishBatchWith(files) }
        }
        fitToWidth()
        checkAgainstPhoto()
        updateButtons()
        updateBanner()
    }

    /**
     * A bill photo was read: the Consumer ID on it must also be on this bill. If a digit of the
     * reference number had been read wrong, this would be someone else's bill with another Consumer ID.
     */
    private fun checkAgainstPhoto() {
        val id = expectId ?: return
        verify = Verify.PENDING
        webView.evaluateJavascript(idsJs) { raw ->
            val ids = try {
                val json = JSONTokener(raw ?: "null").nextValue() as? String
                if (json == null) emptyList() else org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.optString(it) } }
            } catch (e: JSONException) {
                emptyList()
            }
            verify = when {
                id in ids -> Verify.MATCH
                ids.isNotEmpty() -> Verify.MISMATCH
                else -> Verify.NONE
            }
            event("check against photo (Consumer ID $id): $verify")
            updateBanner()
        }
    }

    private fun fitToWidth() {
        if (fitted) return
        fitted = true
        webView.evaluateJavascript(fitJs) { result ->
            // zoom out as far as allowed, which is exactly "whole bill width on screen"
            if (result?.contains("fitted") == true) handler.postDelayed({ webView.zoomBy(0.02f) }, 300)
        }
    }

    // ---- Saving the complete page for next time ----------------------------------------------

    private fun saveSnapshot(html: String, url: String, resources: PageSnapshot.Fetcher, month: String) {
        val gen = lookupGen
        worker.execute {
            val entry = runCatching {
                val page = PageSnapshot.build(html, url, resources)
                cache.putPage(query.digits, page, url, month)
            }.getOrNull()
            runOnUiThread {
                if (gen == lookupGen && entry != null && !isDestroyed) {
                    cached = entry
                    event("complete copy saved on phone")
                }
            }
        }
    }

    /** The browser path found the bill: download the page again (unchanged by its scripts) and save it. */
    private fun saveSnapshotFromBrowser() {
        val url = webView.url ?: return
        if (!url.startsWith("http")) return
        val month = shownMonth
        val cookieManager = CookieManager.getInstance()
        val resources = HttpResourceFetcher(httpClient, webView.settings.userAgentString) { cookieManager.getCookie(it) }
        val gen = lookupGen
        worker.execute {
            val raw = resources.fetch(url)?.bytes?.let { String(it, Charsets.UTF_8) }
            val entry = if (raw != null && PitcHtml.isBill(raw, url)) {
                runCatching { cache.putPage(query.digits, PageSnapshot.build(raw, url, resources), url, month) }.getOrNull()
            } else {
                null
            }
            runOnUiThread {
                if (gen == lookupGen && entry != null && !isDestroyed) {
                    cached = entry
                    event("complete copy saved on phone")
                }
            }
        }
    }

    // ---- Banner and buttons ------------------------------------------------------------------

    private fun updateBanner() {
        if (busy || batchFinishing) return
        val secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val (text, tone) = when {
            shown == Shown.LIVE -> liveBanner to Tone.OK
            shown == Shown.SAVED -> when (check) {
                Check.RUNNING -> getString(R.string.status_saved_checking, savedLabel(), secs) to Tone.INFO
                Check.SAME -> getString(R.string.status_saved_latest, savedLabel()) to Tone.OK
                Check.PERSON -> getString(R.string.status_saved_person, savedLabel()) to Tone.OK
                else -> getString(R.string.status_saved_offline, savedLabel()) to Tone.OK
            }
            step == Step.PERSON -> {
                var t = getString(R.string.status_captcha)
                if (personMessage.isNotBlank()) t += "\n" + getString(R.string.status_not_found_msg, personMessage)
                t to Tone.WARN
            }
            notFoundText != null -> notFoundText!! to Tone.WARN
            failureText != null -> (if (previewView.isVisible) failureText!! else getString(R.string.error_title)) to Tone.ERROR
            else -> {
                val lines = arrayListOf(getString(R.string.status_contacting) + "   " + getString(R.string.status_seconds, secs))
                if (secs >= SLOW_AFTER_S) lines += getString(R.string.status_slow, max(attempts, 1) + if (browserActive) 1 else 0)
                webHint?.let { lines += it }
                lines.joinToString("\n") to (if (secs >= SLOW_AFTER_S) Tone.WARN else Tone.INFO)
            }
        }
        var finalText = text
        var finalTone = tone
        if (shown != Shown.NONE) {
            when (verify) {
                Verify.MATCH -> finalText += "\n" + getString(R.string.verify_match, expectId)
                Verify.MISMATCH -> {
                    finalText += "\n" + getString(R.string.verify_mismatch, expectId)
                    finalTone = Tone.WARN
                }
                else -> Unit
            }
        }
        showBanner(finalText, finalTone)
    }

    private fun showBanner(text: String, tone: Tone) {
        banner.text = text
        banner.setBackgroundColor(ContextCompat.getColor(this, tone.color))
        banner.isVisible = true
    }

    private fun hasBill() = shown != Shown.NONE || cached?.pdf != null

    private fun updateButtons() {
        val enabled = hasBill() && !busy
        pdfButton.isEnabled = enabled
        imageButton.isEnabled = enabled
        shareButton.isEnabled = enabled
    }

    private fun setBusy(isBusy: Boolean, @StringRes message: Int = 0) {
        busy = isBusy
        updateButtons()
        if (isBusy && message != 0) showBanner(getString(message), Tone.INFO) else if (!isBusy) updateBanner()
    }

    // ---- PDF, picture, share ----------------------------------------------------------------

    private fun pdfAttributes(): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()

    private fun displayName(files: Files?, ext: String): String {
        val month = (files?.month ?: shownMonth).replace(" ", "")
        val tag = month.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        return "FESCO-Bill-${query.digits}-$tag.$ext"
    }

    private fun savedFiles(): Files? {
        val c = cached ?: return null
        val pdf = c.pdf ?: return null
        return Files(pdf, c.png, c.month)
    }

    /**
     * The PDF and picture of the bill on screen (made once, in the background), or of the saved
     * copy when no bill is on screen. [cb] gets null if they could not be made.
     */
    private fun requestFiles(cb: ((Files?) -> Unit)?) {
        liveFiles?.let { files ->
            cb?.invoke(files)
            return
        }
        if (shown == Shown.NONE) {
            cb?.invoke(savedFiles())
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
                val month = shownMonth
                worker.execute {
                    val png = File(pdf.parentFile, "work.png")
                    val pngOk = runCatching { BillFiles.pdfToPng(pdf, png) }.isSuccess
                    val entry = runCatching { cache.putFiles(query.digits, pdf, png.takeIf { pngOk }, month) }.getOrNull()
                    runOnUiThread {
                        if (gen != lookupGen || isDestroyed) return@runOnUiThread
                        if (entry != null) cached = entry
                        val savedPdf = entry?.pdf
                        finishFiles(
                            if (savedPdf != null) Files(savedPdf, entry.png, entry.month) else Files(pdf, png.takeIf { pngOk }, month)
                        )
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

    /** Prints the page on screen (as the site's own Print button would) straight into a PDF file. */
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
        if (!hasBill()) {
            toast(R.string.wait_for_bill)
            return
        }
        if (busy) return
        val ready = liveFiles ?: if (shown == Shown.NONE) savedFiles() else null
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
        if (!hasBill()) return
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

    /** Fallback when no PDF can be made: a picture of the whole page (or of what is on screen). */
    private fun captureScreen(): File? = try {
        val view = if (previewView.isVisible) previewView else webView
        @Suppress("DEPRECATION")
        val fullHeight = (view.contentHeight * view.scale).toInt()
        val height = fullHeight.coerceIn(view.height, 12_000)
        val bmp = Bitmap.createBitmap(view.width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        view.draw(canvas)
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
        val pdf = cached?.pdf
        when {
            shown != Shown.NONE -> printWithSystemDialog()
            pdf != null -> BillFiles.open(this, FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf), "application/pdf")
            else -> toast(R.string.wait_for_bill)
        }
    }

    /** The normal Android print screen; "Save as PDF" there works on every phone. */
    private fun printWithSystemDialog() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        toast(R.string.pdf_fallback)
        val name = displayName(null, "pdf").removeSuffix(".pdf")
        try {
            printManager.print(name, webView.createPrintDocumentAdapter(name),
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } catch (t: Throwable) {
            toast(getString(R.string.save_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    /** PITC's SMS service: "PITC <reference number>" to 8334 answers with the amount and due date. */
    private fun smsBill() {
        if (query.mode != SearchMode.REFERENCE) {
            toast(R.string.sms_needs_reference)
            return
        }
        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$SMS_NUMBER")).putExtra("sms_body", "PITC ${query.digits}")
        try {
            startActivity(sms)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_sms_app)
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

    // ---- Connection details -----------------------------------------------------------------

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
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FESCO Bill details", text))
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
            runOnUiThread { if (shown != Shown.NONE) downloadPdf() }
        }
    }
}

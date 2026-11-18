package com.fescobill.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shows the official bill from bill.pitc.com.pk exactly as the website renders it, and saves or
 * shares it as a PDF / picture.
 *
 * Flow: open .../fescobill/general?refno=<number> directly. If the site shows its search form
 * instead, fill it in and press Search (once more if needed). The page is checked after every
 * load, so whatever the site answers (bill, "not found" message, error) is shown to the user.
 */
class BillActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DIGITS = "digits"
        private const val MAX_AUTOFILL = 3
        private const val PDF_TIMEOUT_MS = 30_000L
        private val POLL_DELAYS_MS = longArrayOf(2_500, 5_000, 8_000, 12_000, 18_000)

        fun intent(context: Context, q: BillQuery): Intent =
            Intent(context, BillActivity::class.java).putExtra(EXTRA_DIGITS, q.digits)
    }

    private enum class Step { DIRECT, FORM, SUBMITTED, DONE }

    private enum class Tone(@ColorRes val color: Int) {
        INFO(R.color.banner_info_bg), OK(R.color.banner_ok_bg), WARN(R.color.banner_warn_bg), ERROR(R.color.banner_error_bg)
    }

    private data class PageState(val bill: Boolean, val form: Boolean, val message: String)

    private lateinit var query: BillQuery
    private lateinit var webView: WebView
    private lateinit var banner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var pdfButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var step = Step.DIRECT
    private var billShown = false
    private var savedOnce = false
    private var fitted = false
    private var failed = false
    private var busy = false
    private var autofillCount = 0
    private var navigatedSinceSubmit = false
    private var inspectToken = 0
    private var lastHttpStatus = 0
    private var pendingStorageAction: (() -> Unit)? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = RefNumber.parse(intent.getStringExtra(EXTRA_DIGITS).orEmpty())
        if (parsed == null) {
            finish()
            return
        }
        query = parsed

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

        setupWebView()
        startLookup()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
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
            if (billShown) printWithSystemDialog() else toast(R.string.wait_for_bill)
            true
        }
        R.id.action_browser -> {
            openInBrowser()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- Loading the bill -------------------------------------------------------------------

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
            cacheMode = WebSettings.LOAD_NO_CACHE // always the latest bill, never an old copy
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

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                inspectToken++ // cancels checks scheduled for the previous page
                if (step == Step.SUBMITTED) navigatedSinceSubmit = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(hooksJs, null)
                scheduleInspect(700)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && Build.VERSION.SDK_INT >= 23) {
                    showError(getString(R.string.error_network, error.description))
                }
            }

            @Deprecated("Needed for Android 5")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < 23) showError(getString(R.string.error_network, description.orEmpty()))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) lastHttpStatus = response.statusCode
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never accept a bad certificate
                showError(getString(R.string.error_ssl))
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

    private fun startLookup() {
        handler.removeCallbacksAndMessages(null)
        inspectToken++
        step = Step.DIRECT
        billShown = false
        fitted = false
        failed = false
        busy = false
        autofillCount = 0
        navigatedSinceSubmit = false
        lastHttpStatus = 0
        errorPanel.isVisible = false
        updateButtons()
        setBanner(getString(R.string.status_loading), Tone.INFO)
        webView.loadUrl(Pitc.billUrl(query))
    }

    private fun scheduleInspect(delayMs: Long, fromPoll: Boolean = false, lastPoll: Boolean = false) {
        val token = inspectToken
        handler.postDelayed({
            if (token == inspectToken && !isFinishing) inspect(fromPoll, lastPoll)
        }, delayMs)
    }

    private fun inspect(fromPoll: Boolean, lastPoll: Boolean) {
        if (failed) return
        webView.evaluateJavascript(detectJs) { raw -> onPageState(parseState(raw), fromPoll, lastPoll) }
    }

    private fun parseState(raw: String?): PageState = try {
        val json = JSONTokener(raw ?: "null").nextValue() as? String
        if (json == null) {
            PageState(bill = false, form = false, message = "")
        } else {
            val o = JSONObject(json)
            PageState(o.optBoolean("bill"), o.optBoolean("form"), o.optString("message"))
        }
    } catch (e: JSONException) {
        PageState(bill = false, form = false, message = "")
    }

    private fun onPageState(s: PageState, fromPoll: Boolean, lastPoll: Boolean) {
        if (isFinishing || failed) return
        if (s.bill) {
            onBillShown()
            return
        }
        if (fromPoll) {
            // The form was filled but the page has not moved on (e.g. the site wants a manual tap).
            if (lastPoll && step == Step.SUBMITTED && !navigatedSinceSubmit) {
                if (s.form && s.message.isNotBlank()) {
                    showNotFound(s.message)
                } else {
                    setBanner(getString(R.string.status_tap_search), Tone.WARN)
                }
            }
            return
        }
        when (step) {
            Step.DIRECT -> if (s.form) {
                autofill()
            } else {
                step = Step.FORM
                lastHttpStatus = 0
                webView.loadUrl(Pitc.FORM_URL)
            }
            Step.FORM -> if (s.form) autofill() else showUnexpected()
            Step.SUBMITTED -> {
                if (!navigatedSinceSubmit) return // a repeated "finished" event for the page we just filled
                when {
                    s.form && s.message.isBlank() && autofillCount < MAX_AUTOFILL -> autofill()
                    s.form -> showNotFound(s.message)
                    else -> showUnexpected()
                }
            }
            Step.DONE -> {
                // The user moved to another page of the site.
                billShown = false
                updateButtons()
            }
        }
    }

    private fun autofill() {
        autofillCount++
        step = Step.SUBMITTED
        navigatedSinceSubmit = false
        setBanner(getString(R.string.status_searching), Tone.INFO)
        val call = "($fillJs)(${JSONObject.quote(query.digits)}, ${JSONObject.quote(query.mode.param)})"
        webView.evaluateJavascript(call, null)
        POLL_DELAYS_MS.forEachIndexed { i, delay ->
            scheduleInspect(delay, fromPoll = true, lastPoll = i == POLL_DELAYS_MS.lastIndex)
        }
    }

    private fun onBillShown() {
        step = Step.DONE
        billShown = true
        errorPanel.isVisible = false
        updateButtons()
        if (!busy) setBanner(getString(R.string.status_ready), Tone.OK)
        if (!fitted) {
            fitted = true
            webView.evaluateJavascript(fitJs) { result ->
                // zoom out as far as allowed, which is exactly "whole bill width on screen"
                if (result?.contains("fitted") == true) handler.postDelayed({ webView.zoomBy(0.02f) }, 300)
            }
        }
        if (!savedOnce) {
            savedOnce = true
            SavedBills(this).markOpened(query)
        }
    }

    private fun showNotFound(siteMessage: String) {
        var text = getString(R.string.status_not_found, query.display)
        if (siteMessage.isNotBlank()) text += "\n" + getString(R.string.status_not_found_msg, siteMessage)
        setBanner(text, Tone.WARN)
    }

    private fun showUnexpected() {
        val text = if (lastHttpStatus >= 500) {
            getString(R.string.error_http, lastHttpStatus)
        } else {
            getString(R.string.status_unexpected)
        }
        setBanner(text, Tone.WARN)
    }

    private fun showError(message: String) {
        failed = true
        billShown = false
        handler.removeCallbacksAndMessages(null)
        errorText.text = message
        errorPanel.isVisible = true
        updateButtons()
        setBanner(getString(R.string.error_title), Tone.ERROR)
    }

    private fun setBanner(text: String, tone: Tone) {
        banner.text = text
        banner.setBackgroundColor(ContextCompat.getColor(this, tone.color))
        banner.isVisible = true
    }

    private fun updateButtons() {
        val enabled = billShown && !busy
        pdfButton.isEnabled = enabled
        imageButton.isEnabled = enabled
        shareButton.isEnabled = enabled
    }

    private fun setBusy(isBusy: Boolean, @StringRes message: Int = 0) {
        busy = isBusy
        updateButtons()
        if (isBusy && message != 0) {
            setBanner(getString(message), Tone.INFO)
        } else if (!isBusy && billShown) {
            setBanner(getString(R.string.status_ready), Tone.OK)
        }
    }

    // ---- PDF, picture, share ----------------------------------------------------------------

    private fun fileBaseName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "FESCO-Bill-${query.digits}-$date"
    }

    private fun pdfAttributes(): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()

    /** Prints the page (as the site's own Print button would) straight into a PDF file. */
    private fun makePdf(onReady: (File) -> Unit, onFail: () -> Unit) {
        if (!billShown) {
            toast(R.string.wait_for_bill)
            return
        }
        if (busy) return
        setBusy(true, R.string.preparing_pdf)
        val out = File(File(cacheDir, "bills").apply { mkdirs() }, fileBaseName() + ".pdf")
        var finished = false
        lateinit var timeout: Runnable
        val complete: (File?) -> Unit = { file ->
            runOnUiThread {
                if (!finished && !isFinishing) {
                    finished = true
                    handler.removeCallbacks(timeout)
                    setBusy(false)
                    if (file != null && file.length() > 0) onReady(file) else onFail()
                }
            }
        }
        timeout = Runnable { complete(null) }
        handler.postDelayed(timeout, PDF_TIMEOUT_MS)
        try {
            val adapter = webView.createPrintDocumentAdapter(fileBaseName())
            PdfPrinter.print(adapter, pdfAttributes(), out, object : PdfPrinter.Callback {
                override fun onSuccess(file: File) = complete(file)
                override fun onFailure(message: String) = complete(null)
            })
        } catch (t: Throwable) {
            // Some phones do not allow printing without the dialog; the caller falls back.
            complete(null)
        }
    }

    /** The bill as one picture, made from the PDF so it looks exactly like the printed bill. */
    private fun makeImage(onReady: (File) -> Unit) {
        makePdf(
            onReady = { pdf ->
                setBusy(true, R.string.preparing_image)
                worker.execute {
                    val png = File(pdf.parentFile, pdf.nameWithoutExtension + ".png")
                    val ok = try {
                        BillFiles.pdfToPng(pdf, png)
                        true
                    } catch (t: Throwable) {
                        false
                    }
                    runOnUiThread {
                        setBusy(false)
                        if (ok) onReady(png) else captureScreen()?.let(onReady)
                    }
                }
            },
            onFail = { captureScreen()?.let(onReady) },
        )
    }

    /** Last resort: a picture of what is on screen now. */
    private fun captureScreen(): File? = try {
        val bmp = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bmp))
        val f = File(File(cacheDir, "bills").apply { mkdirs() }, fileBaseName() + "-screen.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f
    } catch (t: Throwable) {
        toast(getString(R.string.save_failed, t.message ?: t.javaClass.simpleName))
        null
    }

    private fun downloadPdf() = withStorage {
        makePdf(
            onReady = { saveAndOffer(it, "application/pdf", pictures = false, saved = R.string.saved_pdf) },
            onFail = { printWithSystemDialog() },
        )
    }

    private fun saveImage() = withStorage {
        makeImage { saveAndOffer(it, "image/png", pictures = true, saved = R.string.saved_image) }
    }

    private fun shareBill() {
        if (!billShown) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_as)
            .setItems(arrayOf(getString(R.string.share_pdf), getString(R.string.share_image))) { _, which ->
                val title = getString(R.string.share)
                if (which == 0) {
                    makePdf(
                        onReady = { BillFiles.share(this, it, "application/pdf", title) },
                        onFail = { makeImage { png -> BillFiles.share(this, png, "image/png", title) } },
                    )
                } else {
                    makeImage { BillFiles.share(this, it, "image/png", title) }
                }
            }
            .show()
    }

    private fun saveAndOffer(file: File, mime: String, pictures: Boolean, @StringRes saved: Int) {
        worker.execute {
            val result = runCatching { BillFiles.saveToPublic(this, file, file.name, mime, pictures) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
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

    /** The normal Android print screen; "Save as PDF" there works on every phone. */
    private fun printWithSystemDialog() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        toast(R.string.pdf_fallback)
        try {
            printManager.print(
                fileBaseName(),
                webView.createPrintDocumentAdapter(fileBaseName()),
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

    private fun asset(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    private fun toast(@StringRes res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Lets the website's own "Print" button save the bill through the app. */
    private inner class PageBridge {
        @JavascriptInterface
        fun print() {
            runOnUiThread { if (billShown) downloadPdf() }
        }
    }
}

package com.fescobill.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors

/**
 * Several bills at once: the numbers come from pasted text (e.g. the WhatsApp message), bill
 * photos or saved bills. Each bill is opened in turn ([BillActivity] in "batch" mode saves it and
 * comes back by itself), then all bills are shown with Save / Share, plus Share all and Save all.
 */
class BatchActivity : AppCompatActivity() {

    private class Item(val query: BillQuery, var status: String? = null, var verified: String = "")

    /** A number read from one of the chosen photos, for the person to check before it is used. */
    private class PhotoNumber(var query: BillQuery?, val verified: OcrScanner.Verified?, val consumerId: String?)

    private lateinit var input: TextInputEditText
    private lateinit var foundText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var inputSection: View
    private lateinit var resultsSection: View
    private lateinit var resultsTitle: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var busyOverlay: View
    private lateinit var busyText: TextView
    private lateinit var scanner: OcrScanner
    private lateinit var cache: BillCache
    private lateinit var saved: SavedBills
    private val worker = Executors.newSingleThreadExecutor()

    private var items = ArrayList<Item>()
    private var next = 0
    private var running = false
    /** Re-running a single bill from the results ("Try again"). */
    private var single = false
    private var pendingStorageAction: (() -> Unit)? = null
    /** Consumer ID read from the photo of each bill (reference digits -> consumer ID), to check the downloaded bill. */
    private val expectIds = HashMap<String, String>()

    private val billLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val status = if (r.resultCode == RESULT_OK) r.data?.getStringExtra(BillActivity.RESULT_STATUS) else null
        if (status == null) {
            // Back was pressed on a bill: stop here and show what is done so far.
            running = false
            showResults()
            return@registerForActivityResult
        }
        items.getOrNull(next)?.let {
            it.status = status
            it.verified = r.data?.getStringExtra(BillActivity.RESULT_VERIFIED).orEmpty()
        }
        if (single) {
            running = false
            single = false
            showResults()
        } else {
            next++
            runNext()
        }
    }

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) readPhotos(uris)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) action?.invoke() else toast(getString(R.string.storage_permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        scanner = OcrScanner(this)
        cache = BillCache(this)
        saved = SavedBills(this)

        input = findViewById(R.id.batchInput)
        foundText = findViewById(R.id.foundText)
        startButton = findViewById(R.id.startButton)
        inputSection = findViewById(R.id.inputSection)
        resultsSection = findViewById(R.id.resultsSection)
        resultsTitle = findViewById(R.id.resultsTitle)
        resultsList = findViewById(R.id.resultsList)
        busyOverlay = findViewById(R.id.busyOverlay)
        busyText = findViewById(R.id.busyText)

        input.doAfterTextChanged { updateFound() }
        findViewById<View>(R.id.addPhotosButton).setOnClickListener {
            try {
                pickPhotos.launch("image/*")
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.no_gallery_app))
            }
        }
        findViewById<View>(R.id.addSavedButton).setOnClickListener { chooseSaved() }
        startButton.setOnClickListener { startRun() }
        findViewById<View>(R.id.shareAllButton).setOnClickListener { shareAll() }
        findViewById<View>(R.id.saveAllButton).setOnClickListener { withStorage { saveAll() } }
        findViewById<View>(R.id.newListButton).setOnClickListener {
            resultsSection.isVisible = false
            inputSection.isVisible = true
        }

        if (savedInstanceState != null) restore(savedInstanceState)
        updateFound()
    }

    override fun onDestroy() {
        scanner.close()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("digits", ArrayList(items.map { it.query.digits }))
        outState.putStringArrayList("status", ArrayList(items.map { it.status.orEmpty() }))
        outState.putStringArrayList("verified", ArrayList(items.map { it.verified }))
        outState.putSerializable("expect", HashMap(expectIds))
        outState.putInt("next", next)
        outState.putBoolean("running", running)
        outState.putBoolean("single", single)
        outState.putBoolean("results", resultsSection.isVisible)
    }

    private fun restore(state: Bundle) {
        val digits = state.getStringArrayList("digits").orEmpty()
        val status = state.getStringArrayList("status").orEmpty()
        val verified = state.getStringArrayList("verified").orEmpty()
        items = ArrayList(digits.mapIndexedNotNull { i, d ->
            RefNumber.parse(d)?.let { Item(it, status.getOrNull(i)?.ifBlank { null }, verified.getOrNull(i).orEmpty()) }
        })
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        (state.getSerializable("expect") as? HashMap<String, String>)?.let { expectIds.putAll(it) }
        next = state.getInt("next")
        running = state.getBoolean("running")
        single = state.getBoolean("single")
        if (state.getBoolean("results")) showResults()
    }

    // ---- Collecting the numbers ------------------------------------------------------------

    private fun queries(): List<BillQuery> = RefNumber.findAll(input.text?.toString().orEmpty())

    private fun label(q: BillQuery): String {
        val name = saved.all().firstOrNull { it.digits == q.digits }?.name.orEmpty()
        val number = q.display + if (q.mode == SearchMode.CUSTOMER_ID) " " + getString(R.string.customer_id_suffix) else ""
        return if (name.isNotBlank()) "$name • $number" else number
    }

    private fun updateFound() {
        val q = queries()
        startButton.isEnabled = q.isNotEmpty()
        foundText.text = if (q.isEmpty()) {
            getString(R.string.batch_none)
        } else {
            getString(R.string.batch_found, q.size) + "\n" + q.joinToString("\n") { "•  " + label(it) }
        }
    }

    private fun appendNumbers(numbers: List<String>) {
        val have = queries().map { it.digits }.toSet()
        val add = numbers.filter { it !in have }.distinct()
        if (add.isEmpty()) return
        val text = input.text?.toString().orEmpty().trimEnd()
        input.setText((if (text.isEmpty()) "" else text + "\n") + add.joinToString("\n"))
        input.setSelection(input.text?.length ?: 0)
    }

    private fun chooseSaved() {
        val entries = saved.all()
        if (entries.isEmpty()) {
            toast(getString(R.string.saved_empty))
            return
        }
        val labels = entries.map { e -> if (e.name.isNotBlank()) "${e.name} • ${e.query.display}" else e.query.display }.toTypedArray()
        val checked = BooleanArray(entries.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_choose_saved)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.ok) { _, _ ->
                appendNumbers(entries.filterIndexed { i, _ -> checked[i] }.map { it.digits })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Reads the reference number from each photo (enlarged, sharpened, read several times). */
    private fun readPhotos(uris: List<Uri>) {
        busyOverlay.isVisible = true
        val results = ArrayList<PhotoNumber>()

        fun step(i: Int) {
            if (isFinishing || isDestroyed) return
            if (i >= uris.size) {
                busyOverlay.isVisible = false
                reviewPhotos(results)
                return
            }
            busyText.text = getString(R.string.batch_reading, i + 1, uris.size)
            scanner.scan(
                uris[i],
                onResult = { r ->
                    results += PhotoNumber(r.best?.query, r.best, r.consumerId)
                    step(i + 1)
                },
                onError = {
                    results += PhotoNumber(null, null, null)
                    step(i + 1)
                },
            )
        }
        step(0)
    }

    /** Every photo's number next to the enlarged picture of it; red digits were hard to read. */
    private fun reviewPhotos(results: List<PhotoNumber>) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (12 * resources.displayMetrics.density).toInt()
        results.forEachIndexed { i, r ->
            val verified = r.verified
            if (verified == null) {
                list.addView(TextView(this).apply {
                    text = getString(R.string.photo_review_none, i + 1)
                    setPadding(pad * 2, pad, pad * 2, pad)
                    setTextColor(0xFFD32F2F.toInt())
                })
                return@forEachIndexed
            }
            val view = ScanViews.proofView(this, verified, r.consumerId, getString(R.string.photo_review_caption, i + 1))
            val correct = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.correct_it)
                isAllCaps = false
                setOnClickListener { correctNumber(r, view) }
            }
            (view as LinearLayout).addView(correct)
            list.addView(view)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.photo_review_add) { _, _ ->
                for (r in results) {
                    val q = r.query ?: continue
                    r.consumerId?.let { expectIds[q.digits] = it }
                }
                appendNumbers(results.mapNotNull { it.query?.digits })
            }
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun correctNumber(r: PhotoNumber, view: View) {
        val edit = android.widget.EditText(this).apply {
            setText(r.query?.digits.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 22f
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.correct_it)
            .setView(wrapper)
            .setPositiveButton(R.string.ok) { _, _ ->
                val q = RefNumber.parse(edit.text.toString())
                if (q == null) {
                    toast(getString(R.string.ref_error))
                } else {
                    r.query = q
                    ScanViews.bindCorrected(view, this, q)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Getting the bills -----------------------------------------------------------------

    private fun startRun() {
        val q = queries()
        if (q.isEmpty()) return
        items = ArrayList(q.map { Item(it) })
        next = 0
        running = true
        single = false
        runNext()
    }

    private fun runNext() {
        if (!running) return
        if (next >= items.size) {
            running = false
            showResults()
            return
        }
        launchBill(next)
    }

    private fun retry(index: Int) {
        next = index
        running = true
        single = true
        launchBill(index)
    }

    private fun launchBill(index: Int) {
        val q = items[index].query
        billLauncher.launch(BillActivity.batchIntent(this, q, index, items.size, expectIds[q.digits]))
    }

    /** The picture of a bill that was downloaded (or taken from the saved copy) in this run. */
    private fun pictureOf(item: Item): File? =
        if (item.status == BillActivity.STATUS_OK || item.status == BillActivity.STATUS_SAVED_COPY) {
            cache.get(item.query.digits)?.png
        } else {
            null
        }

    private fun fileName(item: Item): String {
        val name = saved.all().firstOrNull { it.digits == item.query.digits }?.name.orEmpty()
            .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        val month = cache.get(item.query.digits)?.month.orEmpty().replace(" ", "")
        return listOf("FESCO-Bill", name, item.query.digits, month).filter { it.isNotBlank() }.joinToString("-") + ".png"
    }

    private fun showResults() {
        inputSection.isVisible = false
        resultsSection.isVisible = true
        resultsList.removeAllViews()
        val ready = items.count { pictureOf(it) != null }
        resultsTitle.text = getString(R.string.batch_results, ready, items.size)
        findViewById<View>(R.id.shareAllButton).isEnabled = ready > 0
        findViewById<View>(R.id.saveAllButton).isEnabled = ready > 0

        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_batch_bill, resultsList, false)
            val picture = pictureOf(item)
            val month = cache.get(item.query.digits)?.month.orEmpty()
            row.findViewById<TextView>(R.id.billTitle).text = "${index + 1}.  " + label(item.query)
            row.findViewById<TextView>(R.id.billStatus).text = when (item.status) {
                BillActivity.STATUS_OK -> getString(R.string.batch_status_ok, month)
                BillActivity.STATUS_SAVED_COPY -> getString(R.string.batch_status_saved, month)
                BillActivity.STATUS_NOT_FOUND -> getString(R.string.batch_status_notfound)
                BillActivity.STATUS_FAILED -> getString(R.string.batch_status_failed)
                BillActivity.STATUS_SKIPPED -> getString(R.string.batch_status_skipped)
                else -> getString(R.string.batch_status_stopped)
            } + when (item.verified) {
                BillActivity.VERIFIED_MATCH -> "\n" + getString(R.string.batch_verify_match)
                BillActivity.VERIFIED_MISMATCH -> "\n" + getString(R.string.batch_verify_mismatch)
                else -> ""
            }
            val image = row.findViewById<ImageView>(R.id.billImage)
            val saveButton = row.findViewById<View>(R.id.billSave)
            val shareButton = row.findViewById<View>(R.id.billShare)
            val retryButton = row.findViewById<View>(R.id.billRetry)
            if (picture != null) {
                loadThumbnail(picture, image)
                image.setOnClickListener { openPicture(picture) }
                saveButton.setOnClickListener { withStorage { saveOne(item, picture) } }
                shareButton.setOnClickListener { shareFiles(listOf(item to picture)) }
            } else {
                saveButton.isVisible = false
                shareButton.isVisible = false
            }
            if (item.status != BillActivity.STATUS_OK) {
                retryButton.isVisible = true
                retryButton.setOnClickListener { retry(index) }
            }
            resultsList.addView(row)
        }
        findViewById<ScrollView>(R.id.scroll).post { findViewById<ScrollView>(R.id.scroll).scrollTo(0, 0) }
    }

    private fun loadThumbnail(file: File, view: ImageView) {
        worker.execute {
            val bitmap = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1100) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (bitmap != null && !isDestroyed) {
                    view.setImageBitmap(bitmap)
                    view.isVisible = true
                }
            }
        }
    }

    private fun openPicture(file: File) {
        BillFiles.open(this, FileProvider.getUriForFile(this, "$packageName.fileprovider", file), "image/png")
    }

    // ---- Save / share ---------------------------------------------------------------------

    private fun readyItems(): List<Pair<Item, File>> = items.mapNotNull { item -> pictureOf(item)?.let { item to it } }

    private fun shareAll() = shareFiles(readyItems())

    /** Shares one or more bill pictures, e.g. to WhatsApp, named after the bill. */
    private fun shareFiles(list: List<Pair<Item, File>>) {
        if (list.isEmpty()) return
        worker.execute {
            val dir = File(cacheDir, "bills").apply { mkdirs() }
            val uris = ArrayList<Uri>()
            for ((item, file) in list) {
                val target = File(dir, fileName(item))
                runCatching { file.copyTo(target, overwrite = true) }.onSuccess {
                    uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
                }
            }
            runOnUiThread {
                if (uris.isEmpty() || isDestroyed) return@runOnUiThread
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                send.type = "image/png"
                send.clipData = ClipData.newRawUri("", uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
        }
    }

    private fun saveOne(item: Item, file: File) {
        worker.execute {
            val ok = runCatching { BillFiles.saveToPublic(this, file, fileName(item), "image/png", pictures = true) }.isSuccess
            runOnUiThread { toast(getString(if (ok) R.string.saved_image else R.string.save_failed, "")) }
        }
    }

    private fun saveAll() {
        val list = readyItems()
        worker.execute {
            var count = 0
            for ((item, file) in list) {
                if (runCatching { BillFiles.saveToPublic(this, file, fileName(item), "image/png", pictures = true) }.isSuccess) count++
            }
            runOnUiThread { toast(getString(R.string.batch_saved_all, count)) }
        }
    }

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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

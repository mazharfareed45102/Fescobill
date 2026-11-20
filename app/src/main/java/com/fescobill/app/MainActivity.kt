package com.fescobill.app

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var refLayout: TextInputLayout
    private lateinit var refInput: TextInputEditText
    private lateinit var savedContainer: LinearLayout
    private lateinit var savedEmpty: TextView
    private lateinit var savedHint: TextView
    private lateinit var busyOverlay: View
    private lateinit var store: SavedBills
    private lateinit var cache: BillCache
    private lateinit var scanner: OcrScanner

    /** What the last bill photo said, to check the downloaded bill against. */
    private var scannedDigits: String? = null
    private var scannedConsumerId: String? = null

    /** Fixed location, so the photo is still found if Android closed the app while the camera was open. */
    private val photoFile: File by lazy { File(cacheDir, "scans/bill_photo.jpg") }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved && photoFile.length() > 0) scan(photoUri())
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SavedBills(this)
        cache = BillCache(this)
        scanner = OcrScanner(this)

        refLayout = findViewById(R.id.refLayout)
        refInput = findViewById(R.id.refInput)
        savedContainer = findViewById(R.id.savedContainer)
        savedEmpty = findViewById(R.id.savedEmpty)
        savedHint = findViewById(R.id.savedHint)
        busyOverlay = findViewById(R.id.busyOverlay)

        findViewById<View>(R.id.viewBillButton).setOnClickListener { openFromInput() }
        findViewById<View>(R.id.scanCameraButton).setOnClickListener { launchCamera() }
        findViewById<View>(R.id.scanGalleryButton).setOnClickListener { launchGallery() }
        findViewById<View>(R.id.batchButton).setOnClickListener {
            startActivity(android.content.Intent(this, BatchActivity::class.java))
        }

        refInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openFromInput()
                true
            } else {
                false
            }
        }
        refInput.doAfterTextChanged { updateHelper() }
    }

    override fun onResume() {
        super.onResume()
        renderSaved()
    }

    override fun onDestroy() {
        scanner.close()
        super.onDestroy()
    }

    private fun updateHelper() {
        val q = RefNumber.parse(refInput.text?.toString().orEmpty())
        refLayout.error = null
        refLayout.helperText = when (q?.mode) {
            SearchMode.REFERENCE -> getString(R.string.ref_ok_reference, q.display)
            SearchMode.CUSTOMER_ID -> getString(R.string.ref_ok_customer, q.display)
            null -> getString(R.string.ref_helper)
        }
    }

    private fun openFromInput() {
        val q = RefNumber.parse(refInput.text?.toString().orEmpty())
        if (q == null) {
            refLayout.error = getString(R.string.ref_error)
            refInput.requestFocus()
            return
        }
        hideKeyboard()
        // The Consumer ID from the last photo still applies if the number is (a correction of) the one read from it.
        val expect = scannedConsumerId?.takeIf {
            val from = scannedDigits
            from != null && from.length == q.digits.length && ReadingVote.hamming(from, q.digits) <= 4
        }
        openBill(q, expect)
    }

    private fun openBill(q: BillQuery, expectConsumerId: String? = null) {
        startActivity(BillActivity.intent(this, q).apply {
            if (expectConsumerId != null) putExtra(BillActivity.EXTRA_EXPECT_ID, expectConsumerId)
        })
    }

    // ---- Photo scanning -------------------------------------------------------------------

    private fun photoUri(): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)

    private fun launchCamera() {
        photoFile.parentFile?.mkdirs()
        photoFile.writeBytes(ByteArray(0)) // so an old photo is never read again by mistake
        try {
            takePicture.launch(photoUri())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGallery() {
        try {
            pickImage.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_gallery_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun scan(uri: Uri) {
        busyOverlay.isVisible = true
        scanner.scan(
            uri,
            onResult = { result ->
                busyOverlay.isVisible = false
                showScanResult(result)
            },
            onError = { e ->
                busyOverlay.isVisible = false
                if (!isFinishing) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.scan_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            },
        )
    }

    /** Shows the number read from the photo next to the enlarged picture of it, for the person to confirm. */
    private fun showScanResult(result: OcrScanner.ScanResult) {
        if (isFinishing || isDestroyed) return
        val best = result.best
        if (best == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_none_title)
                .setMessage(R.string.scan_none_msg)
                .setPositiveButton(R.string.try_again) { _, _ -> launchCamera() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        scannedDigits = best.query.digits
        scannedConsumerId = result.consumerId
        fillDigits(best.query.digits)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (best.consensus.sure) R.string.scan_found_title else R.string.scan_check_title)
            .setView(ScanViews.proofView(this, best, result.consumerId))
            .setPositiveButton(R.string.view_bill) { _, _ -> openBill(best.query, result.consumerId) }
            .setNeutralButton(R.string.correct_it) { _, _ -> focusInput() }
        if (result.others.isNotEmpty()) {
            builder.setNegativeButton(R.string.other_number) { _, _ -> chooseOther(result) }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun chooseOther(result: OcrScanner.ScanResult) {
        val shown = result.others.take(5)
        val items = shown.map { c -> "${c.display}\n${ScanViews.kindText(this, c)}" }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_choose_title)
            .setItems(items) { _, which ->
                scannedDigits = shown[which].digits
                fillDigits(shown[which].digits)
                focusInput()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fillDigits(digits: String) {
        refInput.setText(digits)
        refInput.setSelection(refInput.text?.length ?: 0)
    }

    private fun focusInput() {
        refInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(refInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(refInput.windowToken, 0)
    }

    // ---- Saved bills ----------------------------------------------------------------------

    private fun renderSaved() {
        val entries = store.all()
        savedContainer.removeAllViews()
        savedEmpty.isVisible = entries.isEmpty()
        savedHint.isVisible = entries.isNotEmpty()
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val row = inflater.inflate(R.layout.item_saved_bill, savedContainer, false)
            val opened = getString(
                R.string.last_opened,
                DateUtils.getRelativeTimeSpanString(e.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            )
            val saved = if (Prefs.isWaiting(this, e.digits)) {
                getString(R.string.waiting_for_fesco)
            } else {
                cache.get(e.digits)?.let {
                    if (it.month.isNotBlank()) getString(R.string.saved_on_phone, it.month) else getString(R.string.saved_on_phone_no_month)
                }
            }
            row.findViewById<TextView>(R.id.savedName).text = e.name.ifBlank { e.query.display }
            row.findViewById<TextView>(R.id.savedNumber).text =
                listOfNotNull(e.query.display.takeIf { e.name.isNotBlank() }, saved, opened).joinToString("  •  ")
            row.setOnClickListener { openBill(e.query) }
            row.setOnLongClickListener {
                showEntryMenu(e)
                true
            }
            savedContainer.addView(row)
        }
    }

    private fun showEntryMenu(e: SavedBills.Entry) {
        val title = e.name.ifBlank { e.query.display }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) showRename(e) else confirmDelete(e, title)
            }
            .show()
    }

    private fun showRename(e: SavedBills.Entry) {
        val input = EditText(this).apply {
            setText(e.name)
            hint = getString(R.string.rename_hint)
            setSingleLine()
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(wrapper)
            .setPositiveButton(R.string.ok) { _, _ ->
                store.rename(e.digits, input.text.toString())
                renderSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(e: SavedBills.Entry, title: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(e.digits)
                cache.delete(e.digits)
                BillRetryWorker.cancel(this, e.digits)
                renderSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

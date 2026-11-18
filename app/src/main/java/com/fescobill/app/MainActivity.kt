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
    private lateinit var scanner: OcrScanner

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
        openBill(q)
    }

    private fun openBill(q: BillQuery) {
        startActivity(BillActivity.intent(this, q))
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
            onResult = { candidates ->
                busyOverlay.isVisible = false
                showScanResult(candidates)
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

    private fun showScanResult(candidates: List<Candidate>) {
        if (isFinishing || isDestroyed) return
        if (candidates.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_none_title)
                .setMessage(R.string.scan_none_msg)
                .setPositiveButton(R.string.try_again) { _, _ -> launchCamera() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (RefExtractor.isConfident(candidates)) {
            val best = candidates[0]
            fillInput(best)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_found_title)
                .setMessage(getString(R.string.scan_found_msg, best.display, describe(best, true)))
                .setPositiveButton(R.string.view_bill) { _, _ -> openBill(best.toQuery()) }
                .setNegativeButton(R.string.edit) { _, _ -> focusInput() }
                .show()
            return
        }

        val shown = candidates.take(5)
        var chosen = 0
        fillInput(shown[0])
        val items = shown.mapIndexed { i, c -> "${c.display}\n${describe(c, i == 0)}" }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_choose_title)
            .setSingleChoiceItems(items, 0) { _, which ->
                chosen = which
                fillInput(shown[which])
            }
            .setPositiveButton(R.string.view_bill) { _, _ -> openBill(shown[chosen].toQuery()) }
            .setNegativeButton(R.string.edit) { _, _ -> focusInput() }
            .show()
    }

    private fun describe(c: Candidate, first: Boolean): String = getString(
        when {
            c.mode == SearchMode.CUSTOMER_ID -> R.string.label_customer_id
            c.label == LabelKind.OLD_ACCOUNT -> R.string.label_old_account
            c.label == LabelKind.REFERENCE || c.suffix != null -> R.string.label_reference
            first -> R.string.label_reference_likely
            else -> R.string.label_unknown
        }
    )

    private fun fillInput(c: Candidate) {
        refInput.setText(c.digits)
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
            row.findViewById<TextView>(R.id.savedName).text = e.name.ifBlank { e.query.display }
            row.findViewById<TextView>(R.id.savedNumber).text =
                if (e.name.isBlank()) opened else "${e.query.display}  •  $opened"
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
                renderSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

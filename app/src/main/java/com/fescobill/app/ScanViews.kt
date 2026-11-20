package com.fescobill.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible

/** Shows what was read from a bill photo, with the enlarged picture of the number as proof. */
object ScanViews {

    private const val DOUBT_RED = 0xFFD32F2F.toInt()

    /** The number with the digits to double-check in red, bold and underlined. */
    fun numberText(verified: OcrScanner.Verified): CharSequence {
        val digits = verified.consensus.digits
        val display = RefNumber.format(digits)
        val suffix = verified.candidate.suffix?.let { " $it" }.orEmpty()
        val text = SpannableString(display + suffix)
        for (i in verified.consensus.uncertain) {
            val at = displayIndex(digits.length, i)
            if (at < display.length) {
                for (span in listOf(ForegroundColorSpan(DOUBT_RED), StyleSpan(Typeface.BOLD), UnderlineSpan())) {
                    text.setSpan(span, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return text
    }

    /** Where digit [i] is in the grouped form "07 13523 0628703". */
    fun displayIndex(length: Int, i: Int): Int =
        if (length == RefNumber.REFERENCE_LENGTH) i + (if (i >= 2) 1 else 0) + (if (i >= 7) 1 else 0) else i

    fun statusText(context: Context, verified: OcrScanner.Verified): String {
        val c = verified.consensus
        return when {
            c.sure -> context.getString(R.string.proof_sure, c.readings)
            c.uncertain.isNotEmpty() -> context.getString(R.string.proof_doubt, c.uncertain.size)
            else -> context.getString(R.string.proof_once)
        }
    }

    fun kindText(context: Context, candidate: Candidate): String = context.getString(
        when {
            candidate.mode == SearchMode.CUSTOMER_ID -> R.string.label_customer_id
            candidate.label == LabelKind.OLD_ACCOUNT -> R.string.label_old_account
            candidate.label == LabelKind.REFERENCE || candidate.suffix != null -> R.string.label_reference
            else -> R.string.label_reference_likely
        }
    )

    fun proofView(context: Context, verified: OcrScanner.Verified, consumerId: String?, caption: String? = null): View {
        val view = LayoutInflater.from(context).inflate(R.layout.view_scan_proof, null, false)
        bind(view, context, verified, consumerId, caption)
        return view
    }

    fun bind(view: View, context: Context, verified: OcrScanner.Verified, consumerId: String?, caption: String? = null) {
        val image = view.findViewById<ImageView>(R.id.proofImage)
        val proof = verified.proof
        image.isVisible = proof != null
        if (proof != null) image.setImageBitmap(proof)
        view.findViewById<TextView>(R.id.proofCaption).text = caption ?: context.getString(R.string.proof_caption)
        view.findViewById<TextView>(R.id.proofNumber).text = numberText(verified)
        view.findViewById<TextView>(R.id.proofKind).text = kindText(context, verified.candidate)
        val status = view.findViewById<TextView>(R.id.proofStatus)
        status.text = statusText(context, verified)
        status.setTextColor(if (verified.consensus.sure) 0xFF2E7D32.toInt() else DOUBT_RED)
        val consumer = view.findViewById<TextView>(R.id.proofConsumer)
        consumer.isVisible = consumerId != null
        if (consumerId != null) consumer.text = context.getString(R.string.proof_consumer, consumerId)
    }

    /** The number after the person typed a correction: shown plainly. */
    fun bindCorrected(view: View, context: Context, q: BillQuery) {
        view.findViewById<TextView>(R.id.proofNumber).text = q.display
        val status = view.findViewById<TextView>(R.id.proofStatus)
        status.text = context.getString(R.string.proof_corrected)
        status.setTextColor(Color.parseColor("#2E7D32"))
    }
}

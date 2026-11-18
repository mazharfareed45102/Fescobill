package com.fescobill.app

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The last bill seen for each number, kept on the phone as a PDF and a picture.
 * It opens instantly (even offline) while the app checks FESCO for a newer bill.
 */
class BillCache(context: Context) {

    class Entry(val pdf: File, val png: File?, val savedAt: Long, val month: String)

    private val root = File(context.applicationContext.filesDir, "bill-cache")

    fun get(digits: String): Entry? {
        val dir = File(root, digits)
        val pdf = File(dir, PDF)
        if (!pdf.isFile || pdf.length() == 0L) return null
        val png = File(dir, PNG).takeIf { it.isFile && it.length() > 0 }
        var savedAt = pdf.lastModified()
        var month = ""
        try {
            val meta = JSONObject(File(dir, META).readText())
            savedAt = meta.optLong("savedAt", savedAt)
            month = meta.optString("month")
        } catch (e: IOException) {
            // no metadata; use the file date
        } catch (e: JSONException) {
            // ditto
        }
        return Entry(pdf, png, savedAt, month)
    }

    /** Stores a freshly made bill PDF (and picture), replacing the previous one for this number. */
    fun put(digits: String, pdf: File, png: File?, month: String): Entry {
        val dir = File(root, digits)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val savedAt = System.currentTimeMillis()
        install(pdf, File(dir, PDF))
        val pngTarget = File(dir, PNG)
        if (png != null) install(png, pngTarget) else pngTarget.delete()
        File(dir, META).writeText(JSONObject().put("savedAt", savedAt).put("month", month).toString())
        return Entry(File(dir, PDF), pngTarget.takeIf { png != null }, savedAt, month)
    }

    fun delete(digits: String) {
        File(root, digits).deleteRecursively()
    }

    private fun install(source: File, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("cannot save $target")
        }
    }

    private companion object {
        const val PDF = "bill.pdf"
        const val PNG = "bill.png"
        const val META = "meta.json"
    }
}

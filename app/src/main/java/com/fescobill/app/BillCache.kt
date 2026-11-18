package com.fescobill.app

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * The last bill downloaded for each number, kept on the phone:
 *  - page.html: the complete bill page (styles and pictures inside), shown instantly and offline;
 *  - bill.pdf / bill.png: the printed bill, made from that page, for Download / Share.
 */
class BillCache(context: Context) {

    class Entry(
        val page: File?,
        val baseUrl: String,
        val pdf: File?,
        val png: File?,
        val month: String,
        val pageAt: Long,
        val filesAt: Long,
    ) {
        val savedAt: Long get() = max(pageAt, filesAt)

        /** True when the PDF was made from the current saved page (or there is no page). */
        val filesCurrent: Boolean get() = pdf != null && (page == null || filesAt >= pageAt)
    }

    private val root = File(context.applicationContext.filesDir, "bill-cache")

    fun get(digits: String): Entry? = synchronized(LOCK) {
        val dir = File(root, digits)
        val page = File(dir, PAGE).takeIf { it.isFile && it.length() > 0 }
        val pdf = File(dir, PDF).takeIf { it.isFile && it.length() > 0 }
        val png = File(dir, PNG).takeIf { it.isFile && it.length() > 0 }
        if (page == null && pdf == null) return null
        val meta = readMeta(dir)
        val legacy = meta.optLong("savedAt", pdf?.lastModified() ?: 0L)
        Entry(
            page = page,
            baseUrl = meta.optString("baseUrl"),
            pdf = pdf,
            png = png,
            month = meta.optString("month"),
            pageAt = if (page != null) meta.optLong("pageAt", page.lastModified()) else 0L,
            filesAt = if (pdf != null) meta.optLong("filesAt", legacy) else 0L,
        )
    }

    /** Saves a freshly downloaded, self-contained bill page. */
    fun putPage(digits: String, html: String, baseUrl: String, month: String): Entry {
        synchronized(LOCK) {
            val dir = dirFor(digits)
            val tmp = File(dir, "$PAGE.tmp")
            tmp.writeText(html)
            install(tmp, File(dir, PAGE))
            val meta = readMeta(dir)
            meta.put("baseUrl", baseUrl).put("pageAt", System.currentTimeMillis())
            if (month.isNotBlank()) meta.put("month", month)
            writeMeta(dir, meta)
        }
        return get(digits)!!
    }

    /** Saves the PDF (and picture) made from the bill on screen. */
    fun putFiles(digits: String, pdf: File, png: File?, month: String): Entry {
        synchronized(LOCK) {
            val dir = dirFor(digits)
            copyInto(pdf, File(dir, PDF))
            val pngTarget = File(dir, PNG)
            if (png != null) copyInto(png, pngTarget) else pngTarget.delete()
            val meta = readMeta(dir)
            meta.put("filesAt", System.currentTimeMillis())
            if (month.isNotBlank()) meta.put("month", month)
            writeMeta(dir, meta)
        }
        return get(digits)!!
    }

    fun delete(digits: String) {
        synchronized(LOCK) { File(root, digits).deleteRecursively() }
    }

    private fun dirFor(digits: String): File {
        val dir = File(root, digits)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        return dir
    }

    private fun readMeta(dir: File): JSONObject = try {
        JSONObject(File(dir, META).readText())
    } catch (e: IOException) {
        JSONObject()
    } catch (e: JSONException) {
        JSONObject()
    }

    private fun writeMeta(dir: File, meta: JSONObject) {
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(meta.toString())
        install(tmp, File(dir, META))
    }

    private fun copyInto(source: File, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        source.copyTo(tmp, overwrite = true)
        install(tmp, target)
    }

    private fun install(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("cannot save $target")
        }
    }

    private companion object {
        val LOCK = Any()
        const val PAGE = "page.html"
        const val PDF = "bill.pdf"
        const val PNG = "bill.png"
        const val META = "meta.json"
    }
}

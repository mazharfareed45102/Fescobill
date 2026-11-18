package com.fescobill.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/** Saving bills to Downloads / Pictures, sharing them, and turning the PDF into a picture. */
object BillFiles {
    const val FOLDER = "FESCO Bills"

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    /**
     * Copies [source] into Downloads/FESCO Bills (or Pictures/FESCO Bills when [pictures] is true)
     * and returns a content uri that other apps can open.
     */
    fun saveToPublic(context: Context, source: File, displayName: String, mime: String, pictures: Boolean): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = if (pictures) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val dir = (if (pictures) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("could not create the file")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("could not write the file")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }

        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            if (pictures) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(base, FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val target = uniqueFile(dir, displayName)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return FileProvider.getUriForFile(context, authority(context), target)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists()) f = File(dir, "$stem (${n++})$ext")
        return f
    }

    fun open(activity: Activity, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_pdf_viewer, Toast.LENGTH_LONG).show()
        }
    }

    fun share(activity: Activity, file: File, mime: String, title: CharSequence) {
        val uri = FileProvider.getUriForFile(activity, authority(activity), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, title))
    }

    /** Renders the bill PDF to one tall PNG picture (pages stacked, blank pages skipped). */
    fun pdfToPng(pdf: File, out: File) {
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val pageCount = min(renderer.pageCount, 4)
            if (pageCount == 0) throw IOException("the PDF has no pages")
            val width = if (pageCount <= 2) 1654 else 1240 // A4 at 200 / 150 dpi
            val pages = ArrayList<Bitmap>()
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    val height = (width.toLong() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (i > 0 && isBlank(bmp)) bmp.recycle() else pages += bmp
                } finally {
                    page.close()
                }
            }
            val combined = if (pages.size == 1) {
                pages[0]
            } else {
                Bitmap.createBitmap(width, pages.sumOf { it.height }, Bitmap.Config.ARGB_8888).also { result ->
                    val canvas = Canvas(result)
                    canvas.drawColor(Color.WHITE)
                    var y = 0f
                    for (p in pages) {
                        canvas.drawBitmap(p, 0f, y, null)
                        y += p.height
                        p.recycle()
                    }
                }
            }
            FileOutputStream(out).use { combined.compress(Bitmap.CompressFormat.PNG, 100, it) }
            combined.recycle()
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    private fun isBlank(bmp: Bitmap): Boolean {
        val step = 6
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) < 235 || Color.green(c) < 235 || Color.blue(c) < 235) return false
                x += step
            }
            y += step
        }
        return true
    }
}

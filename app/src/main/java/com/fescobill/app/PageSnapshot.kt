package com.fescobill.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Makes a bill page self-contained: its style sheets, scripts and pictures are downloaded and put
 * inside the HTML. The saved bill then shows and prints exactly like the website, with no internet
 * and without the (often unreachable) FESCO server.
 */
object PageSnapshot {

    class Fetched(val bytes: ByteArray, val contentType: String?)

    fun interface Fetcher {
        /** The resource at [url], or null if it could not be downloaded. */
        fun fetch(url: String): Fetched?
    }

    const val MAX_RESOURCE_BYTES = 3_000_000

    private val IC_DOT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC = Regex("<script\\b([^>]*)>\\s*</script\\s*>", IC_DOT)
    private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("(\\bsrc\\s*=\\s*)(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", RegexOption.IGNORE_CASE)
    private val STYLE_BLOCK = Regex("<style\\b([^>]*)>(.*?)</style\\s*>", IC_DOT)
    private val CSS_URL = Regex("url\\(\\s*([\"']?)([^)\"']+)\\1\\s*\\)", RegexOption.IGNORE_CASE)

    private class Budget(private var bytesLeft: Long, private val deadline: Long) {
        fun expired() = System.currentTimeMillis() > deadline
        fun take(n: Int): Boolean {
            if (n > bytesLeft) return false
            bytesLeft -= n
            return true
        }
    }

    /**
     * Returns [html] with external resources replaced by inline copies. Anything that cannot be
     * downloaded (in time) keeps its original address, which still works whenever the site is up.
     */
    fun build(
        html: String,
        pageUrl: String,
        fetcher: Fetcher,
        maxTotalBytes: Long = 15_000_000,
        deadlineMs: Long = 40_000,
    ): String {
        val base = pageUrl.toHttpUrlOrNull() ?: return html
        val budget = Budget(maxTotalBytes, System.currentTimeMillis() + deadlineMs)
        val seen = HashMap<String, Fetched?>()
        val get: (String) -> Fetched? = { url ->
            if (seen.containsKey(url)) {
                seen[url]
            } else {
                val f = if (budget.expired()) null else fetcher.fetch(url)?.takeIf { budget.take(it.bytes.size) }
                seen[url] = f
                f
            }
        }

        var out = LINK.replace(html) { m ->
            val attrs = PitcHtml.attributes(m.value.removePrefix("<link").removeSuffix(">"))
            val rel = attrs["rel"].orEmpty().lowercase().split(' ')
            val href = attrs["href"]
            val abs = href?.let { resolve(base, it) }
            val css = if ("stylesheet" in rel && abs != null) get(abs.toString()) else null
            if (css == null || abs == null) {
                m.value
            } else {
                val media = attrs["media"]?.let { " media=\"$it\"" }.orEmpty()
                "<style$media>" + inlineCss(String(css.bytes, Charsets.UTF_8), abs, get).replace("</style", "<\\/style") + "</style>"
            }
        }

        out = SCRIPT_SRC.replace(out) { m ->
            val attrs = PitcHtml.attributes(m.groupValues[1])
            val abs = attrs["src"]?.let { resolve(base, it) } ?: return@replace m.value
            val js = get(abs.toString()) ?: return@replace m.value
            val keep = attrs.filterKeys { it != "src" && it != "integrity" && it != "crossorigin" }
                .entries.joinToString("") { (k, v) -> " $k=\"${v.replace("\"", "&quot;")}\"" }
            "<script$keep>" + String(js.bytes, Charsets.UTF_8).replace("</script", "<\\/script") + "</script>"
        }

        out = IMG.replace(out) { m ->
            SRC_ATTR.replace(m.value) { s ->
                val raw = s.groups[3]?.value ?: s.groups[4]?.value ?: s.groups[5]?.value ?: ""
                val src = PitcHtml.decodeEntities(raw)
                val abs = if (src.startsWith("data:", ignoreCase = true)) null else resolve(base, src)
                val data = abs?.let { u -> get(u.toString())?.let { dataUri(it, u) } }
                if (data == null) s.value else "${s.groupValues[1]}\"$data\""
            }
        }

        out = STYLE_BLOCK.replace(out) { m ->
            "<style${m.groupValues[1]}>" + inlineCss(m.groupValues[2], base, get) + "</style>"
        }
        return out
    }

    private fun inlineCss(css: String, cssUrl: HttpUrl, get: (String) -> Fetched?): String =
        CSS_URL.replace(css) { m ->
            val ref = m.groupValues[2].trim()
            if (ref.startsWith("data:", ignoreCase = true) || ref.startsWith("#")) return@replace m.value
            val abs = resolve(cssUrl, ref) ?: return@replace m.value
            val data = get(abs.toString())?.let { dataUri(it, abs) } ?: return@replace m.value
            "url(\"$data\")"
        }

    private fun resolve(base: HttpUrl, ref: String): HttpUrl? {
        val r = ref.trim()
        if (r.isEmpty() || r.startsWith("javascript:", ignoreCase = true)) return null
        return base.resolve(r)
    }

    private fun dataUri(f: Fetched, url: HttpUrl): String {
        val mime = f.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: guessMime(url.encodedPath)
        return "data:$mime;base64," + f.bytes.toByteString().base64()
    }

    private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "eot" -> "application/vnd.ms-fontobject"
        "css" -> "text/css"
        "js" -> "text/javascript"
        else -> "application/octet-stream"
    }
}

/** Downloads page resources with the same connection settings (and cookies) that fetched the page. */
class HttpResourceFetcher(
    client: OkHttpClient,
    private val userAgent: String,
    private val cookieFor: (String) -> String? = { null },
) : PageSnapshot.Fetcher {

    private val client = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun fetch(url: String): PageSnapshot.Fetched? = try {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            cookieFor(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                null
            } else {
                val source = body.source()
                if (source.request(PageSnapshot.MAX_RESOURCE_BYTES + 1L)) {
                    null // too big to keep inside the page
                } else {
                    PageSnapshot.Fetched(source.readByteArray(), response.header("Content-Type"))
                }
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

package xyz.amasuda.ws

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.util.Base64

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Int = HttpFetcher.DEFAULT_TIMEOUT_MS,
    /** Send and keep the WebView's cookies, as a request from the page would. */
    val withCookies: Boolean = true,
)

class HttpResponse(
    val status: Int,
    val statusText: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val finalUrl: String,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val mimeType: String get() = header("Content-Type")?.substringBefore(';')?.trim().orEmpty()

    /** The body as text in the charset [contentType] (or the response's Content-Type) names, else UTF-8. */
    fun text(contentType: String? = null): String {
        val charset = listOfNotNull(contentType, header("Content-Type")).firstNotNullOfOrNull { charsetOf(it) }
        return String(body, charset ?: Charsets.UTF_8)
    }

    private fun charsetOf(contentType: String): Charset? =
        CHARSET.find(contentType)?.groupValues?.get(1)?.let { runCatching { Charset.forName(it.trim('"')) }.getOrNull() }

    private companion object {
        val CHARSET = Regex("charset\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
    }
}

/** Sends HTTP requests from the app rather than the page: installing scripts and `GM_xmlhttpRequest`. */
interface HttpFetcher {
    /**
     * Sends [request], following redirects to web URLs [followRedirect] accepts. Throws [IOException] when
     * the request fails ([SocketTimeoutException] when it times out).
     */
    fun fetch(request: HttpRequest, followRedirect: (String) -> Boolean = { true }): HttpResponse

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 32 shl 20
    }
}

/** The WebView's cookie store, so requests from scripts are signed in like the page. */
interface CookieJar {
    fun cookiesFor(url: String): String?
    fun store(url: String, setCookieHeaders: List<String>)
}

class UrlConnectionFetcher(
    private val cookies: CookieJar?,
    private val userAgent: () -> String,
) : HttpFetcher {

    override fun fetch(request: HttpRequest, followRedirect: (String) -> Boolean): HttpResponse {
        var url = request.url
        var method = request.method
        var body = request.body
        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = request.timeoutMs
                conn.readTimeout = request.timeoutMs
                conn.requestMethod = method
                conn.setRequestProperty("User-Agent", userAgent())
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val hasCookieHeader = request.headers.keys.any { it.equals("Cookie", ignoreCase = true) }
                if (request.withCookies && !hasCookieHeader) cookies?.cookiesFor(url)?.let { conn.setRequestProperty("Cookie", it) }
                if (body != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body!!.toByteArray()) }
                }
                val status = conn.responseCode
                val headers = conn.headerFields.flatMap { (k, values) -> if (k == null) emptyList() else values.map { k to it } }
                if (request.withCookies) {
                    cookies?.store(url, headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.map { it.second })
                }
                val location = conn.getHeaderField("Location")
                if (status in 300..399 && location != null) {
                    val next = URL(URL(url), location).toString()
                    if (!UrlPatterns.isWeb(next) || !followRedirect(next)) throw IOException("redirect to $next is not allowed")
                    if (status == 303 || (status in 301..302 && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    url = next
                    return@repeat
                }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)
                return HttpResponse(status, conn.responseMessage.orEmpty(), headers, bytes, url)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > HttpFetcher.MAX_BODY_BYTES) throw IOException("response is larger than ${HttpFetcher.MAX_BODY_BYTES} bytes")
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        const val BUFFER_BYTES = 16 shl 10
    }
}

/** A `GM_xmlhttpRequest` call as the page script hands it to the app, and the answer it gets back. */
data class XhrRequest(val http: HttpRequest, val binary: Boolean, val overrideMimeType: String) {
    val host: String get() = UrlPatterns.hostOf(http.url)

    /** The JSON the page script's callback receives. Binary bodies travel as base64. */
    fun encodeResponse(r: HttpResponse): String {
        val headers = r.headers.joinToString("") { "${it.first.lowercase()}: ${it.second}\r\n" }
        val o = JSONObject()
            .put("status", r.status)
            .put("statusText", r.statusText)
            .put("headers", headers)
            .put("finalUrl", r.finalUrl)
            .put("contentType", overrideMimeType.ifBlank { r.header("Content-Type").orEmpty() })
        if (binary) o.put("base64", Base64.getEncoder().encodeToString(r.body)) else o.put("text", r.text(overrideMimeType.ifBlank { null }))
        return o.toString()
    }

    companion object {
        private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        private const val MAX_TIMEOUT_MS = 10 * 60_000

        /** Reads and checks a request from a page. Throws [IllegalArgumentException] for anything malformed. */
        fun decode(json: String?): XhrRequest = try {
            val o = JSONObject(json ?: "")
            val method = o.optString("method", "GET").uppercase().ifEmpty { "GET" }
            require(method in METHODS) { "unsupported method $method" }
            val url = o.getString("url")
            require(UrlPatterns.isWeb(url)) { "not a web URL: $url" }
            val h = o.optJSONObject("headers") ?: JSONObject()
            val headers = h.keys().asSequence().associateWith { h.get(it).toString() }
                .filterKeys { k -> k.isNotBlank() && k.none { it == '\r' || it == '\n' } }
                .filterValues { v -> v.none { it == '\r' || it == '\n' } }
            val timeout = o.optInt("timeout", 0)
            XhrRequest(
                http = HttpRequest(
                    method = method,
                    url = url,
                    headers = headers,
                    body = if (o.isNull("data")) null else o.optString("data"),
                    timeoutMs = if (timeout in 1..MAX_TIMEOUT_MS) timeout else HttpFetcher.DEFAULT_TIMEOUT_MS,
                    withCookies = !o.optBoolean("anonymous"),
                ),
                binary = o.optBoolean("binary"),
                overrideMimeType = o.optString("overrideMimeType"),
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("broken request: ${e.message}", e)
        }

        fun encodeError(message: String, timeout: Boolean = false): String =
            JSONObject().put("error", message).put("timeout", timeout).put("status", 0).toString()
    }
}

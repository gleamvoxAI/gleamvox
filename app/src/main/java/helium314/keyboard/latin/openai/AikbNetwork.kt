// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.Context
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.ailm.AiDictModels
import helium314.keyboard.latin.utils.prefs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null,
                  /** called once the whole body is written, before the answer (the voice panel says "Recognizing…") */
                  val onBodySent: (() -> Unit)? = null)

/**
 * header names in lower case; [body] is never logged. [tls] (e.g. TLSv1.3) and [route] (IPv4, IPv6 or proxy) describe
 * the connection when the transport knows them.
 */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray, val tls: String? = null, val route: String? = null) {
    val requestId: String? get() = headers["x-request-id"]
}

/** a response whose body is read while it arrives (server-sent events); [close] ends the connection */
class HttpStream(val status: Int, val headers: Map<String, String>, val body: InputStream, private val onClose: () -> Unit = {}) : Closeable {
    val requestId: String? get() = headers["x-request-id"]
    override fun close() { try { onClose() } catch (_: Exception) { } }
}

/** lets another thread (the "Cancel" button) end a request at once: the connection is closed */
class CancelSignal {
    @Volatile var isCancelled = false
        private set
    private var onCancel: (() -> Unit)? = null

    fun cancel() {
        val action = synchronized(this) { isCancelled = true; onCancel }
        action?.invoke()
    }

    /** [action] runs on [cancel], or at once if it was cancelled already */
    fun setOnCancel(action: (() -> Unit)?) {
        val runNow = synchronized(this) { onCancel = action; isCancelled }
        if (runNow) action?.invoke()
    }
}

/** does the actual connection; replaced by a fake server in tests */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse

    /** streaming answer; the default reads the whole answer first (enough for fakes) */
    fun stream(request: HttpRequest, cancel: CancelSignal): HttpStream {
        val r = execute(request)
        return HttpStream(r.status, r.headers, ByteArrayInputStream(r.body))
    }
}

/** nothing was sent (switch off, address not allowed, plain text, bad proxy, feature off) or a redirect was not followed */
class NetworkBlockedException(val reason: Reason, val target: String? = null, val status: Int? = null) : IOException("network request blocked: $reason") {
    enum class Reason {
        INTERNET_OFF, NOT_ALLOWED, REDIRECT, BAD_PROXY,
        /** voice through OpenAI is off (no consent, system recognizer chosen) or there is no key */
        VOICE_API_OFF,
        /** ✨ and cleanup are off: no consent for text requests or no key */
        TEXT_API_OFF,
    }
}

/** the only three requests the app may make (docs/AI.md, section 1): exact method and address */
enum class Endpoint(val method: String, val url: String) {
    TRANSCRIPTIONS("POST", OpenAi.TRANSCRIPTIONS_URL),
    MODELS("GET", OpenAi.MODELS_URL),
    RESPONSES("POST", OpenAi.RESPONSES_URL);

    companion object {
        /** null unless [method] and [url] are exactly one of the entries: https, no port, no query, no fragment, no user */
        fun of(method: String, url: String): Endpoint? {
            val uri = try { URI(url) } catch (e: Exception) { return null }
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null)
                return null
            if (url != "https://${uri.host}${uri.rawPath}") return null
            return entries.firstOrNull { it.url == url && it.method == method }
        }
    }
}

/**
 * The only place of the app that opens outgoing connections (docs/AI.md, section 1).
 * Before every request: "Allow internet access" must be on, and the method and address must be one of the three
 * [Endpoint]s on https://api.openai.com; [permits] says whether that feature is switched on now (voice: consent and
 * OpenAI chosen and a key; text: consent and a key; models: always). Redirects are not followed. Nothing about
 * requests is logged here.
 * ai-dict-1 (owner's decision 2026-10-17): besides, [downloadFile] fetches the files of the AI dictionary, only when the
 * owner asked for it, only from the fixed addresses of AiDictModels.
 */
class AikbNetwork(
    private val internetAllowed: () -> Boolean,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val permits: (Endpoint) -> Boolean = { true },
    /** a file of the AI dictionary: a fixed catalogue address, or (redirected = true) the storage it redirected to */
    private val aiDictUrl: (String, Boolean) -> Boolean =
        { url, redirected -> if (redirected) AiDictModels.isStorageRedirect(url) else AiDictModels.isCatalogueUrl(url) },
) {

    /** "Allow internet access" is on (checked again for every request) */
    fun isOn() = internetAllowed()

    private fun check(request: HttpRequest) {
        if (!internetAllowed()) throw NetworkBlockedException(NetworkBlockedException.Reason.INTERNET_OFF)
        val endpoint = Endpoint.of(request.method, request.url)
            ?: throw NetworkBlockedException(NetworkBlockedException.Reason.NOT_ALLOWED, hostAndPath(request.url))
        if (!permits(endpoint)) throw NetworkBlockedException(when (endpoint) {
            Endpoint.TRANSCRIPTIONS -> NetworkBlockedException.Reason.VOICE_API_OFF
            else -> NetworkBlockedException.Reason.TEXT_API_OFF
        })
    }

    fun execute(request: HttpRequest): HttpResponse {
        check(request)
        val response = transport.execute(request)
        if (response.status in 300..399) throw NetworkBlockedException(NetworkBlockedException.Reason.REDIRECT, hostAndPath(request.url), response.status)
        return response
    }

    /** the same checks as [execute], the body arrives while it is read; [cancel] closes the connection at once */
    fun stream(request: HttpRequest, cancel: CancelSignal): HttpStream {
        check(request)
        if (cancel.isCancelled) throw IOException("cancelled")
        val response = transport.stream(request, cancel)
        if (response.status in 300..399) {
            response.close()
            throw NetworkBlockedException(NetworkBlockedException.Reason.REDIRECT, hostAndPath(request.url), response.status)
        }
        return response
    }

    /**
     * ai-dict-1: GET of one file of the AI dictionary from byte [rangeFrom] on. "Allow internet access" must be on; the
     * address must be in the catalogue, or HuggingFace's file storage when [redirected] (the caller follows a redirect
     * only by calling this again, so it is checked again). No cookies, no keys, nothing about the user or the text.
     */
    fun downloadFile(url: String, rangeFrom: Long, redirected: Boolean, cancel: CancelSignal): HttpStream {
        if (!internetAllowed()) throw NetworkBlockedException(NetworkBlockedException.Reason.INTERNET_OFF)
        if (!aiDictUrl(url, redirected)) throw NetworkBlockedException(NetworkBlockedException.Reason.NOT_ALLOWED, hostAndPath(url))
        if (cancel.isCancelled) throw IOException("cancelled")
        val headers = if (rangeFrom > 0) mapOf("Accept-Encoding" to "identity", "Range" to "bytes=$rangeFrom-") else mapOf("Accept-Encoding" to "identity")
        return transport.stream(HttpRequest("GET", url, headers), cancel)
    }

    companion object {
        const val PREF_INTERNET_ALLOWED = "aikb_internet_allowed"

        /** for the AI dictionary: direct (the proxy is for OpenAI), no size limit (the model is ~0.5 GB) */
        fun forAiDict(context: Context): AikbNetwork {
            val app = context.applicationContext
            return AikbNetwork({ internetAllowed(app) }, UrlConnectionTransport(maxBodyBytes = Int.MAX_VALUE), { false })
        }

        /** for messages: host and path, never the query */
        fun hostAndPath(url: String): String = try { URI(url).let { "${it.scheme}://${it.host}${it.rawPath ?: ""}" } } catch (e: Exception) { "invalid address" }

        /** only the personal variant has the INTERNET permission and AI functions */
        fun internetAllowed(context: Context) =
            BuildConfig.AIKB_OPENAI && context.prefs().getBoolean(PREF_INTERNET_ALLOWED, false)

        /** what is switched on now, read again for every request */
        fun permits(context: Context, endpoint: Endpoint): Boolean {
            val prefs = context.prefs()
            return when (endpoint) {
                Endpoint.TRANSCRIPTIONS -> helium314.keyboard.latin.voiceapi.VoiceApiPrefs.modeOn(prefs) && helium314.keyboard.latin.voiceapi.VoiceApi.hasKey(context)
                Endpoint.RESPONSES -> helium314.keyboard.latin.ai.AiPrefs.textConsent(prefs) && helium314.keyboard.latin.voiceapi.VoiceApi.hasKey(context)
                Endpoint.MODELS -> true
            }
        }

        /**
         * The client of the app: the internet switch, the proxy for OpenAI requests and [permits]. [readTimeoutMs] is the
         * longest silence of the server: 90 s for a long recording, 30 s for text.
         */
        fun get(context: Context, readTimeoutMs: Int = 30_000, connectTimeoutMs: Int = 15_000): AikbNetwork {
            val app = context.applicationContext
            return AikbNetwork({ internetAllowed(app) },
                UrlConnectionTransport(proxy = { OpenAiProxy.forRequest(OpenAiProxy.read(app.prefs())) },
                    connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs),
                { permits(app, it) })
        }
    }
}

/** HttpsURLConnection from Android, no extra library */
class UrlConnectionTransport(
    /** optional proxy for OpenAI requests (null: direct), read for every request */
    private val proxy: () -> java.net.Proxy? = { null },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxBodyBytes: Int = 2 * 1024 * 1024,
) : HttpTransport {
    private fun open(request: HttpRequest): Pair<HttpsURLConnection, RecordingSocketFactory> {
        val p = proxy()
        val connection = (if (p == null) URL(request.url).openConnection() else URL(request.url).openConnection(p)) as? HttpsURLConnection
            ?: throw NetworkBlockedException(NetworkBlockedException.Reason.NOT_ALLOWED)
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs // for a stream: the longest silence
        connection.requestMethod = request.method
        request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val sockets = RecordingSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
        connection.sslSocketFactory = sockets // only watches the connection, certificates are checked as before
        return connection to sockets
    }

    private fun send(connection: HttpsURLConnection, request: HttpRequest) {
        request.body?.let { body ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            request.onBodySent?.invoke()
        }
    }

    private fun headersOf(connection: HttpsURLConnection) = connection.headerFields.filterKeys { it != null }
        .mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }

    override fun execute(request: HttpRequest): HttpResponse {
        val (connection, sockets) = open(request)
        try {
            send(connection, request)
            val status = connection.responseCode
            val headers = headersOf(connection)
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readLimited(it) } ?: ByteArray(0)
            return HttpResponse(status, headers, body, sockets.tls(), sockets.route())
        } finally {
            connection.disconnect()
        }
    }

    override fun stream(request: HttpRequest, cancel: CancelSignal): HttpStream {
        val (connection, _) = open(request)
        cancel.setOnCancel { try { connection.disconnect() } catch (_: Exception) { } }
        try {
            send(connection, request)
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream) ?: ByteArrayInputStream(ByteArray(0))
            return HttpStream(status, headersOf(connection), LimitedInputStream(body, maxBodyBytes)) {
                cancel.setOnCancel(null)
                connection.disconnect()
            }
        } catch (e: Exception) {
            cancel.setOnCancel(null)
            connection.disconnect()
            throw e
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBodyBytes) throw IOException("response too large")
        }
        return out.toByteArray()
    }
}

/** a stream that fails instead of reading more than [max] bytes */
private class LimitedInputStream(input: InputStream, private val max: Int) : FilterInputStream(input) {
    private var count = 0L
    private fun add(n: Int): Int { if (n > 0) { count += n; if (count > max) throw IOException("response too large") }; return n }
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
}

/** remembers the TLS socket of the request, for "Check connection with OpenAI" (TLS version, IPv4 / IPv6 / proxy) */
private class RecordingSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    @Volatile private var last: SSLSocket? = null
    /** the plain connection under TLS (to OpenAI, or to the local proxy); read after the request, when it is connected */
    @Volatile private var under: Socket? = null

    private fun keep(s: Socket, plain: Socket?): Socket {
        (s as? SSLSocket)?.let { last = it; under = plain }
        return s
    }

    fun tls(): String? = try { last?.session?.protocol?.takeIf { it != "NONE" } } catch (e: Exception) { null }

    fun route(): String? = when (val p = try { (under ?: last)?.inetAddress } catch (e: Exception) { null }) {
        null -> null
        else -> if (p.isLoopbackAddress) "proxy" else if (p is Inet6Address) "IPv6" else if (p is Inet4Address) "IPv4" else null
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        keep(delegate.createSocket(s, host, port, autoClose), s)
    override fun createSocket(host: String?, port: Int): Socket = keep(delegate.createSocket(host, port), null)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        keep(delegate.createSocket(host, port, localHost, localPort), null)
    override fun createSocket(host: InetAddress?, port: Int): Socket = keep(delegate.createSocket(host, port), null)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        keep(delegate.createSocket(address, port, localAddress, localPort), null)
    override fun createSocket(): Socket = keep(delegate.createSocket(), null)
}

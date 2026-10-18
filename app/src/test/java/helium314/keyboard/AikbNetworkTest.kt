// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.openai.Endpoint
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.HttpResponse
import helium314.keyboard.latin.openai.HttpTransport
import helium314.keyboard.latin.openai.NetworkBlockedException
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.restoreSettings
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The single network client, its allow list of three requests and the "Allow internet access" switch (docs/AI.md). */
@RunWith(RobolectricTestRunner::class)
class AikbNetworkTest {
    private class CountingTransport(private val status: Int = 200) : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse { sent.add(request); return HttpResponse(status, emptyMap(), ByteArray(0)) }
    }

    /** stage 5d: only three exact method and address pairs, all on api.openai.com; nothing of the ChatGPT sign-in */
    @Test fun allowListIsExactlyThreeRequests() {
        assertEquals(setOf("POST https://api.openai.com/v1/audio/transcriptions", "GET https://api.openai.com/v1/models",
            "POST https://api.openai.com/v1/responses"), Endpoint.entries.map { "${it.method} ${it.url}" }.toSet())
        assertEquals(Endpoint.TRANSCRIPTIONS, Endpoint.of("POST", OpenAi.TRANSCRIPTIONS_URL))
        assertEquals(Endpoint.MODELS, Endpoint.of("GET", OpenAi.MODELS_URL))
        assertEquals(Endpoint.RESPONSES, Endpoint.of("POST", OpenAi.RESPONSES_URL))
        listOf(
            "GET" to OpenAi.RESPONSES_URL, "POST" to OpenAi.MODELS_URL, "GET" to OpenAi.TRANSCRIPTIONS_URL, // wrong method
            "GET" to "https://api.openai.com/v1/models/gpt-transcribe", // the old key check is gone
            "POST" to "https://auth.openai.com/api/accounts/oauth/token", "POST" to "https://auth.openai.com/api/accounts/oauth/revoke",
            "GET" to "https://auth.openai.com/.well-known/jwks.json", "GET" to "https://auth.openai.com/api/accounts/authorize",
            "POST" to "http://api.openai.com/v1/responses", // plain text
            "POST" to "https://api.openai.com:8443/v1/responses", // other port
            "POST" to "https://api.openai.com/v1/responses?x=1", "POST" to "https://api.openai.com/v1/responses#x",
            "POST" to "https://user@api.openai.com/v1/responses",
            "POST" to "https://api.openai.com.evil.example/v1/responses",
            "POST" to "https://evil.example/https://api.openai.com/v1/responses",
            "POST" to "https://api.openai.com/v1/chat/completions", "POST" to "https://api.openai.com/v1/files",
            "POST" to "https://chatgpt.com/backend-api/conversation", "GET" to "https://chatgpt.com/settings/usage",
            "GET" to OpenAi.BILLING_URL, // only opened in the browser
            "POST" to "https://api.openai.com/v1/responses/../files", "POST" to "https://API.openai.com/v1/responses",
            "POST" to "ftp://api.openai.com/v1/responses", "GET" to "not a url",
        ).forEach { (m, u) -> assertNull(Endpoint.of(m, u), "$m $u") }
    }

    @Test fun featureOffSendsNothing() {
        val t = CountingTransport()
        val voiceOff = assertFailsWith<NetworkBlockedException> {
            AikbNetwork({ true }, t, { it == Endpoint.MODELS }).execute(HttpRequest("POST", OpenAi.TRANSCRIPTIONS_URL))
        }
        assertEquals(NetworkBlockedException.Reason.VOICE_API_OFF, voiceOff.reason)
        val textOff = assertFailsWith<NetworkBlockedException> {
            AikbNetwork({ true }, t, { it == Endpoint.MODELS }).execute(HttpRequest("POST", OpenAi.RESPONSES_URL))
        }
        assertEquals(NetworkBlockedException.Reason.TEXT_API_OFF, textOff.reason)
        assertTrue(t.sent.isEmpty())
    }

    /** the app's client: ✨ needs the consent for text and a key, voice its own consent, mode and a key */
    @Test fun appClientPermitsByConsentAndKey() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = ctx.prefs()
        prefs.edit { clear() }
        assertTrue(AikbNetwork.permits(ctx, Endpoint.MODELS)) // the connection check needs no key
        assertFalse(AikbNetwork.permits(ctx, Endpoint.RESPONSES))
        assertFalse(AikbNetwork.permits(ctx, Endpoint.TRANSCRIPTIONS))
        prefs.edit { putBoolean(AiPrefs.PREF_TEXT_CONSENT, true) }
        // the consent alone is not enough: no key (the unit tests are built without the key store, like release)
        assertFalse(AikbNetwork.permits(ctx, Endpoint.RESPONSES))
        prefs.edit { clear() }
    }

    @Test fun switchOffSendsNothing() {
        val t = CountingTransport()
        val net = AikbNetwork({ false }, t)
        val e = assertFailsWith<NetworkBlockedException> { net.execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        assertEquals(NetworkBlockedException.Reason.INTERNET_OFF, e.reason)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun notAllowedAddressSendsNothing() {
        val t = CountingTransport()
        val e = assertFailsWith<NetworkBlockedException> { AikbNetwork({ true }, t).execute(HttpRequest("GET", "https://example.com/")) }
        assertEquals(NetworkBlockedException.Reason.NOT_ALLOWED, e.reason)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun allowedAddressWithSwitchOnIsSent() {
        val t = CountingTransport()
        assertEquals(200, AikbNetwork({ true }, t).execute(HttpRequest("GET", OpenAi.MODELS_URL)).status)
        assertEquals(1, t.sent.size)
    }

    @Test fun redirectIsAnError() {
        val e = assertFailsWith<NetworkBlockedException> { AikbNetwork({ true }, CountingTransport(302)).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        assertEquals(NetworkBlockedException.Reason.REDIRECT, e.reason)
    }

    @Test fun switchIsOffByDefaultAndOffWithoutPersonalBuild() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.prefs().edit { clear() }
        assertFalse(AikbNetwork.internetAllowed(ctx))
        ctx.prefs().edit { putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true) }
        // unit tests use the build without AI functions (like the release variant): still no network
        assertEquals(helium314.keyboard.latin.BuildConfig.AIKB_OPENAI, AikbNetwork.internetAllowed(ctx))
        ctx.prefs().edit { clear() }
    }

    @Test fun restoredBackupNeverSwitchesNetworkOrConsentOn() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = ctx.prefs()
        prefs.edit {
            clear()
            putBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, true)
            putBoolean(AiPrefs.PREF_TEXT_CONSENT, true)
            putBoolean("some_other_setting", true)
        }
        val out = ByteArrayOutputStream()
        settingsToJsonStream(prefs.all, out)
        prefs.edit { clear() }
        restoreSettings(out.toString().split("\n"), prefs)
        assertTrue(prefs.getBoolean("some_other_setting", false)) // the backup itself works
        assertFalse(prefs.contains(AikbNetwork.PREF_INTERNET_ALLOWED))
        assertFalse(prefs.contains(AiPrefs.PREF_TEXT_CONSENT))
        prefs.edit { clear() }
    }

    /** every outgoing connection must go through AikbNetwork; nothing listens on a port any more (stage 5d) */
    @Test fun noOtherNetworkCode() {
        val allowed = mapOf(
            "AikbNetwork.kt" to setOf("openConnection", "HttpsURLConnection", "java.net.URL", "java.net.Socket", "java.net.InetAddress", "java.net.Proxy", "java.net.InetSocketAddress"),
            "OpenAiProxy.kt" to setOf("java.net.Proxy", "java.net.InetSocketAddress", "java.net.InetAddress"),
        )
        val patterns = listOf("openConnection", "HttpURLConnection", "HttpsURLConnection", "java.net.URL", "ServerSocket",
            "java.net.Socket", "DatagramSocket", "java.net.InetAddress", "WebView", "okhttp", "DownloadManager", "SocketChannel",
            "java.net.Proxy", "java.net.InetSocketAddress", "ProxySelector")
        val found = File("src/main/java").walk().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .flatMap { f ->
                val text = f.readText()
                patterns.filter { Regex(Regex.escape(it) + "\\b").containsMatchIn(text) }.filter { it !in allowed[f.name].orEmpty() }.map { "${f.path}: $it" }
            }
            .toList()
        assertEquals(emptyList(), found)
    }
}

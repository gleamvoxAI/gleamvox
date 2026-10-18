// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.ConnectionCheck
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.NetworkBlockedException
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.OpenAiProxy
import helium314.keyboard.latin.openai.UrlConnectionTransport
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proxy for OpenAI requests: only allowed OpenAI addresses go through it, only to a proxy on this phone. */
@RunWith(RobolectricTestRunner::class)
class OpenAiProxyTest {
    /** a fake local proxy that records what the client asks for and then refuses */
    private class FakeProxy(private val socks: Boolean) {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val port get() = server.localPort
        init {
            Thread {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: Exception) { break }
                    try { s.use { if (socks) socks5(it) else http(it) } } catch (_: Exception) { }
                }
            }.apply { isDaemon = true }.start()
        }
        private fun http(s: Socket) {
            seen.add(s.getInputStream().bufferedReader().readLine())
            s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        private fun socks5(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val version = input.readUnsignedByte()
            if (version != 5) { seen.add("version $version"); return }
            val n = input.readUnsignedByte(); input.readFully(ByteArray(n))
            out.write(byteArrayOf(5, 0)) // no authentication
            input.readUnsignedByte(); input.readUnsignedByte(); input.readUnsignedByte() // ver, cmd, rsv
            val target = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                else -> ByteArray(16).also { input.readFully(it) }.let { "ipv6" }
            }
            val port = input.readUnsignedShort()
            seen.add("$target:$port")
            out.write(byteArrayOf(5, 2, 0, 1, 0, 0, 0, 0, 0, 0)) // refused by rules
        }
        fun close() = server.close()
    }

    private val proxies = mutableListOf<FakeProxy>()
    private fun proxy(socks: Boolean) = FakeProxy(socks).also { proxies.add(it) }
    @AfterTest fun closeAll() { proxies.forEach { it.close() } }

    private fun network(config: OpenAiProxy.Config, internet: Boolean = true) =
        AikbNetwork({ internet }, UrlConnectionTransport(proxy = { OpenAiProxy.forRequest(config) }, connectTimeoutMs = 3000, readTimeoutMs = 3000))

    private fun waitFor(p: FakeProxy): List<String> {
        val until = System.currentTimeMillis() + 3000
        while (p.seen.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        return p.seen.toList()
    }

    @Test fun httpProxyGetsTheHostName() {
        val p = proxy(socks = false)
        assertFailsWith<java.io.IOException> { network(OpenAiProxy.Config(OpenAiProxy.Type.HTTP, "127.0.0.1", p.port)).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        // DNS on the proxy side: the client asks for the name, not an address
        assertEquals("CONNECT api.openai.com:443 HTTP/1.1", waitFor(p).first())
    }

    @Test fun socksProxyIsUsed() {
        val p = proxy(socks = true)
        assertFailsWith<java.io.IOException> { network(OpenAiProxy.Config(OpenAiProxy.Type.SOCKS5, "127.0.0.1", p.port)).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        val seen = waitFor(p).first()
        assertTrue(seen.endsWith(":443"), seen)
        // the JVM of the tests may resolve the name itself; Android's client passes it unresolved (checked on the phone)
        assertTrue(seen == "api.openai.com:443" || seen.matches(Regex("""\d+\.\d+\.\d+\.\d+:443|ipv6:443""")), seen)
    }

    @Test fun notAllowedAddressesNeverReachTheProxy() {
        val p = proxy(socks = false)
        val net = network(OpenAiProxy.Config(OpenAiProxy.Type.HTTP, "127.0.0.1", p.port))
        for (url in listOf("https://example.com/", "https://api.openai.com/v1/files", "http://api.openai.com/v1/models", "https://auth.openai.com/api/accounts/oauth/token")) {
            val e = assertFailsWith<NetworkBlockedException> { net.execute(HttpRequest("GET", url)) }
            assertEquals(NetworkBlockedException.Reason.NOT_ALLOWED, e.reason)
        }
        val off = assertFailsWith<NetworkBlockedException> { network(OpenAiProxy.Config(OpenAiProxy.Type.HTTP, "127.0.0.1", p.port), internet = false).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        assertEquals(NetworkBlockedException.Reason.INTERNET_OFF, off.reason)
        Thread.sleep(200)
        assertTrue(p.seen.isEmpty())
    }

    @Test fun onlyAProxyOnThisPhone() {
        for (host in listOf("127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]")) assertTrue(OpenAiProxy.loopbackAddress(host)!!.isLoopbackAddress, host)
        for (host in listOf("10.0.0.1", "192.168.1.1", "8.8.8.8", "proxy.example.com", "127.0.0.256", "0127.0.0.1", "", "::2"))
            assertNull(OpenAiProxy.loopbackAddress(host), host)
        assertEquals(OpenAiProxy.Problem.HOST_NOT_LOCAL, OpenAiProxy.check(OpenAiProxy.Type.SOCKS5, "192.168.1.1", 1080))
        assertEquals(OpenAiProxy.Problem.BAD_PORT, OpenAiProxy.check(OpenAiProxy.Type.HTTP, "127.0.0.1", 0))
        assertEquals(null, OpenAiProxy.check(OpenAiProxy.Type.NONE, "x", null))
        // a stored remote proxy is never used, and the request does not silently go direct
        val e = assertFailsWith<NetworkBlockedException> { network(OpenAiProxy.Config(OpenAiProxy.Type.HTTP, "8.8.8.8", 8080)).execute(HttpRequest("GET", OpenAi.MODELS_URL)) }
        assertEquals(NetworkBlockedException.Reason.BAD_PROXY, e.reason)
    }

    @Test fun offByDefaultAndSaved() {
        val prefs = ApplicationProvider.getApplicationContext<android.app.Application>().prefs()
        prefs.edit { clear() }
        val c = OpenAiProxy.read(prefs)
        assertEquals(OpenAiProxy.Type.NONE, c.type)
        assertNull(OpenAiProxy.forRequest(c)) // direct
        assertEquals(OpenAiProxy.Problem.HOST_NOT_LOCAL, OpenAiProxy.save(prefs, OpenAiProxy.Type.SOCKS5, "1.2.3.4", 1080))
        assertEquals(OpenAiProxy.Type.NONE, OpenAiProxy.read(prefs).type) // nothing saved
        assertNull(OpenAiProxy.save(prefs, OpenAiProxy.Type.SOCKS5, " 127.0.0.1 ", 10808))
        assertEquals("socks5 127.0.0.1:10808", OpenAiProxy.read(prefs).toString())
        prefs.edit { clear() }
    }

    @Test fun connectionCheckUsesTheProxy() {
        val p = proxy(socks = false)
        val r = ConnectionCheck.run(network(OpenAiProxy.Config(OpenAiProxy.Type.HTTP, "127.0.0.1", p.port)))
        assertFalse(r.ok)
        assertEquals("CONNECT api.openai.com:443 HTTP/1.1", waitFor(p).first())
        assertTrue(r.diagnostic!!.cause.isNotEmpty())
    }
}

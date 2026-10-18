// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.SharedPreferences
import androidx.core.content.edit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Optional "Proxy for OpenAI requests" (off by default, docs/DESIGN.md stage 3f addition): only the requests of
 * [AikbNetwork] to the OpenAI allow list go through it, voice and everything else are not affected. Only a proxy on
 * this phone (127.0.0.1, ::1, localhost) is accepted, so plain text never leaves the phone; the proxy gets the host
 * name of OpenAI and resolves it itself (HTTP: CONNECT host:443; SOCKS5: Android's client passes the name unresolved).
 */
object OpenAiProxy {
    const val PREF_TYPE = "aikb_openai_proxy_type"
    const val PREF_HOST = "aikb_openai_proxy_host"
    const val PREF_PORT = "aikb_openai_proxy_port"
    const val DEFAULT_HOST = "127.0.0.1"

    enum class Type(val id: String) { NONE("none"), SOCKS5("socks5"), HTTP("http") }

    class Config(val type: Type, val host: String, val port: Int) {
        override fun toString() = if (type == Type.NONE) "none" else "${type.id} $host:$port"
    }

    enum class Problem { HOST_NOT_LOCAL, BAD_PORT }

    fun read(prefs: SharedPreferences): Config {
        val type = Type.entries.firstOrNull { it.id == prefs.getString(PREF_TYPE, null) } ?: Type.NONE
        return Config(type, prefs.getString(PREF_HOST, DEFAULT_HOST) ?: DEFAULT_HOST, prefs.getInt(PREF_PORT, 0))
    }

    fun check(type: Type, host: String, port: Int?): Problem? {
        if (type == Type.NONE) return null
        if (loopbackAddress(host) == null) return Problem.HOST_NOT_LOCAL
        if (port == null || port !in 1..65535) return Problem.BAD_PORT
        return null
    }

    /** @return the problem, nothing saved if there is one */
    fun save(prefs: SharedPreferences, type: Type, host: String, port: Int?): Problem? {
        check(type, host.trim(), port)?.let { return it }
        prefs.edit {
            putString(PREF_TYPE, type.id)
            putString(PREF_HOST, host.trim().ifEmpty { DEFAULT_HOST })
            putInt(PREF_PORT, port ?: 0)
        }
        return null
    }

    /** the proxy for one request; null = direct. A configured but unusable proxy stops the request instead of going direct. */
    fun forRequest(config: Config): Proxy? {
        if (config.type == Type.NONE) return null
        val address = loopbackAddress(config.host)
        if (address == null || config.port !in 1..65535)
            throw NetworkBlockedException(NetworkBlockedException.Reason.BAD_PROXY, config.toString())
        val socket = InetSocketAddress(address, config.port)
        return Proxy(if (config.type == Type.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP, socket)
    }

    /** only addresses on this phone, without any DNS lookup */
    fun loopbackAddress(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost") return InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        if (h == "::1") return InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1))
        val parts = h.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 && it == n.toString() } ?: return null }
        if (bytes[0] != 127) return null
        return InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
    }
}

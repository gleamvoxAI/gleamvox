// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import java.io.IOException

/**
 * "Check connection with OpenAI": GET /v1/models through [AikbNetwork] WITHOUT the key (nothing secret is sent, nothing
 * is paid). Any answer of the API (200, or 401 "no key") shows that this app reaches api.openai.com; the time, TLS
 * version and route (IPv4, IPv6, proxy) are reported, or the step and cause of the failure.
 */
object ConnectionCheck {
    class Result(val ok: Boolean, val elapsedMs: Long, val tls: String?, val route: String?, val diagnostic: NetDiagnostic?) {
        /** "OK, 230 ms, TLSv1.3, IPv4" */
        fun summary(): String = if (ok) listOfNotNull("OK", "$elapsedMs ms", tls, route).joinToString(", ")
            else diagnostic?.short() ?: "failed"
    }

    /** the last check of this app run: (ok, time); only in memory */
    @Volatile
    var last: Pair<Boolean, Long>? = null
        private set

    fun remember(r: Result, at: Long = System.currentTimeMillis()) { last = r.ok to at }

    /** answers that only the OpenAI API gives without a key: it was reached */
    private val REACHED = setOf(200, 401)

    fun run(network: AikbNetwork, now: () -> Long = System::currentTimeMillis): Result {
        val start = now()
        return try {
            val r = network.execute(HttpRequest("GET", OpenAi.MODELS_URL, mapOf("Accept" to "application/json")))
            val ms = now() - start
            if (r.status in REACHED) Result(true, ms, r.tls, r.route, null)
            else Result(false, ms, r.tls, r.route, NetDiagnostic(NetStep.CHECK, "server answered with an error", r.status, null, ms))
        } catch (e: IOException) {
            val ms = now() - start
            Result(false, ms, null, null, NetDiagnostic.of(NetStep.CHECK, e, ms))
        } catch (e: Exception) {
            val ms = now() - start
            Result(false, ms, null, null, NetDiagnostic(NetStep.CHECK, NetDiagnostic.exceptionName(e), null, null, ms))
        }
    }
}

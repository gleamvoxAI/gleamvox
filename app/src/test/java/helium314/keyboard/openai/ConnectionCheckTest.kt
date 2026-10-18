// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.openai

import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.ConnectionCheck
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.HttpResponse
import helium314.keyboard.latin.openai.HttpTransport
import helium314.keyboard.latin.openai.NetStep
import helium314.keyboard.latin.openai.OpenAi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Check connection with OpenAI": GET /v1/models without the key; any API answer (200 or 401) means it was reached. */
@RunWith(RobolectricTestRunner::class)
class ConnectionCheckTest {
    private val requests = mutableListOf<HttpRequest>()
    private var clock = 1000L

    private fun answering(status: Int) = HttpTransport { r ->
        requests.add(r); clock += 230
        HttpResponse(status, emptyMap(), """{"error":{"code":"missing_api_key"}}""".toByteArray(), "TLSv1.3", "IPv4")
    }

    @Test fun okWithoutKey() {
        val r = ConnectionCheck.run(AikbNetwork({ true }, answering(401))) { clock }
        assertTrue(r.ok)
        assertEquals("OK, 230 ms, TLSv1.3, IPv4", r.summary())
        assertEquals(listOf("GET " + OpenAi.MODELS_URL), requests.map { "${it.method} ${it.url}" }) // nothing else
        assertTrue(requests.single().headers.keys.none { it.equals("Authorization", true) }, "the key is never sent here")
        assertTrue(ConnectionCheck.run(AikbNetwork({ true }, answering(200))) { clock }.ok)
    }

    @Test fun failures() {
        val r = ConnectionCheck.run(AikbNetwork({ true }, HttpTransport { throw java.net.UnknownHostException("api.openai.com") }))
        assertFalse(r.ok)
        assertEquals(NetStep.CHECK, r.diagnostic!!.step)
        assertEquals("UnknownHostException", r.diagnostic!!.cause)

        val off = ConnectionCheck.run(AikbNetwork({ false }, answering(200)))
        assertEquals("internet access switched off", off.diagnostic!!.cause)

        val err = ConnectionCheck.run(AikbNetwork({ true }, answering(503)))
        assertFalse(err.ok)
        assertEquals(503, err.diagnostic!!.httpStatus)
    }
}

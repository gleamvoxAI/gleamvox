// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ailm

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ailm.AiDictDownloader
import helium314.keyboard.latin.ailm.AiDictFile
import helium314.keyboard.latin.ailm.AiDictModel
import helium314.keyboard.latin.ailm.AiDictModels
import helium314.keyboard.latin.ailm.BpeTokenizer
import helium314.keyboard.latin.ailm.LmRanker
import helium314.keyboard.latin.ailm.TypoCost
import helium314.keyboard.latin.ailm.WordModel
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.HttpRequest
import helium314.keyboard.latin.openai.HttpResponse
import helium314.keyboard.latin.openai.HttpStream
import helium314.keyboard.latin.openai.HttpTransport
import helium314.keyboard.latin.openai.NetworkBlockedException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ai-dict-1: the parts of the AI dictionary that work without a model (the model itself: LocalLmEvalTest) */
@RunWith(RobolectricTestRunner::class)
class AiDictTest {

    @Test fun typoCostKnowsTheKeyboard() {
        assertEquals(0.0, TypoCost.cost("Привет", "привет"))
        assertTrue(TypoCost.neighbours('а', 'м'))      // ЙЦУКЕН: а is above м
        assertFalse(TypoCost.neighbours('а', 'з'))
        assertTrue(TypoCost.cost("ghbdtn", "hello") > 2) // not a typo
        assertTrue(TypoCost.cost("Ппивет", "Привет") < TypoCost.cost("Ппивет", "Пакет"))
        assertTrue(TypoCost.cost("teh", "the") < 1.0)  // swapped letters
        // a completion costs less per letter than a typo
        assertTrue(TypoCost.cost("Прив", "Привет") < TypoCost.cost("Приа", "Приза") * 2)
    }

    @Test fun `the model does not correct to a far word (Gle to For, ux-2)`() {
        // "For" is three neighbour keys away from "Gle": the model is surer of it, but it is not a typo correction
        val r = LmRanker(Fake(mapOf("Gle" to -30.0, "For" to -2.0, "God" to -4.0))).rank("", "Gle", listOf("For", "God"), false)
        assertEquals(null, r.autocorrectTo)
        // a real typo is still corrected
        val t = LmRanker(Fake(mapOf("teh" to -25.0, "the" to -1.5))).rank("", "teh", listOf("the"), false)
        assertEquals("the", t.autocorrectTo)
    }

    @Test fun typoCostOfOtherLanguages() {
        // a letter typed without its accent (no long press) costs little
        assertEquals(TypoCost.ACCENT, TypoCost.cost("tres", "très"), 1e-9)
        assertEquals(2 * TypoCost.ACCENT, TypoCost.cost("Czesc", "Cześć"), 1e-9)
        assertEquals(TypoCost.ACCENT, TypoCost.cost("degil", "değil"), 1e-9)
        assertEquals(TypoCost.ACCENT, TypoCost.cost("bialy", "biały"), 1e-9)
        // й is its own key, not и with a sign
        assertEquals(1.0, TypoCost.cost("мой", "мои"), 1e-9)
        // Ukrainian і sits on the key of ы: в is its neighbour
        assertTrue(TypoCost.neighbours('і', 'в'))
        // Arabic keyboard: ي and ب are neighbours
        assertTrue(TypoCost.neighbours('ي', 'ب'))
    }

    /** a fake model: fixed log-probabilities per word */
    private class Fake(val lp: Map<String, Double>) : WordModel {
        override fun wordLogProbs(context: String, words: List<String>) = DoubleArray(words.size) { lp[words[it]] ?: -30.0 }
        override fun nextWords(context: String, count: Int) = lp.entries.sortedByDescending { it.value }.take(count).map { it.key to it.value }
    }

    @Test fun ownersCaseNoAutocorrectionToAnAbbreviation() {
        // 4408: "Приа" -> "Прим" (an abbreviation). If the model finds no candidate clearly better, the typed word stays.
        val r = LmRanker(Fake(mapOf("Приа" to -19.0, "Прим" to -18.5, "Приза" to -21.0))).rank("", "Приа", listOf("Прим", "Приза"), false)
        assertNull(r.autocorrectTo)
    }

    @Test fun clearTypoIsCorrected() {
        val r = LmRanker(Fake(mapOf("длеа" to -25.0, "дела" to -3.0, "дело" to -9.0))).rank("Привет, как", "длеа", listOf("дела", "дело"), false)
        assertEquals("дела", r.autocorrectTo)
        assertEquals("дела", r.ranked.first().word)
    }

    @Test fun aValidWordNeedsMuchMore() {
        // "прим" typed on purpose is a word: a slightly better candidate does not replace it
        val r = LmRanker(Fake(mapOf("прим" to -9.0, "при" to -7.5))).rank("Он", "прим", listOf("при"), true)
        assertNull(r.autocorrectTo)
    }

    @Test fun tokenizerReadsTokenizerJson() {
        // a tiny byte-level BPE in the format of tokenizer.json ("Ġ" = space)
        val json = """{"version":"1.0","truncation":null,"padding":null,
            "added_tokens":[{"id":7,"content":"<|endoftext|>","special":true}],
            "normalizer":null,"pre_tokenizer":{"type":"ByteLevel","add_prefix_space":false},
            "model":{"type":"BPE","dropout":null,"vocab":{"h":0,"i":1,"Ġ":2,"hi":3,"Ġhi":4,"x":5,"â":6},
            "merges":["h i","Ġ hi"]}}"""
        val tok = BpeTokenizer.read(json.reader())
        assertEquals(7, tok.endOfText)
        assertContentEquals(intArrayOf(3, 4), tok.encode("hi hi"))
        assertEquals(" hi", String(tok.tokenBytes[4]))
        // merges as arrays (newer tokenizer.json)
        val json2 = json.replace("\"merges\":[\"h i\",\"Ġ hi\"]", "\"merges\":[[\"h\",\"i\"],[\"Ġ\",\"hi\"]]")
        assertContentEquals(intArrayOf(3, 4), BpeTokenizer.read(json2.reader()).encode("hi hi"))
    }

    @Test fun catalogueIsPinnedAndChecked() {
        for (m in AiDictModels.all) for (f in m.files) {
            assertTrue(f.url.startsWith("https://huggingface.co/"), f.url)
            assertFalse("/resolve/main/" in f.url, "fixed revision, never main: ${f.url}")
            assertEquals(64, f.sha256.length)
        }
        assertTrue(AiDictModels.isStorageRedirect("https://cas-bridge.xethub.hf.co/xet-bridge-us/abc?X-Amz=1"))
        assertTrue(AiDictModels.isStorageRedirect("https://us.aws.cdn.hf.co/x?y=1"))
        assertFalse(AiDictModels.isStorageRedirect("http://us.aws.cdn.hf.co/x"))
        assertFalse(AiDictModels.isStorageRedirect("https://evil.example/hf.co"))
        assertFalse(AiDictModels.isStorageRedirect("https://hf.co.evil.example/x"))
    }

    // ---- the download, with a fake server ----
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** a fake server behind the real checks of AikbNetwork */
    private class FakeServer(val answer: (HttpRequest, Int) -> HttpStream) : HttpTransport {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse = error("the download streams")
        override fun stream(request: HttpRequest, cancel: CancelSignal): HttpStream { requests.add(request); return answer(request, requests.size) }
    }

    /** a body that breaks (connection lost) after [breakAfter] bytes */
    private fun body(b: ByteArray, breakAfter: Int = Int.MAX_VALUE): InputStream = object : InputStream() {
        val inner = ByteArrayInputStream(b); var n = 0
        override fun read(): Int { if (n++ >= breakAfter) throw IOException("connection lost"); return inner.read() }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (n >= breakAfter) throw IOException("connection lost")
            val r = inner.read(buf, off, minOf(len, breakAfter - n)); if (r > 0) n += r; return r
        }
    }

    private fun model(sha256: String = sha(payload)) = AiDictModel("test", "Test", "MIT", "test",
        listOf(AiDictFile("model.bin", "https://huggingface.co/x/resolve/abc/model.bin", payload.size.toLong(), sha256)))

    /** the test model's address counts as the catalogue; the storage redirect is checked as in the app */
    private fun net(server: HttpTransport, m: AiDictModel, internet: Boolean = true) = AikbNetwork({ internet }, server, { false },
        { u, redirected -> if (redirected) AiDictModels.isStorageRedirect(u) else u == m.files[0].url })

    @Test fun downloadResumesAfterABreakAndChecksTheSum() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val m = model()
        AiDictDownloader.delete(ctx, m)
        var storageCalls = 0
        val server = FakeServer { r, _ ->
            if (r.url.startsWith("https://huggingface.co/")) HttpStream(302, mapOf("location" to "https://cas-bridge.xethub.hf.co/f?sig=1"), body(ByteArray(0)))
            else if (++storageCalls == 1) HttpStream(200, emptyMap(), body(payload, breakAfter = 100_000)) // first try: the connection breaks
            else {
                assertEquals("bytes=100000-", r.headers["Range"])
                HttpStream(206, emptyMap(), body(payload.copyOfRange(100_000, payload.size)))
            }
        }
        val d = AiDictDownloader(ctx, net(server, m))
        assertFailsWith<IOException> { d.download(m) { } }
        assertEquals(100_000L, File(m.dir(ctx), "model.bin.part").length())
        var lastProgress = 0L
        d.download(m) { lastProgress = it.done }
        assertTrue(m.isComplete(ctx))
        assertContentEquals(payload, File(m.dir(ctx), "model.bin").readBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        // nothing about the user goes out: no cookies, no keys, only these two headers
        for (r in server.requests) { assertEquals("GET", r.method); assertTrue(r.headers.keys.all { it == "Range" || it == "Accept-Encoding" }, r.headers.toString()) }
    }

    @Test fun wrongChecksumIsDeleted() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val m = model(sha256 = "0".repeat(64))
        AiDictDownloader.delete(ctx, m)
        val d = AiDictDownloader(ctx, net(FakeServer { _, _ -> HttpStream(200, emptyMap(), body(payload)) }, m))
        assertFailsWith<IOException> { d.download(m) { } }
        assertFalse(File(m.dir(ctx), "model.bin").exists())
        assertFalse(File(m.dir(ctx), "model.bin.part").exists())
        assertFalse(m.isComplete(ctx))
    }

    @Test fun onlyAllowedAddressesAndOnlyWithInternetOn() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val m = model()
        AiDictDownloader.delete(ctx, m)
        val none = FakeServer { _, _ -> error("no request") }
        // "Allow internet access" is off
        assertFailsWith<NetworkBlockedException> { AiDictDownloader(ctx, net(none, m, internet = false)).download(m) { } }
        // not in the catalogue (the real catalogue of the app)
        assertFailsWith<NetworkBlockedException> { AiDictDownloader(ctx, AikbNetwork({ true }, none, { false })).download(m) { } }
        // a redirect to a foreign host is not followed
        val evil = FakeServer { _, _ -> HttpStream(302, mapOf("location" to "https://evil.example/model.bin"), body(ByteArray(0))) }
        assertFailsWith<NetworkBlockedException> { AiDictDownloader(ctx, net(evil, m)).download(m) { } }
        assertEquals(1, evil.requests.size)
        assertTrue(none.requests.isEmpty())
    }
}

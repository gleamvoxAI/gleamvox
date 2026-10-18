// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.openai.ConnectionCheck
import helium314.keyboard.latin.openai.NetDiagnostic
import helium314.keyboard.latin.openai.NetStep
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.settings.screens.ConnectionCheckBanner
import helium314.keyboard.settings.screens.KeyCheckBanner
import helium314.keyboard.settings.screens.ResultBannerColors
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5b: the results of "Check key" and "Check connection with OpenAI" as green or red banners that catch the eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultBannerTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun inLanguage(tag: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    /** draws [content] in the app theme ([dark] or light), saves it in build/shots and returns the picture */
    private fun draw(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            helium314.keyboard.latin.utils.Theme(dark) {
                // like on the settings screen: the theme background, below the title bar of the test window
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp)) { content() }
                }
            }
        }
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 800)
        val bmp = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/shots").mkdirs()
        File("build/shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun count(bmp: Bitmap, color: Long): Int {
        var n = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (bmp.getPixel(x, y) == color.toInt()) n++
        return n
    }

    @Test fun palettesAreReadableInLightAndDark() {
        for (success in listOf(true, false)) for (dark in listOf(true, false)) {
            val p = ResultBannerColors.of(success, dark)
            val ratio = ResultBannerColors.contrast(p.container, p.content)
            assertTrue(ratio >= 4.5, "success=$success dark=$dark contrast $ratio")
        }
        assertEquals(0xFF2E7D32, ResultBannerColors.of(true, false).container, "the green of 'All connected'")
        assertTrue(ResultBannerColors.of(false, false).container != ResultBannerColors.of(true, false).container)
    }

    @Test fun keyWorksIsGreen() {
        for (dark in listOf(false, true)) {
            val bmp = draw("banner_key_ok_${if (dark) "dark" else "light"}", dark) { KeyCheckBanner(VoiceApi.KeyCheck(TranscriptionResult.Ok("", null), 230, voiceModel = true, textModels = 5)) }
            val green = ResultBannerColors.of(true, dark).container
            assertTrue(count(bmp, green) > 2000, "a large green banner (dark=$dark)")
            assertEquals(0, count(bmp, ResultBannerColors.of(false, dark).container), "no red")
        }
    }

    @Test fun eachKeyErrorIsRed() {
        for (p in listOf(VoiceApiProblem.KEY_INVALID, VoiceApiProblem.NO_BALANCE, VoiceApiProblem.RATE_LIMIT, VoiceApiProblem.NETWORK,
                VoiceApiProblem.MODEL_NOT_AVAILABLE)) {
            for (dark in listOf(false, true)) {
                val bmp = draw("banner_key_${p.name.lowercase()}_${if (dark) "dark" else "light"}", dark) {
                    KeyCheckBanner(VoiceApi.KeyCheck(TranscriptionResult.Failed(p, 401, "invalid_api_key", "invalid_request_error"), 180, false, 0))
                }
                assertTrue(count(bmp, ResultBannerColors.of(false, dark).container) > 2000, "$p red banner (dark=$dark)")
                assertEquals(0, count(bmp, ResultBannerColors.of(true, dark).container), "$p no green")
            }
        }
    }

    @Test fun keyErrorTitlesInPlainWords() {
        val ru = inLanguage("ru")
        assertEquals("Неверный ключ", VoiceApi.keyCheckTitle(ru, VoiceApiProblem.KEY_INVALID))
        assertEquals("Нет денег на балансе OpenAI", VoiceApi.keyCheckTitle(ru, VoiceApiProblem.NO_BALANCE))
        assertEquals("Лимит запросов OpenAI, повторите через минуту", VoiceApi.keyCheckTitle(ru, VoiceApiProblem.RATE_LIMIT))
        assertEquals("Нет связи с OpenAI", VoiceApi.keyCheckTitle(ru, VoiceApiProblem.NETWORK))
        assertEquals("Ключ без доступа к модели gpt-transcribe", VoiceApi.keyCheckTitle(ru, VoiceApiProblem.MODEL_NOT_AVAILABLE))
        val en = inLanguage("en")
        assertEquals("Wrong key", VoiceApi.keyCheckTitle(en, VoiceApiProblem.KEY_INVALID))
        assertEquals("No connection to OpenAI", VoiceApi.keyCheckTitle(en, VoiceApiProblem.NETWORK))
        assertEquals("Ключ работает", ru.getString(helium314.keyboard.latin.R.string.voice_api_check_ok_title))
        assertEquals("Голос: модель gpt-transcribe доступна · текстовых моделей: 7 · ответ за 230 мс. Баланс здесь не проверяется.",
            ru.getString(helium314.keyboard.latin.R.string.voice_api_check_ok_detail, 7, 230))
    }

    @Test fun copiedDetailsHaveNoSecrets() {
        val text = VoiceApi.keyCheckDetails(app, TranscriptionResult.Failed(VoiceApiProblem.KEY_INVALID, 401, "invalid_api_key", "invalid_request_error"), 180)
        assertTrue(text.contains("HTTP 401") && text.contains("invalid_api_key") && text.contains("180 ms"), text)
        assertFalse(text.contains("sk-") || text.contains("••••"), text)
    }

    @Test fun connectionCheckBanners() {
        for (dark in listOf(false, true)) {
            val ok = draw("banner_check_ok_${if (dark) "dark" else "light"}", dark) {
                ConnectionCheckBanner(ConnectionCheck.Result(true, 173, "TLSv1.3", "IPv4", null))
            }
            assertTrue(count(ok, ResultBannerColors.of(true, dark).container) > 2000, "green OK (dark=$dark)")
            val failed = draw("banner_check_failed_${if (dark) "dark" else "light"}", dark) {
                ConnectionCheckBanner(ConnectionCheck.Result(false, 15000, null, null,
                    NetDiagnostic(NetStep.CHECK, "SocketTimeoutException", null, null, 15000)))
            }
            assertTrue(count(failed, ResultBannerColors.of(false, dark).container) > 2000, "red failure (dark=$dark)")
        }
        assertEquals("OK, 173 мс", inLanguage("ru").getString(helium314.keyboard.latin.R.string.openai_check_ok_title, 173))
    }
}

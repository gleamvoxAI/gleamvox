// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** play-3: the name of another project is not in the texts of the user, except the credit and the licence. */
class BrandNamesTest {
    private val res = File("src/main/res")
    private val playRes = File("src/play/res")
    private val stringRe = Regex("""<string name="([A-Za-z_0-9]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    // the credit of the upstream project (About), the dictionaries it built, the research project's credit line (a hidden screen)
    private val creditNames = setOf("app_slogan", "about_dictionaries_text", "gesture_data_description_privacy", "gesture_data_promotion_message")

    private fun stringsOf(dir: File) = dir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        .flatMap { d -> d.listFiles { f -> f.extension == "xml" }.orEmpty().map { d.name to it } }
        .flatMap { (d, f) -> stringRe.findAll(f.readText()).map { Triple(d, it.groupValues[1], it.groupValues[2]) } }

    @Test fun upstreamNameOnlyInCredits() {
        val bad = stringsOf(res).filter { (_, name, text) -> text.contains("Heliboard", ignoreCase = true) && name !in creditNames }
        assertEquals(emptyList(), bad.map { "${it.first}/${it.second}" })
    }

    @Test fun playOverlayGivesGleamvoxToTheSettingsTitleInEveryLanguageWithTheName() {
        val title = stringsOf(playRes).filter { it.second == "ime_settings" }
        assertTrue(title.size > 40, "overlays: ${title.size}")
        title.forEach { assertTrue(it.third.contains("Gleamvox"), "${it.first}: ${it.third}") }
        assertEquals("Gleamvox Settings", title.first { it.first == "values" }.third.trim())
        assertEquals("Настройки Gleamvox", title.first { it.first == "values-ru" }.third.trim())
    }

    @Test fun playHasNoPersonalBrandInItsOverlay() {
        assertTrue(stringsOf(playRes).none { it.third.contains("AI Keyboard") })
    }

    @Test fun aboutKeepsTheCredit() {
        val slogan = stringsOf(res).first { it.first == "values" && it.second == "app_slogan" }.third
        assertTrue(slogan.contains("Based on HeliBoard (GPL-3.0) by Helium314 and contributors"))
        assertTrue(File("../LICENSE").isFile && File("../NOTICE.md").readText().contains("HeliBoard"))
    }
}

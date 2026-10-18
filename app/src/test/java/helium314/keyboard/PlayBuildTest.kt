// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * play-1: the store version "Gleamvox" (build type "play"): its own package and name, not debuggable, signed with the
 * upload key from outside the repository, no photo access, no gesture library from a file, nothing of personal (calls,
 * OpenAI, the network). What the built AAB holds is checked by tools/aikb/build.sh play (aapt2), see STATUS.md.
 */
class PlayBuildTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"
    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))
    private fun Document.elements(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private val gradle = File("build.gradle.kts").readText()
    private fun block(start: String): String { val i = gradle.indexOf(start); assertTrue(i >= 0, start); return gradle.substring(i, gradle.indexOf("\n        }", i)) }

    @Test fun ownPackageNameAndVersion() {
        assertTrue("variant.applicationId.set(\"com.gleamvox.keyboard\")" in gradle)
        val name = parse("src/play/res/values/donottranslate.xml").elements("string").single { it.getAttribute("name") == "english_ime_name" }
        assertEquals("Gleamvox", name.textContent)
        assertTrue("versionName.set(\"1.0.\${4201 + gitCommitCount}\")" in gradle, "versionCode grows with every commit, as for the other variants")
    }

    @Test fun notDebuggableNoScreenshotNoUserLibrary() {
        val play = block("create(\"play\")")
        assertTrue("isDebuggable = false" in play && "isJniDebuggable = false" in play, play)
        assertTrue("buildConfigField(\"boolean\", \"AIKB_SCREENSHOT\", \"false\")" in play, play)
        assertTrue("buildConfigField(\"boolean\", \"USER_GESTURE_LIB\", \"false\")" in play, play)
        assertFalse("AIKB_OPENAI\", \"true\"" in play, "no OpenAI in the store version")
        // the debug unit tests run with the defaults: the feature is there, a library may be loaded
        assertTrue(helium314.keyboard.latin.BuildConfig.AIKB_SCREENSHOT)
        assertTrue(helium314.keyboard.latin.BuildConfig.USER_GESTURE_LIB)
    }

    @Test fun photoAccessRemovedInThePlayManifest() {
        val m = parse("src/play/AndroidManifest.xml")
        val removed = m.elements("uses-permission").filter { it.getAttributeNS(toolsNs, "node") == "remove" }.map { it.getAttributeNS(androidNs, "name") }
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), removed)
        assertEquals(listOf("helium314.keyboard.latin.ScreenshotProvider"), m.elements("provider").map { it.getAttributeNS(androidNs, "name") })
        assertEquals(listOf("helium314.keyboard.latin.ScreenshotPermissionActivity"), m.elements("activity").map { it.getAttributeNS(androidNs, "name") })
        assertTrue((m.elements("provider") + m.elements("activity")).all { it.getAttributeNS(toolsNs, "node") == "remove" })
        assertTrue(m.elements("service").isEmpty() && m.elements("uses-permission").none { it.getAttributeNS(toolsNs, "node") != "remove" },
            "the store version adds nothing")
    }

    @Test fun signedWithTheUploadKeyFromOutsideTheRepository() {
        assertTrue("\${System.getProperty(\"user.home\")}/.local/ai-keyboard-keys" in gradle)
        assertTrue("upload-signing.properties" in gradle)
        assertTrue("aikbPlay and aikbPersonal together" in gradle, "never with the personal parts")
        assertFalse(File("upload.p12").exists() || File("../upload.p12").exists(), "no key in the repository")
        val script = File("../tools/aikb/make-upload-key.sh").readText()
        assertTrue("umask 077" in script && "chmod 600" in script && "--cipher-algo AES256" in script)
        assertFalse("cat \"\$K/backup-passphrase.txt\"" in script, "the passphrase is never printed")
    }

    @Test fun playGetsTheSameCheckedDictionariesAsPersonal() {
        // play-2: one dictionary block for both, with the SHA-256 check; no personal-only flag for the dictionaries
        assertTrue("if (aikbPersonal || aikbPlay) {" in gradle)
        assertTrue("sourceSets[\"play\"].assets.srcDir(dictsOut)" in gradle)
        assertTrue("wrong SHA-256 of" in gradle)
    }

    @Test fun personalPartsStayInPersonal() {
        // the calls, the network and ONNX Runtime come only with -PaikbPersonal=true, into the debugNoMinify variant
        assertTrue("sourceSets[\"debugNoMinify\"].manifest.srcFile(\"src/aikbPersonal/AndroidManifest.xml\")" in gradle)
        assertFalse(File("src/play/java").exists())
        val main = parse("src/main/AndroidManifest.xml").elements("uses-permission").map { it.getAttributeNS(androidNs, "name") }
        for (p in listOf("android.permission.INTERNET", "android.permission.MANAGE_OWN_CALLS", "android.permission.ACCESS_NETWORK_STATE"))
            assertFalse(p in main, p)
        assertTrue(parse("src/main/AndroidManifest.xml").elements("service").none { it.getAttributeNS(androidNs, "name").endsWith("AikbCallService") })
    }
}

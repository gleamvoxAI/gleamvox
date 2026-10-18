// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Backup off, no plain-text traffic, INTERNET only in the personal variant (docs/CHATGPT.md, sections 2 and 5). */
class ManifestBackupTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))
    private fun Document.elements(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test fun backupIsOff() {
        val app = parse("src/main/AndroidManifest.xml").elements("application").single()
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("false", app.getAttributeNS(androidNs, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("@xml/network_security_config", app.getAttributeNS(androidNs, "networkSecurityConfig"))
    }

    @Test fun dataExtractionExcludesEverything() {
        val doc = parse("src/main/res/xml/data_extraction_rules.xml")
        val domains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = doc.elements(section).single()
            val excluded = s.getElementsByTagName("exclude").let { l -> (0 until l.length).map { l.item(it) as Element } }
            assertEquals(domains.toSet(), excluded.filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet(), section)
            assertEquals(0, s.getElementsByTagName("include").length, section)
        }
    }

    @Test fun noPlainTextAtAllAndOnlySystemCertificates() {
        val doc = parse("src/main/res/xml/network_security_config.xml")
        val base = doc.elements("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val certs = doc.elements("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), certs)
        // the loopback exception of the ChatGPT sign-in is gone (stage 5d)
        assertTrue(doc.elements("domain-config").none { it.getAttribute("cleartextTrafficPermitted") == "true" })
    }

    @Test fun internetOnlyInPersonalManifest() {
        val main = parse("src/main/AndroidManifest.xml").elements("uses-permission").map { it.getAttributeNS(androidNs, "name") }
        assertTrue("android.permission.INTERNET" !in main)
        // ai-dict-2: the download service and its notification are only in the personal manifest
        assertTrue(main.none { it in setOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS") })
        val personal = parse("src/aikbPersonal/AndroidManifest.xml")
        val toolsNs = "http://schemas.android.com/tools"
        val (removed, perms) = personal.elements("uses-permission").partition { it.getAttributeNS(toolsNs, "node") == "remove" }
        // stage 5g-20: the network state, to send the waiting pieces again when the network is back (a normal permission)
        // stage 5g-21: own calls for the probe "mute the radio with a call"
        // ai-dict-2: the download of the AI dictionary: foreground service (dataSync) with a notification
        assertEquals(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.MANAGE_OWN_CALLS", "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS"),
            perms.map { it.getAttributeNS(androidNs, "name") })
        // stage 5g-3: setMode(MODE_IN_COMMUNICATION) needs it on every Android (a normal permission, no dialog)
        assertEquals("", perms[2].getAttributeNS(androidNs, "maxSdkVersion"))
        // stage 5g-19: what ONNX Runtime brings is removed: its telemetry provider; nothing else changes
        assertEquals(emptyList(), removed.map { it.getAttributeNS(androidNs, "name") })
        val providers = personal.elements("provider")
        assertEquals(listOf("ai.onnxruntime.TelemetryInitializer"), providers.map { it.getAttributeNS(androidNs, "name") })
        assertTrue(providers.all { it.getAttributeNS(toolsNs, "node") == "remove" })
        assertEquals(1, personal.elements("application").size)
        val services = personal.elements("service")
        assertEquals(listOf("helium314.keyboard.latin.ailm.AiDictDownloadService", "helium314.keyboard.latin.voice.AikbCallService"),
            services.map { it.getAttributeNS(androidNs, "name") })
        assertEquals("dataSync", services[0].getAttributeNS(androidNs, "foregroundServiceType"))
        assertEquals("false", services[0].getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.BIND_TELECOM_CONNECTION_SERVICE", services[1].getAttributeNS(androidNs, "permission"))
    }

    @Test fun personalBuildIsNotDebuggableAndSignedOutsideGit() {
        val gradle = File("build.gradle.kts").readText()
        val personalPart = gradle.substringAfter("// personal is not debuggable").substringBefore("sourceSets[\"debugNoMinify\"].manifest")
        assertTrue("isDebuggable = false" in personalPart)
        assertTrue("signingConfig = personalSigning" in personalPart)
        assertTrue(".secrets/aikb-signing.properties" in personalPart)
        assertTrue("throw GradleException" in personalPart) // no parameters file: the personal build fails
        assertTrue(Regex("""(store|key)Password\s*=\s*"""").find(gradle) == null, "no password written in the build file")
    }
}

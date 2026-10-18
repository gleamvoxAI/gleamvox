// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.ApiKeyStore
import helium314.keyboard.latin.voiceapi.VoiceApi
import java.util.concurrent.Executors

/** Actions of the AI settings screen that are not about one feature: connection check, browser links, "Delete all". */
object OpenAiSetup {
    private const val TAG = "OpenAiSetup"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aikb-openai").apply { isDaemon = true } }

    /** "Check connection with OpenAI", on a background thread; works without a key */
    fun checkConnection(context: Context, onDone: (ConnectionCheck.Result) -> Unit) {
        val app = context.applicationContext
        worker.execute { onDone(ConnectionCheck.run(AikbNetwork.get(app)).also {
            ConnectionCheck.remember(it)
            if (it.ok) OpenAiReach.ok(app.prefs()) else OpenAiReach.problem(app.prefs())
        }) }
    }

    /** the browser with its address bar, never a page embedded in the app; https only */
    fun openInBrowser(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
    }

    /** "Delete all" (background thread): the API key with its Keystore key and protection mode, and all AI settings */
    fun eraseEverything(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        worker.execute {
            eraseEverything(app.prefs(), VoiceApi.keyStore(app))
            helium314.keyboard.latin.voice.VoiceJournal.erase(app)
            helium314.keyboard.latin.voiceapi.SilentMics.erase(app) // the marks of devices without sound (stage 5g-3)
            Log.i(TAG, "everything deleted")
            onDone()
        }
    }

    fun eraseEverything(prefs: android.content.SharedPreferences, apiKey: ApiKeyStore?) {
        apiKey?.delete()
        OpenAiPrefs.eraseSettings(prefs)
    }

    /** the cause for the screen: in plain words when it is known, else the technical cause */
    fun causeText(context: Context, d: NetDiagnostic): String {
        if (d.kind != NetDiagnostic.Kind.INTERNET_OFF) return d.short()
        return context.getString(R.string.openai_cause_internet_off) + ", " + d.elapsedMs + " ms"
    }

    /** text for "Copy details", nothing secret */
    fun details(context: Context, d: NetDiagnostic): String =
        d.details(listOfNotNull(
            "internet switch: " + if (AikbNetwork.internetAllowed(context)) "on" else "off",
            "proxy for OpenAI: " + OpenAiProxy.read(context.prefs()),
            keyProtectionText(context),
        ))

    /** "Key protection: strict / normal" in plain words; null before the first unlock or without a key */
    fun keyProtectionText(context: Context): String? =
        VoiceApi.keyStore(context)?.takeIf { it.hasKey() }?.protection()?.let { keyProtectionText(context, it) }

    fun keyProtectionText(context: Context, p: KeyProtection): String = context.getString(when (p) {
        KeyProtection.STRICT -> R.string.openai_key_protection_strict
        KeyProtection.PLAIN -> R.string.openai_key_protection_plain
    })
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.features

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * Features shown on the "AI Keyboard features" screen (docs/DECISIONS.md, first start screen). Everything is off by
 * default; only a press on "Turn on" changes something. A feature added in a later version is shown once.
 */
enum class AikbFeature(val id: String) {
    CONTACTS("contacts"),
    VOICE("voice"),
    /** AI functions with the own OpenAI API key (stage 5d, replaces "chatgpt"); only in the personal variant */
    AI("ai"),
    /** stage 5g-16: the fresh screenshot in the strip (access to photos); Android 13+ */
    SCREENSHOT("screenshot"),
}

object AikbFeatures {
    /** ids of the feature cards already shown */
    const val PREF_SEEN = "aikb_features_seen"

    fun available(ai: Boolean = OpenAiPrefs.isAvailable()): List<AikbFeature> =
        listOfNotNull(AikbFeature.CONTACTS, AikbFeature.VOICE, AikbFeature.AI.takeIf { ai },
            AikbFeature.SCREENSHOT.takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                helium314.keyboard.latin.FreshScreenshot.inBuild() })

    fun unseen(prefs: SharedPreferences, ai: Boolean = OpenAiPrefs.isAvailable()): List<AikbFeature> {
        val seen = prefs.getStringSet(PREF_SEEN, emptySet()).orEmpty()
        return available(ai).filter { it.id !in seen }
    }

    /** only remembers that the cards were shown, never switches anything on */
    fun markSeen(prefs: SharedPreferences, features: Collection<AikbFeature>) {
        val seen = prefs.getStringSet(PREF_SEEN, emptySet()).orEmpty()
        prefs.edit { putStringSet(PREF_SEEN, seen + features.map { it.id }) }
    }

    fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context, prefs: SharedPreferences, feature: AikbFeature): Boolean = when (feature) {
        AikbFeature.CONTACTS -> prefs.getBoolean(Settings.PREF_USE_CONTACTS, Defaults.PREF_USE_CONTACTS)
            && hasPermission(context, Manifest.permission.READ_CONTACTS)
        AikbFeature.VOICE -> prefs.getBoolean(Settings.PREF_VOICE_INPUT_INTERNAL, Defaults.PREF_VOICE_INPUT_INTERNAL)
            && hasPermission(context, Manifest.permission.RECORD_AUDIO)
        AikbFeature.AI -> AikbNetwork.internetAllowed(context) && VoiceApi.hasKey(context)
        AikbFeature.SCREENSHOT -> prefs.getBoolean(Settings.PREF_SUGGEST_SCREENSHOT, Defaults.PREF_SUGGEST_SCREENSHOT)
            && helium314.keyboard.latin.FreshScreenshot.access(context) == helium314.keyboard.latin.FreshScreenshot.Access.FULL
    }
}

/** Steps of the first start wizard: welcome, enable the keyboard, select it, features, AI dictionary (ai-dict-2), done. */
object WizardSteps {
    const val WELCOME = 0
    const val ENABLE = 1
    const val SELECT = 2
    const val FEATURES = 3
    /** the offer of the AI dictionary: only inside the wizard, only once, only when it can be downloaded here */
    const val AI_DICT = 4
    const val DONE = 5
    val ORDER = listOf(WELCOME, ENABLE, SELECT, FEATURES, AI_DICT, DONE)

    fun determine(enabled: Boolean, current: Boolean, featuresSeen: Boolean, aiDictOffer: Boolean = false): Int = when {
        !enabled -> WELCOME
        !current -> SELECT
        !featuresSeen -> FEATURES
        aiDictOffer -> AI_DICT
        else -> DONE
    }

    /** the step after the features page */
    fun afterFeatures(aiDictOffer: Boolean) = if (aiDictOffer) AI_DICT else DONE
}

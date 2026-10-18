// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createToolbarSettings

class SettingsContainer(context: Context) {
    private val list = createSettings(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key $it added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    // filtering could be more elaborate, but should be good enough for a start
    // always have all settings in search, because:
    //  don't show disabled settings -> users confused
    //  show as disabled (i.e. no interaction possible) -> users confused
    //  show, but change will not do anything because another setting needs to be enabled first -> probably best
    fun filter(searchTerm: String): List<Setting> {
        val term = searchTerm.lowercase()
        val results = mutableSetOf<Setting>()
        list.forEach { setting -> if (setting.title.lowercase().startsWith(term)) results.add(setting) }
        list.forEach { setting -> if (setting.title.lowercase().split(' ').any { it.startsWith(term) }) results.add(setting) }
        list.forEach { setting ->
            if (setting.description?.lowercase()?.split(' ')?.any { it.startsWith(term) } == true)
                results.add(setting)
        }
        return results.toList()
    }
}

@Immutable
class Setting(
    context: Context,
    val key: String,
    @StringRes titleId: Int,
    @StringRes descriptionId: Int? = null,
    private val content: @Composable (Setting) -> Unit
) {
    val title = context.getString(titleId)
    val description = descriptionId?.let { context.getString(it) }

    @Composable
    fun Preference() {
        content(this)
    }
}

// intentionally not putting individual debug settings in here so user knows the context
private fun createSettings(context: Context) = createAboutSettings(context) + createAppearanceSettings(context) +
        createCorrectionSettings(context) + createPreferencesSettings(context) + createToolbarSettings(context) +
        createLayoutSettings(context) + createAdvancedSettings(context) +
        if (JniUtils.sHaveGestureLib) createGestureTypingSettings(context) else emptyList()

object SettingsWithoutKey {
    const val EDIT_PERSONAL_DICTIONARY = "edit_personal_dictionary"
    const val APP = "app"
    const val VERSION = "version"
    const val LICENSE = "license"
    /** stage 5g-6: the speech detector of WebRTC (BSD) */
    const val THIRD_PARTY_VAD = "third_party_vad"
    /** stage 5g-19: the Silero speech detector (MIT) and ONNX Runtime (MIT), only in the personal variant */
    const val THIRD_PARTY_SILERO = "third_party_silero"
    const val THIRD_PARTY_DICTIONARIES = "third_party_dictionaries"
    const val DICTIONARY_CHECK = "dictionary_check"
    /** ai-dict-1: the AI dictionary on the phone (download, on/off, delete, journal) */
    const val AI_DICTIONARY = "ai_dictionary"
    const val HIDDEN_FEATURES = "hidden_features"
    const val GITHUB = "github"
    const val GLEAMVOX_SOURCE = "gleamvox_source"
    const val GLEAMVOX_SITE = "gleamvox_site"
    const val GITHUB_WIKI = "github_wiki"
    const val COMMUNITY_LINKS = "community_links"
    const val SAVE_LOG = "save_log"
    const val BACKUP_RESTORE = "backup_restore"
    const val DEBUG_SETTINGS = "screen_debug"
    const val LOAD_GESTURE_LIB = "load_gesture_library"
    const val BACKGROUND_IMAGE = "background_image"
    const val BACKGROUND_IMAGE_LANDSCAPE = "background_image_landscape"
    const val CUSTOM_FONT = "custom_font"
    const val CUSTOM_EMOJI_FONT = "custom_emoji_font"
}

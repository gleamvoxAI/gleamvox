// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiInvite
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.settings.features.AiInviteCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
    onClickAi: () -> Unit = {},
    onClickFeatures: () -> Unit = {},
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                // AI Keyboard: invitation and the AI entry at the very top, so the key is easy to paste
                if (OpenAiPrefs.isAvailable()) {
                    val ctx = LocalContext.current
                    val prefs = ctx.prefs()
                    var snoozedUntil by remember { mutableLongStateOf(prefs.getLong(AiInvite.PREF_SNOOZED_UNTIL, 0L)) }
                    // null until known: the card never flashes up for a moment when a key exists
                    var hasKey by remember { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.IO) { VoiceApi.hasKey(ctx) } }
                    // stage 5zh: an installation that uses AI but lost the text consent in 5d: one card, one dialog
                    var confirmTick by remember { mutableStateOf(0) }
                    var consentDialog by remember { mutableStateOf(false) }
                    @Suppress("UNUSED_EXPRESSION") confirmTick
                    if (hasKey != null && helium314.keyboard.latin.ai.AiConsent.needsConfirmation(prefs, true, hasKey == true, System.currentTimeMillis()))
                        helium314.keyboard.settings.features.ConfirmConsentCard(onConfirm = { consentDialog = true }, onLater = {
                            helium314.keyboard.latin.ai.AiConsent.snooze(prefs); confirmTick++
                        })
                    if (consentDialog) helium314.keyboard.settings.features.UnifiedConsentDialog(onDismiss = { consentDialog = false }, onAccept = {
                        helium314.keyboard.latin.ai.AiConsent.giveAll(prefs)
                        consentDialog = false; confirmTick++
                    })
                    if (hasKey == false && AiInvite.shouldShow(true, false, snoozedUntil, System.currentTimeMillis()))
                        AiInviteCard(onConnect = onClickAi, onLater = {
                            AiInvite.snooze(prefs)
                            snoozedUntil = prefs.getLong(AiInvite.PREF_SNOOZED_UNTIL, 0L)
                        })
                    Preference(
                        name = stringResource(R.string.ai_title),
                        description = stringResource(if (hasKey == true) R.string.ai_summary_key else R.string.ai_summary),
                        onClick = onClickAi,
                        icon = R.drawable.ic_settings_about
                    ) { NextScreenIcon() }
                }
                Preference(
                    name = stringResource(R.string.features_settings_title),
                    description = stringResource(if (OpenAiPrefs.isAvailable()) R.string.features_settings_summary_ai else R.string.features_settings_summary),
                    onClick = onClickFeatures,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    onClick = onClickLanguage,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_preferences),
                    onClick = onClickPreferences,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_appearance),
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_appearance
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_toolbar),
                    onClick = onClickToolbar,
                    icon = R.drawable.ic_settings_toolbar
                ) { NextScreenIcon() }
                if (JniUtils.sHaveGestureLib)
                    Preference(
                        name = stringResource(R.string.settings_screen_gesture),
                        onClick = onClickGestureTyping,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                    Preference(
                        name = stringResource(R.string.gesture_data_screen),
                        onClick = onClickDataGathering,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_correction),
                    onClick = onClickTextCorrection,
                    icon = R.drawable.ic_settings_correction
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_secondary_layouts),
                    onClick = onClickLayouts,
                    icon = R.drawable.ic_settings_layout
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.dictionary_settings_category),
                    onClick = onClickDictionaries,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_advanced),
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

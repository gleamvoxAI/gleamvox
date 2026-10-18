// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import helium314.keyboard.latin.R
import helium314.keyboard.latin.openai.OpenAiPrefs
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.voice.VoiceInput
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.features.AikbFeatures
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference

/** "Features and permissions": the same switches as the cards of the first start screen. */
@Composable
fun FeaturesScreen(onClickBack: () -> Unit, onClickAi: () -> Unit) {
    val ctx = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { recheck++; onPauseOrDispose { } }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.features_settings_title), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                // names from contacts: HeliBoard's own switch, which asks for the contacts permission
                SettingsActivity.settingsContainer[Settings.PREF_USE_CONTACTS]?.Preference()
                key(recheck) {
                    val micAllowed = AikbFeatures.hasPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    SwitchPreference(
                        name = stringResource(R.string.feature_voice_title),
                        key = Settings.PREF_VOICE_INPUT_INTERNAL,
                        default = Defaults.PREF_VOICE_INPUT_INTERNAL,
                        description = stringResource(R.string.feature_voice_text),
                        onCheckedChange = { on -> if (on && !micAllowed) VoiceInput.requestPermissionFromSettings(ctx) },
                    )
                    if (!micAllowed)
                        Preference(
                            name = stringResource(R.string.feature_mic_permission),
                            description = stringResource(R.string.feature_mic_permission_summary),
                            onClick = { VoiceInput.requestPermissionFromSettings(ctx) },
                        )
                }
                if (OpenAiPrefs.isAvailable()) {
                    InternetSwitch()
                    Preference(
                        name = stringResource(R.string.ai_title),
                        description = stringResource(R.string.feature_ai_text),
                        onClick = onClickAi,
                    ) { NextScreenIcon() }
                }
            }
        }
    }
}

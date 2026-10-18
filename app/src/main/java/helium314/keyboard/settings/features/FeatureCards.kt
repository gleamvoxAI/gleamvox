// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.features

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceInput

/**
 * Full screen "AI Keyboard features": one card per feature, everything off, "Turn on" and "Later" on every card.
 * Used as a step of the first start wizard and, once, for features that came with an update.
 */
@Composable
fun FeaturesPage(features: List<AikbFeature>, doneText: String, onOpenAi: () -> Unit = {}, onDone: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.features_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.features_intro), style = MaterialTheme.typography.bodyLarge)
            FeatureCards(features, onOpenAi)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { }
                Button(onClick = onDone) { Text(doneText) }
            }
        }
    }
}

/**
 * the cards; only a press on "Turn on" (and the system permission dialog after it) switches on. "Turn on" of the AI
 * card opens the AI screen ([onOpenAi]), where the key is pasted and the consents are given.
 */
@Composable
fun FeatureCards(features: List<AikbFeature>, onOpenAi: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var recheck by remember { mutableIntStateOf(0) } // permissions may change in the system dialogs
    LifecycleResumeEffect(Unit) { recheck++; onPauseOrDispose { } }
    var later by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prefs.edit { putBoolean(Settings.PREF_USE_CONTACTS, true) }
        recheck++
    }

    val screenshotLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (helium314.keyboard.latin.FreshScreenshot.access(ctx) != helium314.keyboard.latin.FreshScreenshot.Access.NONE)
            prefs.edit { putBoolean(Settings.PREF_SUGGEST_SCREENSHOT, true) }
        recheck++
    }

    // key(recheck): the cards are read again after a permission dialog
    key(recheck) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (feature in features) {
            val on = AikbFeatures.isOn(ctx, prefs, feature)
            val (title, text) = when (feature) {
                AikbFeature.CONTACTS -> R.string.feature_contacts_title to R.string.feature_contacts_text
                AikbFeature.VOICE -> R.string.feature_voice_title to R.string.feature_voice_text
                AikbFeature.AI -> R.string.feature_ai_title to R.string.feature_ai_text
                AikbFeature.SCREENSHOT -> R.string.feature_screenshot_title to R.string.feature_screenshot_text
            }
            val status = when {
                on -> stringResource(R.string.feature_on)
                feature.id in later -> stringResource(R.string.feature_later_note)
                else -> null
            }
            FeatureCard(
                title = stringResource(title),
                text = stringResource(text),
                status = status,
                showButtons = !on && feature.id !in later,
                onEnable = {
                    when (feature) {
                        AikbFeature.CONTACTS ->
                            if (AikbFeatures.hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
                                prefs.edit { putBoolean(Settings.PREF_USE_CONTACTS, true) }
                                recheck++
                            } else contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                        AikbFeature.VOICE -> {
                            prefs.edit { putBoolean(Settings.PREF_VOICE_INPUT_INTERNAL, true) }
                            if (!AikbFeatures.hasPermission(ctx, Manifest.permission.RECORD_AUDIO))
                                VoiceInput.requestPermissionFromSettings(ctx)
                            recheck++
                        }
                        AikbFeature.AI -> onOpenAi() // the key and the consents are on the AI screen
                        AikbFeature.SCREENSHOT -> screenshotLauncher.launch(
                            helium314.keyboard.latin.FreshScreenshot.permissions(android.os.Build.VERSION.SDK_INT))
                    }
                },
                onLater = { later = later + feature.id },
            )
        }
    } }
}

@Composable
fun FeatureCard(title: String, text: String, status: String?, showButtons: Boolean, onEnable: () -> Unit, onLater: () -> Unit,
                enableText: String = stringResource(R.string.feature_enable)) {
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (showButtons)
                helium314.keyboard.settings.screens.EndButtons {
                    TextButton(onClick = onLater, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_later)) }
                    Button(onClick = onEnable, modifier = helium314.keyboard.settings.screens.endButton) { Text(enableText) }
                }
        }
    }
}

/** "Connect AI: paste an OpenAI API key" at the top of the main settings (personal variant, no key, not closed) */
@Composable
fun AiInviteCard(onConnect: () -> Unit, onLater: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_invite_title), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.ai_invite_text), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            helium314.keyboard.settings.screens.EndButtons {
                TextButton(onClick = onLater, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_later)) }
                Button(onClick = onConnect, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_invite_connect)) }
            }
        }
    }
}

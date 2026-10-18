// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.features

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiConsent
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.settings.screens.KeyCheckBanner

/** the single consent text with the links to the OpenAI policies (shared by the wizard page and the dialog) */
@Composable
fun UnifiedConsentText() {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_enable_text))
        Text(stringResource(R.string.ai_text_consent_what), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { OpenAiSetup.openInBrowser(ctx, OpenAi.PRIVACY_POLICY_URL) }) { Text(stringResource(R.string.voice_api_consent_policy)) }
        }
        Row {
            TextButton(onClick = { OpenAiSetup.openInBrowser(ctx, OpenAi.API_DATA_URL) }) { Text(stringResource(R.string.voice_api_consent_data)) }
        }
    }
}

/**
 * The single consent as a dialog (the card "Confirm the consent for ✨", a tap on the dimmed ✨): voice, ✨ and the
 * cleanup at once; nothing changes without [onAccept].
 */
@Composable
fun UnifiedConsentDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_consent_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { UnifiedConsentText() } },
        confirmButton = { TextButton(onClick = onAccept, modifier = Modifier.testTag("consent_accept")) { Text(stringResource(R.string.voice_api_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_enable_skip)) } },
    )
}

/**
 * The wizard step "Turn on AI" (stage 5zh, item 8): one short text, one button "Turn on" that asks for the microphone
 * (and, if ticked, the contacts), switches the internet on and gives the single consent; then the field for the key with
 * "Check" and "Later". Refusing the microphone does not stop ✨; without the consent nothing is switched on.
 */
@Composable
fun EnableAiPage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var contacts by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(AiConsent.hasAll(prefs)) }
    var micDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true
        prefs.edit {
            putBoolean(Settings.PREF_VOICE_INPUT_INTERNAL, true)
            if (granted[Manifest.permission.READ_CONTACTS] == true) putBoolean(Settings.PREF_USE_CONTACTS, true)
        }
        micDenied = !mic
        enabled = true
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(if (enabled) R.string.ai_enable_done_title else R.string.ai_enable_title), style = MaterialTheme.typography.headlineMedium)
            if (!enabled) {
                UnifiedConsentText()
                Text(stringResource(R.string.ai_enable_what), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().clickable { contacts = !contacts }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = contacts, onCheckedChange = { contacts = it })
                    Text(stringResource(R.string.ai_enable_contacts), style = MaterialTheme.typography.bodyMedium)
                }
                helium314.keyboard.settings.screens.EndButtons {
                    TextButton(onClick = onDone, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_enable_skip)) }
                    Button(modifier = helium314.keyboard.settings.screens.endButton.testTag("enable_ai"), onClick = {
                        // the consent first: the text above is what the user agrees to with this one press
                        AiConsent.giveAll(prefs, internet = true)
                        launcher.launch(listOfNotNull(Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS.takeIf { contacts }).toTypedArray())
                    }) { Text(stringResource(R.string.ai_enable_button)) }
                }
            } else {
                if (micDenied) Text(stringResource(R.string.ai_enable_mic_denied), color = MaterialTheme.colorScheme.error)
                WizardKeyBlock(onDone)
            }
        }
    }
}

/** the key in the wizard: paste, "Check" (saves and checks), "Later"; after a working key "Done" */
@Composable
fun WizardKeyBlock(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var check by androidx.compose.runtime.remember { mutableStateOf<VoiceApi.KeyCheck?>(null) }
    val notAKey = stringResource(R.string.voice_api_key_not_a_key)
    val notSaved = stringResource(R.string.voice_api_key_not_saved)
    // no screenshots while the key can be typed in
    val window = ctx.getActivity()?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun saveAndCheck(text: String) {
        message = null; check = null
        when (VoiceApi.saveKey(ctx, text)) {
            VoiceApi.SaveResult.SAVED -> { input = ""; checking = true; VoiceApi.checkKey(ctx) { r -> check = r; checking = false } }
            VoiceApi.SaveResult.NOT_A_KEY -> message = notAKey
            VoiceApi.SaveResult.NOT_SAVED -> message = notSaved
        }
    }
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_enable_key_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_enable_key_text), style = MaterialTheme.typography.bodyMedium)
            val ok = check?.result is TranscriptionResult.Ok
            if (!ok) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.voice_api_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                helium314.keyboard.settings.screens.StackedButtons {
                    helium314.keyboard.settings.screens.WideButton(
                        stringResource(if (checking) R.string.openai_check_running else R.string.ai_enable_check),
                        enabled = !checking && input.isNotBlank(), onClick = { saveAndCheck(input) })
                    helium314.keyboard.settings.screens.WideButton(stringResource(R.string.voice_api_key_paste), enabled = !checking,
                        onClick = { VoiceApi.clipboardText(ctx)?.let { saveAndCheck(it) } ?: run { message = notAKey } })
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            check?.let { KeyCheckBanner(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (ok) Button(onClick = onDone) { Text(stringResource(R.string.ai_enable_finish)) }
                else TextButton(onClick = onDone) { Text(stringResource(R.string.ai_enable_key_later)) }
            }
        }
    }
}

/** "Confirm the consent for ✨" at the top of the main settings (existing installations after the update) */
@Composable
fun ConfirmConsentCard(onConfirm: () -> Unit, onLater: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("confirm_consent_card"),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_confirm_title), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.ai_confirm_text), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            helium314.keyboard.settings.screens.EndButtons {
                TextButton(onClick = onLater, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_enable_skip)) }
                Button(onClick = onConfirm, modifier = helium314.keyboard.settings.screens.endButton) { Text(stringResource(R.string.ai_confirm_button)) }
            }
        }
    }
}

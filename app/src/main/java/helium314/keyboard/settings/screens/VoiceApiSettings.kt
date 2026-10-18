// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import helium314.keyboard.latin.voiceapi.VoiceApiUsage
import helium314.keyboard.latin.voiceapi.VoiceApiBalance
import helium314.keyboard.latin.voiceapi.AudioRecordSource
import helium314.keyboard.latin.voiceapi.Loudness
import helium314.keyboard.latin.voiceapi.MicCheck
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.openai.OpenAi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voiceapi.TranscriptionResult
import helium314.keyboard.latin.voiceapi.VoiceApi
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "OpenAI API key" on the AI screen (docs/AI.md, docs/VOICE_API.md): one key for voice, ✨ and the cleanup. The key is
 * pasted into a password field (or from the clipboard), saved encrypted, and afterwards only "••••" and its last 4
 * characters are shown. [onKeyChanged] after saving or deleting, so the other blocks read their state again.
 */
@Composable
fun ApiKeySection(onKeyChanged: () -> Unit = {}) {
    if (!VoiceApiPrefs.isAvailable()) return
    val ctx = LocalContext.current
    var masked by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    // stays until "Check key" is pressed again, the key changes or the screen is left (stage 5b)
    var keyCheck by remember { mutableStateOf<VoiceApi.KeyCheck?>(null) }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) { hasKey = VoiceApi.hasKey(ctx); masked = VoiceApi.maskedKey(ctx) }
    }
    val savedText = stringResource(R.string.voice_api_key_saved)
    val notAKeyText = stringResource(R.string.voice_api_key_not_a_key)
    val notSavedText = stringResource(R.string.voice_api_key_not_saved)
    val deletedText = stringResource(R.string.voice_api_key_deleted)
    fun save(text: String) {
        message = when (VoiceApi.saveKey(ctx, text)) {
            VoiceApi.SaveResult.SAVED -> { input = ""; editing = false; reload++; onKeyChanged(); savedText }
            VoiceApi.SaveResult.NOT_A_KEY -> notAKeyText
            VoiceApi.SaveResult.NOT_SAVED -> notSavedText
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_key_summary), style = MaterialTheme.typography.bodySmall)
        if (hasKey && !editing) {
            Text(masked?.let { stringResource(R.string.voice_api_key_saved_as, it) } ?: stringResource(R.string.voice_api_key_unreadable))
            if (VoiceApiPrefs.keyRejected(ctx.prefs()))
                Text(stringResource(R.string.voice_api_key_rejected), color = MaterialTheme.colorScheme.error)
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                message = null
                keyCheck = null
                VoiceApi.checkKey(ctx) { r ->
                    checking = false
                    keyCheck = r
                    reload++
                }
            }) { Text(stringResource(if (checking) R.string.openai_check_running else R.string.voice_api_key_check)) }
            keyCheck?.let { KeyCheckBanner(it) }
            StackedButtons {
                WideButton(stringResource(R.string.voice_api_key_replace), onClick = { editing = true; message = null; keyCheck = null })
                WideButton(stringResource(R.string.voice_api_key_delete), onClick = { confirmDelete = true }, color = MaterialTheme.colorScheme.error)
            }
        } else {
            if (!hasKey) Text(stringResource(R.string.voice_api_key_none), style = MaterialTheme.typography.bodySmall)
            // no screenshots or screen recording while the key can be typed in
            val window = ctx.getActivity()?.window
            DisposableEffect(window) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            // a password field: the keyboard neither suggests nor learns what is typed here
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text(stringResource(R.string.voice_api_key_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            StackedButtons {
                WideButton(stringResource(R.string.voice_api_key_save), enabled = input.isNotBlank(), onClick = { save(input) })
                WideButton(stringResource(R.string.voice_api_key_paste), onClick = { VoiceApi.clipboardText(ctx)?.let { save(it) } ?: run { message = notAKeyText } })
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.voice_api_key_delete_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            VoiceApi.deleteKey(ctx)
            keyCheck = null
            message = deletedText
            reload++
            onKeyChanged()
        }) { Text(stringResource(R.string.voice_api_key_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * "Voice": the recognizer (OpenAI with the key, or Android's), pause before sending, minutes, longest recording and the
 * balance estimate (docs/VOICE_API.md)
 */
@Composable
fun VoiceSection() {
    if (!VoiceApiPrefs.isAvailable()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_api_summary), style = MaterialTheme.typography.bodySmall)
        SpeechEngineChooser()
        VoiceApiUsageBlock()
        ContinuousDictationBlock()
        MicChoiceBlock() // the owner (5g-5): for everybody, Auto by default
    }
}

/**
 * "Microphone for voice" (stage 5g): Auto (the Bluetooth headset when one is connected, by default) / Phone / Headset.
 * No permission is asked: Android 12+ routes the headset without BLUETOOTH_CONNECT (checked in the Android sources).
 */
@Composable
fun MicChoiceBlock() {
    val prefs = LocalContext.current.prefs()
    var selected by remember { mutableStateOf(helium314.keyboard.latin.voiceapi.MicChoice.read(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.voice_mic_choice_title))
        helium314.keyboard.latin.voiceapi.MicChoice.entries.forEach { option ->
            fun choose() { selected = option; helium314.keyboard.latin.voiceapi.MicChoice.save(prefs, option) }
            Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == selected, onClick = { choose() })
                Text(stringResource(when (option) {
                    helium314.keyboard.latin.voiceapi.MicChoice.AUTO -> R.string.voice_mic_choice_auto
                    helium314.keyboard.latin.voiceapi.MicChoice.PHONE -> R.string.voice_mic_choice_phone
                    helium314.keyboard.latin.voiceapi.MicChoice.HEADSET -> R.string.voice_mic_choice_headset
                }))
            }
        }
        Text(stringResource(R.string.voice_mic_choice_summary), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * "Continuous dictation" (stage 5g, off by default): the switch, the silence that ends it (5–60 s, 10 by default) and
 * the longest dictation (1–10 min, 5 by default). A long press on the microphone starts it once even when it is off.
 */
@Composable
private fun ContinuousDictationBlock() {
    val prefs = LocalContext.current.prefs()
    var on by remember { mutableStateOf(VoiceApiPrefs.continuous(prefs)) }
    var silence by remember { mutableFloatStateOf(VoiceApiPrefs.continuousSilenceS(prefs).toFloat()) }
    var limit by remember { mutableFloatStateOf(VoiceApiPrefs.continuousLimitMin(prefs).toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { on = !on; VoiceApiPrefs.setContinuous(prefs, on) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.voice_continuous_title), Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; VoiceApiPrefs.setContinuous(prefs, it) })
        }
        Text(stringResource(R.string.voice_continuous_summary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.voice_continuous_silence, Math.round(silence)))
        Slider(value = silence, onValueChange = { silence = it },
            valueRange = VoiceApiPrefs.MIN_CONTINUOUS_SILENCE_S.toFloat()..VoiceApiPrefs.MAX_CONTINUOUS_SILENCE_S.toFloat(),
            steps = VoiceApiPrefs.MAX_CONTINUOUS_SILENCE_S - VoiceApiPrefs.MIN_CONTINUOUS_SILENCE_S - 1,
            onValueChangeFinished = { VoiceApiPrefs.setContinuousSilenceS(prefs, Math.round(silence)) })
        Text(stringResource(R.string.voice_continuous_limit, Math.round(limit)))
        Slider(value = limit, onValueChange = { limit = it },
            valueRange = VoiceApiPrefs.MIN_CONTINUOUS_LIMIT_MIN.toFloat()..VoiceApiPrefs.MAX_CONTINUOUS_LIMIT_MIN.toFloat(),
            steps = VoiceApiPrefs.MAX_CONTINUOUS_LIMIT_MIN - VoiceApiPrefs.MIN_CONTINUOUS_LIMIT_MIN - 1,
            onValueChangeFinished = { VoiceApiPrefs.setContinuousLimitMin(prefs, Math.round(limit)) })
    }
}

/** the order on the screen: "Auto (recommended)" first, then "Smart recognition" (OpenAI), then the system one */
val SpeechEngineChoices = listOf(SpeechEngineSetting.AUTO, SpeechEngineSetting.OPENAI, SpeechEngineSetting.SYSTEM)

/** "Speech recognition": Auto / Smart recognition / system (docs/VOICE_API.md, section 3); Auto and Smart need the consent */
@Composable
private fun SpeechEngineChooser() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var selected by remember { mutableStateOf(VoiceApiPrefs.engine(prefs)) }
    var consentFor by remember { mutableStateOf<SpeechEngineSetting?>(null) }
    var consent by remember { mutableStateOf(VoiceApiPrefs.consent(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.voice_api_engine_title))
        SpeechEngineChoices.forEach { option ->
            fun choose() {
                // OpenAI and Auto need the consent first; without it nothing is switched on
                if (VoiceApiPrefs.setEngine(prefs, option)) selected = option else consentFor = option
            }
            Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == selected, onClick = { choose() })
                Text(stringResource(when (option) {
                    SpeechEngineSetting.OPENAI -> R.string.voice_api_engine_openai
                    SpeechEngineSetting.SYSTEM -> R.string.voice_api_engine_system
                    SpeechEngineSetting.AUTO -> R.string.voice_api_engine_auto
                }))
            }
        }
        Text(stringResource(R.string.voice_api_engine_summary), style = MaterialTheme.typography.bodySmall)
        if (consent) TextButton(onClick = {
            VoiceApiPrefs.withdrawConsent(prefs)
            consent = false
            selected = SpeechEngineSetting.SYSTEM
        }) { Text(stringResource(R.string.voice_api_consent_withdraw)) }
    }
    consentFor?.let { option ->
        VoiceApiConsentDialog(onDismiss = { consentFor = null }, onAccept = {
            VoiceApiPrefs.giveConsent(prefs)
            VoiceApiPrefs.setEngine(prefs, option)
            consent = true
            selected = option
            consentFor = null
        })
    }
}

/**
 * Consent before voice through OpenAI is switched on the first time (docs/VOICE_API.md): the sound goes to OpenAI with
 * your key and you pay per minute, AI Keyboard keeps no sound, links to the OpenAI policy. Nothing changes without [onAccept].
 */
@Composable
fun VoiceApiConsentDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_api_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.voice_api_consent_text))
                Text(stringResource(R.string.voice_api_consent_what), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { OpenAiSetup.openInBrowser(ctx, OpenAi.PRIVACY_POLICY_URL) }) {
                    Text(stringResource(R.string.voice_api_consent_policy))
                }
                TextButton(onClick = { OpenAiSetup.openInBrowser(ctx, OpenAi.API_DATA_URL) }) {
                    Text(stringResource(R.string.voice_api_consent_data))
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.voice_api_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_later)) } },
    )
}

/** minutes used this month, the monthly ceiling and the longest recording (only on this phone); dollars only in Advanced */
@Composable
private fun VoiceApiUsageBlock() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val usage = remember { VoiceApiUsage(prefs) }
    var cap by remember { mutableFloatStateOf(usage.capMinutes().toFloat()) }
    var maxSeconds by remember { mutableFloatStateOf(VoiceApiPrefs.maxSeconds(prefs).toFloat()) }
    val locale = ctx.resources.configuration.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.voice_api_usage_title))
        Text(stringResource(R.string.voice_api_usage, String.format(locale, "%.1f", usage.usedMinutes()), cap.toInt()))
        when {
            usage.capReached() -> Text(stringResource(R.string.voice_api_usage_reached), color = MaterialTheme.colorScheme.error)
            usage.nearCap() -> Text(stringResource(R.string.voice_api_usage_warning), color = MaterialTheme.colorScheme.error)
        }
        Slider(value = cap, onValueChange = { cap = it }, valueRange = 0f..VoiceApiUsage.MAX_CAP_MINUTES.toFloat(),
            steps = VoiceApiUsage.MAX_CAP_MINUTES / 10 - 1, onValueChangeFinished = { usage.setCapMinutes(cap.toInt()) })
        Text(stringResource(R.string.voice_api_usage_summary), style = MaterialTheme.typography.bodySmall)
        EndPauseSlider()
        Text(stringResource(R.string.voice_api_max_seconds, maxSeconds.toInt()))
        Slider(value = maxSeconds, onValueChange = { maxSeconds = it },
            valueRange = VoiceApiPrefs.MIN_MAX_SECONDS.toFloat()..VoiceApiPrefs.MAX_MAX_SECONDS.toFloat(),
            steps = (VoiceApiPrefs.MAX_MAX_SECONDS - VoiceApiPrefs.MIN_MAX_SECONDS) / 10 - 1,
            onValueChangeFinished = { VoiceApiPrefs.setMaxSeconds(prefs, maxSeconds.toInt()) })
    }
}

/**
 * "Check microphone" (stage 5b): 2.5 s with each microphone source, a live level and the peak of each with
 * "good / quiet / silence". The sound is only measured: never kept, saved or sent. A source that is not silent is
 * remembered for voice through OpenAI.
 */
@Composable
fun MicCheckBlock() {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var results by remember { mutableStateOf<List<MicCheck.SourceResult>?>(null) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    var needPermission by remember { mutableStateOf(false) }
    val cancelled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { cancelled.set(true) } } // leaving the screen ends the recording
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled = !running, onClick = {
            results = null; chosen = null
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needPermission = true
                helium314.keyboard.latin.voice.VoiceInput.requestPermissionFromSettings(ctx)
                return@OutlinedButton
            }
            needPermission = false
            running = true
            cancelled.set(false)
            // a connected Bluetooth headset is checked too (stage 5g)
            // only a proven headset (stage 5g-3): watches and speakers are not checked
            val headset = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                helium314.keyboard.latin.voiceapi.AndroidHeadsetMic.proven(ctx) else null
            Thread({
                // stage 5g-6: also MIC with the noise suppressor and the gain control of the phone, to compare
                val r = MicCheck.run({ src -> if (src == Loudness.SOURCE_MIC_EFFECTS) AudioRecordSource(Loudness.SOURCE_MIC, effects = true) else AudioRecordSource(src) },
                    sources = MicCheck.SOURCES + Loudness.SOURCE_MIC_EFFECTS,
                    onProgress = { src, l, left -> level = l; current = src to left },
                    cancelled = { cancelled.get() }, headset = headset)
                val best = MicCheck.best(r)
                if (best != null && !cancelled.get()) VoiceApiPrefs.setAudioSource(ctx.prefs(), best)
                results = r; chosen = best; running = false; current = null; level = 0f
            }, "aikb-mic-check").start()
        }) { Text(stringResource(R.string.voice_mic_check)) }
        Text(stringResource(R.string.voice_mic_check_summary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.voice_mic_check_headset), style = MaterialTheme.typography.bodySmall)
        if (needPermission) Text(stringResource(R.string.voice_mic_permission), color = MaterialTheme.colorScheme.error)
        if (running) {
            current?.let { (src, left) ->
                Text(stringResource(R.string.voice_mic_check_running, Loudness.name(src), ((left + 999) / 1000).toInt()))
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
        }
        results?.forEach { r ->
            val text = when (r.verdict) {
                MicCheck.Verdict.UNAVAILABLE -> stringResource(R.string.voice_mic_unavailable, Loudness.name(r.source))
                else -> stringResource(R.string.voice_mic_check_result, Loudness.name(r.source),
                    helium314.keyboard.latin.voice.VoiceInputView.formatDbfs(r.peakDbfs ?: -100.0),
                    stringResource(when (r.verdict) {
                        MicCheck.Verdict.GOOD -> R.string.voice_mic_good
                        MicCheck.Verdict.QUIET -> R.string.voice_mic_quiet
                        else -> R.string.voice_mic_silent
                    }))
            }
            Text(text + (r.noiseDbfs?.let { " · " + stringResource(R.string.voice_mic_noise_floor, helium314.keyboard.latin.voice.VoiceInputView.formatDbfs(it)) } ?: ""),
                color = if (r.verdict == MicCheck.Verdict.GOOD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        chosen?.let { Text(stringResource(R.string.voice_mic_chosen, Loudness.name(it)), style = MaterialTheme.typography.bodySmall) }
    }
}

/** result of "Check key": a green banner "Key works" or a red one with the reason in plain words and "Copy details" */
@Composable
fun KeyCheckBanner(check: VoiceApi.KeyCheck) {
    val ctx = LocalContext.current
    val r = check.result
    val ms = check.ms
    when (r) {
        is TranscriptionResult.Ok -> {
            val detail = stringResource(if (check.voiceModel) R.string.voice_api_check_ok_detail else R.string.voice_api_check_ok_no_voice,
                check.textModels, ms)
            ResultBanner(true, stringResource(R.string.voice_api_check_ok_title), detail,
                spoken = stringResource(R.string.voice_api_check_ok_title) + ". " + detail)
        }
        is TranscriptionResult.Failed -> {
            val title = VoiceApi.keyCheckTitle(ctx, r.problem)
            val detail = r.details() + " · " + ms + " ms"
            ResultBanner(false, title, detail, spoken = stringResource(R.string.voice_api_check_failed_spoken, title),
                copyText = VoiceApi.keyCheckDetails(ctx, r, ms))
        }
    }
}

/** Advanced → "Your own OpenAI key" (stage 5zh): the dollars of this month's voice and the balance estimate */
@Composable
fun OwnKeyMoneyBlock() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val locale = ctx.resources.configuration.locales[0]
    val usage = remember { VoiceApiUsage(prefs) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_own_key_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_own_key_summary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.voice_api_usage_dollars, String.format(locale, "%.2f", usage.estimatedDollars()),
            String.format(locale, "%.4f", VoiceApiBalance.pricePerMinute(prefs))))
        BalanceBlock()
    }
}

/**
 * "Remaining (estimate)" (stage 5c, docs/DESIGN.md): the amounts the user entered minus the minutes sent times the
 * price. Only on this phone, never sent anywhere, always called an estimate; the exact balance is on the OpenAI site.
 */
@Composable
fun BalanceBlock() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val balance = remember { VoiceApiBalance(prefs) }
    var version by remember { mutableStateOf(0) } // recomposes after every change
    var dialog by remember { mutableStateOf<BalanceDialog?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val locale = ctx.resources.configuration.locales[0]
    @Suppress("UNUSED_EXPRESSION") version
    val remaining = balance.remainingDollars()
    val exhausted = balance.exhausted()
    val low = balance.low()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.voice_balance_title))
        val line = if (remaining == null) stringResource(R.string.voice_balance_not_set)
            else stringResource(R.string.voice_balance_remaining, VoiceApiBalance.format(remaining.coerceAtLeast(0.0), locale))
        Text(line, color = if (exhausted || low) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
            style = MaterialTheme.typography.bodyLarge)
        when {
            exhausted -> Text(stringResource(R.string.voice_balance_exhausted), color = MaterialTheme.colorScheme.error)
            low -> Text(stringResource(R.string.voice_balance_low), color = MaterialTheme.colorScheme.error)
        }
        balance.lastEntryMs().takeIf { it > 0 }?.let {
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(it))
            Text(stringResource(R.string.voice_balance_last_entry, date), style = MaterialTheme.typography.bodySmall)
        }
        StackedButtons {
            WideButton(stringResource(R.string.voice_balance_top_up), onClick = { dialog = BalanceDialog.TOP_UP })
            WideButton(stringResource(R.string.voice_balance_set), onClick = { dialog = BalanceDialog.SET })
        }
        if (balance.isSet() || exhausted)
            TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.voice_balance_reset)) }
        Text(stringResource(R.string.voice_balance_price, String.format(locale, "%.4f", balance.pricePerMinute())),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { dialog = BalanceDialog.PRICE }) { Text(stringResource(R.string.voice_balance_price_change)) }
        Text(stringResource(R.string.voice_balance_summary), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { OpenAiSetup.openInBrowser(ctx, OpenAi.BILLING_URL) }) {
            Text(stringResource(R.string.voice_balance_open_site))
        }
        Text(stringResource(R.string.voice_balance_exact_on_site), style = MaterialTheme.typography.bodySmall)
    }
    dialog?.let { d ->
        AmountDialog(d, initial = if (d == BalanceDialog.PRICE) String.format(locale, "%.4f", balance.pricePerMinute()) else "",
            onDismiss = { dialog = null }) { text ->
            val ok = when (d) {
                BalanceDialog.TOP_UP -> VoiceApiBalance.parseAmount(text)?.let { balance.topUp(it) } != null
                BalanceDialog.SET -> VoiceApiBalance.parseAmount(text, allowZero = true)?.let { balance.setRemaining(it) } != null
                BalanceDialog.PRICE -> VoiceApiBalance.parseAmount(text, max = VoiceApiBalance.MAX_PRICE_PER_MINUTE)
                    ?.let { VoiceApiBalance.setPricePerMinute(prefs, it) } == true
            }
            if (ok) { dialog = null; version++ }
            ok
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        text = { Text(stringResource(R.string.voice_balance_reset_confirm)) },
        confirmButton = { TextButton(onClick = { balance.reset(); confirmReset = false; version++ }) {
            Text(stringResource(R.string.voice_balance_reset))
        } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private enum class BalanceDialog { TOP_UP, SET, PRICE }

/** a number in dollars; [onSave] returns false for a wrong number (the dialog stays with a red hint) */
@Composable
private fun AmountDialog(kind: BalanceDialog, initial: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(initial) }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(when (kind) {
            BalanceDialog.TOP_UP -> R.string.voice_balance_top_up_title
            BalanceDialog.SET -> R.string.voice_balance_set_title
            BalanceDialog.PRICE -> R.string.voice_balance_price_title
        })) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it; wrong = false }, singleLine = true,
                    label = { Text(stringResource(R.string.voice_balance_amount_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (wrong) Text(stringResource(R.string.voice_balance_amount_wrong), color = MaterialTheme.colorScheme.error)
                if (kind == BalanceDialog.PRICE)
                    Text(stringResource(R.string.voice_balance_price_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { if (!onSave(text)) wrong = true }) { Text(stringResource(R.string.voice_balance_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "Pause before sending" (stage 5c): how much silence after speech sends the phrase, 0.8–2.5 s, 1.5 s by default */
@Composable
private fun EndPauseSlider() {
    val prefs = LocalContext.current.prefs()
    val locale = LocalContext.current.resources.configuration.locales[0]
    var ms by remember { mutableFloatStateOf(VoiceApiPrefs.endPauseMs(prefs).toFloat()) }
    Text(stringResource(R.string.voice_api_end_pause, String.format(locale, "%.1f", ms / 1000f)))
    Slider(value = ms, onValueChange = { ms = it },
        valueRange = VoiceApiPrefs.MIN_END_PAUSE_MS.toFloat()..VoiceApiPrefs.MAX_END_PAUSE_MS.toFloat(),
        steps = (VoiceApiPrefs.MAX_END_PAUSE_MS - VoiceApiPrefs.MIN_END_PAUSE_MS) / VoiceApiPrefs.END_PAUSE_STEP_MS - 1,
        onValueChangeFinished = { VoiceApiPrefs.setEndPauseMs(prefs, ms.toInt()) })
    Text(stringResource(R.string.voice_api_end_pause_summary), style = MaterialTheme.typography.bodySmall)
}

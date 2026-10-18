// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import helium314.keyboard.latin.openai.ConnectionCheck
import helium314.keyboard.latin.openai.OpenAiSetup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiModelEval
import helium314.keyboard.latin.ai.AiModels
import helium314.keyboard.latin.ai.AiQuality
import helium314.keyboard.latin.ai.TextModels
import helium314.keyboard.latin.openai.CancelSignal
import helium314.keyboard.latin.openai.OpenAiTextProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The row "For developer ›" at the bottom of the AI screen, only in the developer mode (stage 5g-4, the owner: the
 * engineering settings are not for ordinary users). An ordinary row with an arrow that opens a screen of its own.
 */
@Composable
fun AiAdvancedSection() {
    val prefs = LocalContext.current.prefs()
    if (!helium314.keyboard.latin.ai.DeveloperMode.on(prefs)) return
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { helium314.keyboard.settings.SettingsDestination.navigateTo(helium314.keyboard.settings.SettingsDestination.AiAdvanced) }
            .testTag("ai_advanced"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.developer_summary), style = MaterialTheme.typography.bodySmall)
        }
        Text("›", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Stage 5g-5, the owner: when OpenAI did not answer (no network to it, a timeout, DNS or VPN) everybody sees a card
 * with the proxy and "Check connection to OpenAI"; when it answers they are only in the developer mode.
 */
@Composable
fun OpenAiUnreachableCard() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    if (!helium314.keyboard.latin.openai.OpenAiReach.unreachable(prefs) || helium314.keyboard.latin.ai.DeveloperMode.on(prefs)) return
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<ConnectionCheck.Result?>(null) }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().testTag("openai_unreachable"),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.openai_unreachable_title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.openai_unreachable_text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                check = null
                OpenAiSetup.checkConnection(ctx) { check = it; checking = false }
            }) { Text(stringResource(if (checking) R.string.openai_check_running else R.string.openai_check)) }
            check?.let { c -> ConnectionCheckBanner(c) }
            ProxySettings()
        }
    }
}

/** "Report a problem": until the reports of stage 5i exist it leads to the hint about the developer mode */
@Composable
fun ReportProblemRow() {
    var hint by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { hint = true }.testTag("report_problem"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.report_problem_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.report_problem_summary), style = MaterialTheme.typography.bodySmall)
        }
        Text("›", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineMedium)
    }
    if (hint) androidx.compose.material3.AlertDialog(
        onDismissRequest = { hint = false },
        text = { Text(stringResource(R.string.report_problem_hint)) },
        confirmButton = { TextButton(onClick = { hint = false }) { Text(stringResource(android.R.string.ok)) } },
    )
}

/** the screen "For developer" (only in the developer mode): the engineering items and the switch that turns the mode off */
@Composable
fun AiAdvancedScreen(onClickBack: () -> Unit) {
    helium314.keyboard.settings.SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.developer_title), settings = emptyList()) {
        androidx.compose.material3.Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding).padding(16.dp).testTag("ai_advanced_content")) {
                AiAdvancedContent(onSwitchedOff = onClickBack)
            }
        }
    }
}

@Composable
fun AiAdvancedContent(onSwitchedOff: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<ConnectionCheck.Result?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { helium314.keyboard.latin.ai.DeveloperMode.set(prefs, false); onSwitchedOff() }.testTag("developer_switch"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.developer_switch), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.developer_switch_summary), style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.Switch(checked = true, onCheckedChange = { helium314.keyboard.latin.ai.DeveloperMode.set(prefs, false); onSwitchedOff() })
        }
        HorizontalDivider()
        Preference(name = stringResource(R.string.voice_diag_title), description = stringResource(R.string.voice_diag_summary),
            onClick = { helium314.keyboard.settings.SettingsDestination.navigateTo(helium314.keyboard.settings.SettingsDestination.VoiceDiagnostics) })
        HorizontalDivider()
        MicCheckBlock()
        HorizontalDivider()
        // stage 5g-21: how long the car's link stays warm after a phrase from the phone
        if (helium314.keyboard.latin.voiceapi.VoiceApiPrefs.isAvailable()) {
            var warmS by remember { mutableStateOf(prefs.getInt(helium314.keyboard.latin.voice.CarWarmLink.PREF, helium314.keyboard.latin.voice.CarWarmLink.DEFAULT_S)) }
            Column {
                Text(stringResource(R.string.car_warm_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.car_warm_summary), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (sec in helium314.keyboard.latin.voice.CarWarmLink.CHOICES_S) {
                        androidx.compose.material3.RadioButton(selected = warmS == sec, onClick = {
                            warmS = sec; prefs.edit().putInt(helium314.keyboard.latin.voice.CarWarmLink.PREF, sec).apply()
                        }, modifier = Modifier.testTag("car_warm_$sec"))
                        Text(if (sec == 0) stringResource(R.string.car_warm_off) else stringResource(R.string.car_warm_seconds, sec))
                    }
                }
            }
            HorizontalDivider()
        }
        // stage 5g-21: the probe "mute the radio with a call" (on by default, only with a car-like device)
        if (helium314.keyboard.latin.voiceapi.VoiceApiPrefs.isAvailable()) {
            var mute by remember { mutableStateOf(helium314.keyboard.latin.voice.CarMuteCall.enabled(prefs)) }
            Row(Modifier.fillMaxWidth().clickable { mute = !mute; prefs.edit().putBoolean(helium314.keyboard.latin.voice.CarMuteCall.PREF, mute).apply() },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.car_mute_call_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.car_mute_call_summary), style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(checked = mute, onCheckedChange = { mute = it; prefs.edit().putBoolean(helium314.keyboard.latin.voice.CarMuteCall.PREF, it).apply() })
            }
            HorizontalDivider()
        }
        // stage 5g-20: the probe of 5g-18, only on demand (another phone)
        if (helium314.keyboard.latin.voiceapi.VoiceApiPrefs.isAvailable()) {
            var probe by remember { mutableStateOf(prefs.getBoolean(helium314.keyboard.latin.voiceapi.PhoneProbe.PREF, false)) }
            Row(Modifier.fillMaxWidth().clickable { probe = !probe; prefs.edit().putBoolean(helium314.keyboard.latin.voiceapi.PhoneProbe.PREF, probe).apply() },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.developer_probe_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.developer_probe_summary), style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(checked = probe, onCheckedChange = { probe = it; prefs.edit().putBoolean(helium314.keyboard.latin.voiceapi.PhoneProbe.PREF, it).apply() })
            }
            HorizontalDivider()
        }
        if (helium314.keyboard.latin.voiceapi.VoiceApiPrefs.isAvailable()) { OwnKeyMoneyBlock(); HorizontalDivider() }
        AboutVoiceAndAi()
        ModelDetails()
        HorizontalDivider()
        Text(stringResource(R.string.ai_section_connection), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        ProxySettings()
        // works without a key: checks the route of this app to OpenAI (VPN, DNS, certificates, proxy)
        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            check = null
            OpenAiSetup.checkConnection(ctx) { check = it; checking = false }
        }) { Text(stringResource(if (checking) R.string.openai_check_running else R.string.openai_check)) }
        check?.let { c -> ConnectionCheckBanner(c) }
    }
}

/**
 * "About voice and AI" (stage 5zh, item 7): read only, which models do the work: speech recognition (gpt-transcribe)
 * and the text model chosen automatically. Nothing to choose here, no prices.
 */
@Composable
fun AboutVoiceAndAi() {
    val ctx = LocalContext.current
    val model = remember { TextModels(ctx.prefs()).current()?.id }
    Column(Modifier.testTag("ai_about"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.ai_about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_about_voice, helium314.keyboard.latin.openai.OpenAi.TRANSCRIBE_MODEL))
        Text(model?.let { stringResource(R.string.ai_about_text, it) } ?: stringResource(R.string.ai_about_text_unknown))
    }
}

/** "AI quality": Economy (default) or Better; no model names, no prices */
@Composable
fun AiQualityBlock() {
    val prefs = LocalContext.current.prefs()
    var quality by remember { mutableStateOf(AiQuality.read(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.ai_quality_title), style = MaterialTheme.typography.titleMedium)
        AiQuality.entries.forEach { q ->
            fun choose() { AiQuality.save(prefs, q); quality = q }
            Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = q == quality, onClick = { choose() })
                Text(stringResource(if (q == AiQuality.ECONOMY) R.string.ai_quality_economy else R.string.ai_quality_better))
            }
        }
        Text(stringResource(R.string.ai_quality_summary), style = MaterialTheme.typography.bodySmall)
    }
}

/** "Details": the model chosen automatically and the candidates with their estimated price; for diagnostics only */
@Composable
private fun ModelDetails() {
    val ctx = LocalContext.current
    val models = remember { TextModels(ctx.prefs()) }
    var shown by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { shown = !shown }) { Text(stringResource(R.string.ai_details)) }
    if (!shown) return
    @Suppress("UNUSED_EXPRESSION") version
    Text(modelDetails(models, ctx.resources.configuration.locales[0]), style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace)
    if (failed) Text(stringResource(R.string.ai_model_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !loading, onClick = {
        loading = true; failed = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { OpenAiTextProvider.get(ctx).models(refresh = true) }
            loading = false
            if (r is AiModels.Ok) version++ else failed = true
        }
    }) { Text(stringResource(if (loading) R.string.openai_check_running else R.string.ai_model_refresh)) }
    ModelComparison(models)
}

/**
 * "Compare models on 20 phrases" (stage 5zh, docs/AI.md section 7): fixed test phrases with each candidate model and the
 * user's own key, a report to copy. Never text of the user. Leaving the screen stops it.
 */
@Composable
private fun ModelComparison(models: TextModels) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    val cancel = remember { mutableStateOf(CancelSignal()) }
    DisposableEffect(Unit) { onDispose { cancel.value.cancel() } }
    val candidates = AiModelEval.candidates(models.cached()?.map { it.id }.orEmpty())
    if (candidates.isEmpty()) return
    val requests = candidates.size * AiModelEval.CASES.size
    Text(stringResource(R.string.ai_compare_summary, AiModelEval.CASES.size, requests), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !running, onClick = {
        running = true; report = null
        val signal = CancelSignal().also { cancel.value = it }
        val provider = OpenAiTextProvider.get(ctx)
        val eval = AiModelEval({ r, c -> provider.respond(r, c) { } })
        Thread({
            val results = candidates.map { m -> eval.runModel(m, signal) { done, total -> progress = "$m: $done / $total" } }
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(java.util.Date())
            report = AiModelEval.report(results, date)
            running = false; progress = ""
        }, "aikb-model-eval").start()
    }) { Text(stringResource(R.string.ai_compare)) }
    if (running) Text(progress, style = MaterialTheme.typography.bodySmall)
    report?.let { r ->
        Text(r.lineSequence().take(candidates.size + 1).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("AI Keyboard", r))
        }) { Text(stringResource(R.string.ai_compare_copy)) }
    }
}

/** technical text of "Details": quality, the current model, the candidates with "≈ N $ / 1000 requests" */
fun modelDetails(models: TextModels, locale: Locale): String {
    val quality = models.quality()
    val ids = models.cached()?.map { it.id } ?: return "quality: ${quality.value}\nmodels: not loaded yet"
    val gone = models.unavailable()
    val lines = mutableListOf("quality: ${quality.value}", "text model now: ${models.current()?.id ?: "none"} (automatic)")
    TextModels.order(ids, quality).forEach { id ->
        val price = TextModels.typicalCost(id)?.let { "≈" + String.format(locale, "%.2f", it / 1000.0) + " $ / 1000" } ?: "price unknown"
        lines.add("· $id, $price" + if (id in gone) ", not available (404)" else "")
    }
    return lines.joinToString("\n")
}

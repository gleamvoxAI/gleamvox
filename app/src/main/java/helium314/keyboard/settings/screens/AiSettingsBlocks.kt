// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiPrefs
import helium314.keyboard.latin.ai.AiUsage
import helium314.keyboard.latin.ai.CleanupLevel
import helium314.keyboard.latin.ai.LongPressCommand
import helium314.keyboard.latin.openai.OpenAi
import helium314.keyboard.latin.openai.OpenAiSetup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "✨ Assistant": what the ✨ button and the cleanup of dictation use (docs/AI.md) */
@Composable
fun AssistantSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextConsentBlock()
        LongPressBlock()
        CleanupLevelBlock()
        TranslateLanguageBlock()
        AiUsageBlock()
    }
}

/** "Long press on ✨": which command runs at once ("Fix" by default) and whether a preview is shown (off by default) */
@Composable
fun LongPressBlock() {
    val prefs = LocalContext.current.prefs()
    var choice by remember { mutableStateOf(AiPrefs.longPressCommand(prefs)) }
    var preview by remember { mutableStateOf(AiPrefs.longPressPreview(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.ai_long_press_title), style = MaterialTheme.typography.titleMedium)
        LongPressCommand.entries.forEach { c ->
            fun choose() { AiPrefs.setLongPressCommand(prefs, c); choice = c }
            Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = c == choice, onClick = { choose() })
                Text(stringResource(when (c) {
                    LongPressCommand.FIX -> R.string.ai_cmd_fix
                    LongPressCommand.REWRITE -> R.string.ai_cmd_rewrite
                    LongPressCommand.SHORTEN -> R.string.ai_cmd_shorten
                    LongPressCommand.CONTINUE -> R.string.ai_cmd_continue
                    LongPressCommand.TRANSLATE_LAYOUT -> R.string.ai_long_press_translate_layout
                    LongPressCommand.TRANSLATE_SECOND -> R.string.ai_long_press_translate_second
                }))
            }
        }
        Text(stringResource(R.string.ai_long_press_summary), style = MaterialTheme.typography.bodySmall)
        fun toggle(on: Boolean) { AiPrefs.setLongPressPreview(prefs, on); preview = on }
        Row(Modifier.fillMaxWidth().clickable { toggle(!preview) }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_long_press_preview), Modifier.weight(1f))
            Switch(checked = preview, onCheckedChange = { toggle(it) })
        }
    }
}

/** "Cleanup of dictation": off / light / full, full by default (docs/DESIGN.md, stage 5d) */
@Composable
fun CleanupLevelBlock() {
    val prefs = LocalContext.current.prefs()
    var level by remember { mutableStateOf(CleanupLevel.read(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.ai_cleanup_title), style = MaterialTheme.typography.titleMedium)
        CleanupLevel.entries.forEach { l ->
            fun choose() { CleanupLevel.save(prefs, l); level = l }
            Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = l == level, onClick = { choose() })
                Text(stringResource(when (l) {
                    CleanupLevel.OFF -> R.string.ai_cleanup_off
                    CleanupLevel.LIGHT -> R.string.ai_cleanup_light
                    CleanupLevel.FULL -> R.string.ai_cleanup_full
                }))
            }
        }
        Text(stringResource(R.string.ai_cleanup_summary), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The consent for text requests with the own key (docs/AI.md, section 4): without it ✨ and the cleanup send nothing.
 * Asked again after stage 5d, the former ChatGPT consent does not count. [onChange] gets the current state.
 */
@Composable
fun TextConsentBlock(onChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var consent by remember { mutableStateOf(AiPrefs.textConsent(prefs)) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(consent) { onChange(consent) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_text_summary), style = MaterialTheme.typography.bodySmall)
        if (consent) {
            Text(stringResource(R.string.ai_text_on), color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { AiPrefs.withdrawTextConsent(prefs); consent = false }) { Text(stringResource(R.string.ai_text_consent_withdraw)) }
        } else {
            Text(stringResource(R.string.ai_text_off), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { asking = true }) { Text(stringResource(R.string.ai_text_enable)) }
        }
    }
    if (asking) TextConsentDialog(onDismiss = { asking = false }, onAccept = {
        AiPrefs.giveTextConsent(prefs)
        consent = true
        asking = false
    })
}

/** what is sent, who pays, links to the OpenAI policy; nothing changes without [onAccept] */
@Composable
fun TextConsentDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_text_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ai_text_consent_text))
                Text(stringResource(R.string.ai_text_consent_what), style = MaterialTheme.typography.bodySmall)
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

/** requests sent today and the daily ceiling; counted only on this phone */
@Composable
fun AiUsageBlock() {
    val ctx = LocalContext.current
    val usage = remember { AiUsage(ctx.prefs()) }
    var cap by remember { mutableFloatStateOf(usage.cap().toFloat()) }
    val count = remember { usage.todayCount() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_usage_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_usage_today, count, cap.toInt()))
        Text(stringResource(R.string.ai_cleanup_count, usage.todayCleanups()))
        Slider(value = cap, onValueChange = { cap = it }, valueRange = 0f..AiUsage.MAX_CAP.toFloat(), steps = AiUsage.MAX_CAP / 10 - 1,
            onValueChangeFinished = { usage.setCap(cap.toInt()) })
        Text(stringResource(R.string.ai_usage_summary), style = MaterialTheme.typography.bodySmall)
    }
}

/** apps where the ✨ button is hidden and nothing is sent; empty by default, added by choosing an installed app */
@Composable
fun ExcludedAppsBlock() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var excluded by remember { mutableStateOf(AiPrefs.excludedApps(prefs).sorted()) }
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_excluded_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_excluded_summary), style = MaterialTheme.typography.bodySmall)
        if (excluded.isEmpty()) Text(stringResource(R.string.ai_excluded_none), style = MaterialTheme.typography.bodySmall)
        excluded.forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appLabel(ctx, pkg) ?: pkg)
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { AiPrefs.removeExcluded(prefs, pkg); excluded = AiPrefs.excludedApps(prefs).sorted() }) {
                    Text(stringResource(R.string.ai_excluded_remove))
                }
            }
        }
        OutlinedButton(onClick = { choosing = true }) { Text(stringResource(R.string.ai_excluded_add)) }
    }
    if (choosing) AppChooserDialog(skip = excluded.toSet(), onDismiss = { choosing = false }) { pkg ->
        choosing = false
        AiPrefs.addExcluded(prefs, pkg)
        excluded = AiPrefs.excludedApps(prefs).sorted()
    }
}

private fun appLabel(ctx: Context, pkg: String): String? = try {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { null }

/** installed apps with an icon on the home screen (sorted by name) */
fun launcherApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .map { (pkg, label) -> label to pkg }
        .sortedBy { it.first.lowercase() }
}

@Composable
private fun AppChooserDialog(skip: Set<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launcherApps(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_excluded_add)) },
        text = {
            Column {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_excluded_search)) })
                val shown = apps.orEmpty().filter { (label, pkg) -> pkg !in skip &&
                    (filter.isBlank() || label.contains(filter, true) || pkg.contains(filter, true)) }
                if (apps == null) Text(stringResource(R.string.openai_check_running))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { (label, pkg) ->
                        Column(Modifier.fillMaxWidth().clickable { onChoose(pkg) }.padding(vertical = 8.dp)) {
                            Text(label)
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "Delete all": the API key and all AI settings and counters, after a confirmation */
@Composable
fun EraseEverythingButton(onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val doneText = stringResource(R.string.ai_erase_done)
    OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.ai_erase), color = MaterialTheme.colorScheme.error) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text(stringResource(R.string.ai_erase_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirm = false
            OpenAiSetup.eraseEverything(ctx) { onDone(doneText) }
        }) { Text(stringResource(R.string.ai_erase)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** the second language of "Translate → …" in the ✨ panel (the first one is the language of the active layout) */
@Composable
fun TranslateLanguageBlock() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val phone = ctx.resources.configuration.locales[0]
    var chosen by remember { mutableStateOf(prefs.getString(AiPrefs.PREF_TRANSLATE_TO, null)) }
    var open by remember { mutableStateOf(false) }
    fun name(tag: String) = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_translate_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_translate_summary), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open = true }) {
            Text(name(helium314.keyboard.latin.ai.AiCommands.secondLanguage(chosen, phone.language)))
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ai_translate_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(helium314.keyboard.latin.ai.AiCommands.COMMON_LANGUAGES) { tag ->
                    Text(name(tag), Modifier.fillMaxWidth().clickable {
                        prefs.edit().putString(AiPrefs.PREF_TRANSLATE_TO, tag).apply()
                        chosen = tag
                        open = false
                    }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

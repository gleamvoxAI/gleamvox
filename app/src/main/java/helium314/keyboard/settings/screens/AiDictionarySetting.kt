// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ailm.AiDictDownloader
import helium314.keyboard.latin.ailm.AiDictDownloads
import helium314.keyboard.latin.ailm.AiDictOffer
import helium314.keyboard.latin.ailm.AiDictModels
import helium314.keyboard.latin.ailm.AiDictionary
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.Preference

/**
 * "Download" pressed: on Android 13+ the notification permission is asked right now (the progress is in a notification),
 * then the download starts; it starts also when the person refuses (it works, only without the notification).
 */
@Composable
fun rememberAiDictStarter(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AiDictDownloads.start(ctx) }
    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else AiDictDownloads.start(ctx)
    }
}

/** the step of the first start wizard (ai-dict-2); "Later" is the answer "Not now": the wizard does not ask again */
@Composable
fun AiDictWizardPage(onDone: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val start = rememberAiDictStarter()
    val size = mb(AiDictModels.current.totalBytes)
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.ai_dict_wizard_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.ai_dict_wizard_text, size), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { prefs.edit { putString(AiDictionary.PREF_OFFER, "accepted") }; start(); onDone() }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ai_dict_download, size))
        }
        TextButton(onClick = { AiDictOffer.decline(prefs); onDone() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ai_dict_later)) }
    }
}

private fun mb(bytes: Long) = (bytes + 500_000) / 1_000_000

/** the one-time offer; shown by TextCorrectionScreen until answered */
@Composable
fun AiDictionaryOffer() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val start = rememberAiDictStarter()
    var show by remember { mutableStateOf(AiDictionary.inBuild() && !AiDictionary.isDownloaded(ctx) && AikbNetwork.internetAllowed(ctx)
            && AiDictOffer.unanswered(prefs)) }
    if (!show) return
    ConfirmationDialog(
        onDismissRequest = { show = false; AiDictOffer.decline(prefs) },
        onConfirmed = { show = false; prefs.edit { putString(AiDictionary.PREF_OFFER, "accepted") }; start() },
        title = { Text(stringResource(R.string.ai_dict_offer_title)) },
        content = { Text(stringResource(R.string.ai_dict_offer_text, mb(AiDictModels.current.totalBytes))) },
        confirmButtonText = stringResource(R.string.ai_dict_download, mb(AiDictModels.current.totalBytes)),
        cancelButtonText = stringResource(R.string.ai_dict_not_now),
    )
}

fun createAiDictionarySetting(context: Context) = Setting(context, SettingsWithoutKey.AI_DICTIONARY, R.string.ai_dict_title) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val start = rememberAiDictStarter()
    val state by AiDictDownloads.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(AiDictionary.isOn(prefs)) }
    val model = AiDictModels.current
    val downloaded = AiDictionary.isDownloaded(ctx)
    val description = when (val s = state) {
        is AiDictDownloads.State.Running -> stringResource(R.string.ai_dict_state_downloading, mb(s.done), mb(s.total))
        is AiDictDownloads.State.Failed -> if (s.message.isEmpty()) stringResource(R.string.ai_dict_state_cancelled)
            else stringResource(R.string.ai_dict_state_failed, s.message)
        else -> if (!downloaded) stringResource(R.string.ai_dict_state_none, mb(model.totalBytes))
            else if (on) stringResource(R.string.ai_dict_state_on, model.title) else stringResource(R.string.ai_dict_state_off, model.title)
    }
    Column {
        Preference(name = it.title, description = description, onClick = { dialog = true })
        (state as? AiDictDownloads.State.Running)?.let { s ->
            LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f })
        }
    }
    if (!dialog) return@Setting
    val running = state is AiDictDownloads.State.Running
    when {
        running -> ConfirmationDialog(
            onDismissRequest = { dialog = false },
            onConfirmed = { AiDictDownloads.cancel() },
            content = { Text(stringResource(R.string.ai_dict_running_text)) },
            confirmButtonText = stringResource(R.string.ai_dict_stop),
            cancelButtonText = stringResource(android.R.string.ok),
        )
        !downloaded -> {
            val internet = AikbNetwork.internetAllowed(ctx)
            val metered = remember { AiDictDownloader(ctx).isMetered() }
            ConfirmationDialog(
                onDismissRequest = { dialog = false },
                onConfirmed = { if (internet) start() },
                title = { Text(stringResource(R.string.ai_dict_title)) },
                content = { Text(stringResource(R.string.ai_dict_about, mb(model.totalBytes), model.title, model.license) +
                    (if (!internet) "\n\n" + stringResource(R.string.ai_dict_no_internet)
                     else if (metered) "\n\n" + stringResource(R.string.ai_dict_metered) else "")) },
                confirmButtonText = if (internet) stringResource(R.string.ai_dict_download, mb(model.totalBytes)) else stringResource(android.R.string.ok),
            )
        }
        else -> ConfirmationDialog(
            onDismissRequest = { dialog = false },
            onConfirmed = { val v = !on; prefs.edit { putBoolean(AiDictionary.PREF_ON, v) }; on = v; if (!v) AiDictionary.release() },
            title = { Text(stringResource(R.string.ai_dict_title)) },
            content = {
                // ai-dict-3: the counters can be reset, so the numbers of a few new phrases can be read
                var resets by remember { mutableStateOf(0) }
                Column {
                Text(stringResource(R.string.ai_dict_downloaded, model.title, mb(model.totalBytes), model.license) +
                "\n\n" + (if (resets >= 0) AiDictionary.Journal.summary() else "") +
                "\n\n" + helium314.keyboard.latin.ailm.EraseStats.summary() +
                "\n\n" + helium314.keyboard.latin.WordLearning.summary(prefs, helium314.keyboard.latin.PersonalWords.size()) +
                (AiDictionary.loadError?.let { e -> "\n\n" + stringResource(R.string.ai_dict_load_error, e) } ?: ""))
                androidx.compose.material3.TextButton(onClick = { AiDictionary.Journal.reset(prefs); resets++ }) {
                    Text(stringResource(R.string.ai_dict_reset_journal))
                }
                }
            },
            confirmButtonText = stringResource(if (on) R.string.ai_dict_switch_off else R.string.ai_dict_switch_on),
            neutralButtonText = stringResource(R.string.ai_dict_delete),
            onNeutral = { AiDictDownloads.delete(ctx) },
        )
    }
}

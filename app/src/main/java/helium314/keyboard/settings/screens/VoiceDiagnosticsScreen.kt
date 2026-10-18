// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceAttempt
import helium314.keyboard.latin.voice.VoiceJournal
import helium314.keyboard.latin.voiceapi.VoiceApiPrefs
import helium314.keyboard.settings.SearchSettingsScreen

/**
 * Advanced → "Voice diagnostics" (stage 5zh, item 6): the last 30 dictations from the voice journal, newest first, with
 * "Copy details". Only steps, codes, times and the sound level; never text or sound.
 */
@Composable
fun VoiceDiagnosticsScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.voice_diag_title), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding).padding(16.dp)) {
                VoiceDiagnostics()
            }
        }
    }
}

@Composable
fun VoiceDiagnostics() {
    val ctx = LocalContext.current
    val journal = remember { VoiceJournal.get(ctx) }
    var entries by remember { mutableStateOf(journal.entries()) }
    var silentMics by remember { mutableStateOf(helium314.keyboard.latin.voiceapi.SilentMics.get(ctx).count()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_diag_hint), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.voice_diag_summary), style = MaterialTheme.typography.bodySmall)
        var copied by remember { mutableStateOf(false) }
        var confirmClear by remember { mutableStateOf(false) }
        val copiedToast = stringResource(R.string.voice_diag_copied_toast)
        androidx.compose.runtime.LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }
        StackedButtons {
            // the main button: filled, the whole width, first (stage 5g-4)
            androidx.compose.material3.Button(
                onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("AI Keyboard", journal.text(diagnosticsHeader(ctx)) + crashesText(ctx)))
                    android.widget.Toast.makeText(ctx, copiedToast, android.widget.Toast.LENGTH_SHORT).show()
                    copied = true
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = MIN_BUTTON_HEIGHT).testTag("copy_journal"),
            ) { Text(stringResource(if (copied) R.string.voice_diag_copied_button else R.string.voice_diag_copy)) }
            WideButton(stringResource(R.string.voice_diag_clear), onClick = { confirmClear = true })
            // stage 5g-3: devices whose microphone gave no sound are skipped for good; this forgets them
            if (silentMics > 0) WideButton(stringResource(R.string.voice_diag_forget_mics, silentMics), onClick = {
                helium314.keyboard.latin.voiceapi.SilentMics.get(ctx).clear(); silentMics = 0
            })
        }
        if (confirmClear) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.voice_diag_clear_confirm)) },
            confirmButton = { androidx.compose.material3.TextButton(modifier = Modifier.testTag("confirm_clear"), onClick = { confirmClear = false; journal.clear(); entries = emptyList() }) {
                Text(stringResource(R.string.voice_diag_clear)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
        // stage 5g-5: the last crashes of the app (classes and frames, no text), a step towards the reports of 5i
        val crashes = remember { helium314.keyboard.latin.CrashLog.get(ctx).entries() }
        if (crashes.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.voice_diag_crashes, crashes.size), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("crashes"))
            crashes.forEach { Text(it.lineSequence().take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        if (entries.isEmpty()) Text(stringResource(R.string.voice_diag_empty))
        entries.forEach { a ->
            HorizontalDivider()
            Text(resultText(a), style = MaterialTheme.typography.titleSmall,
                color = if (a.result() == VoiceAttempt.Result.INSERTED) MaterialTheme.colorScheme.primary
                    else if (a.result() == VoiceAttempt.Result.CANCELLED) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error)
            Text(a.line(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** the first line of an entry in plain words */
@Composable
private fun resultText(a: VoiceAttempt): String = when (a.result()) {
    VoiceAttempt.Result.INSERTED -> a.words?.let { pluralStringResource(R.plurals.voice_done_words, it, it) } ?: stringResource(R.string.voice_diag_inserted)
    VoiceAttempt.Result.WAITING -> stringResource(R.string.voice_diag_waiting)
    VoiceAttempt.Result.CANCELLED -> stringResource(R.string.voice_diag_cancelled)
    VoiceAttempt.Result.NOT_STARTED -> stringResource(R.string.voice_diag_not_started) + ": " + (a.notStarted ?: "")
    VoiceAttempt.Result.FAILED -> stringResource(R.string.voice_diag_failed) + (a.error?.let { ": $it" } ?: "")
}

/** "Last crashes" for the copy of the journal (stage 5g-5); empty when there were none */
fun crashesText(ctx: Context): String {
    val crashes = helium314.keyboard.latin.CrashLog.get(ctx).entries()
    if (crashes.isEmpty()) return ""
    return "\n\nLast crashes (${crashes.size}):\n" + crashes.joinToString("\n\n")
}

/** version, Android, the switches of voice; never the key, not even a part of it */
fun diagnosticsHeader(ctx: Context): String {
    val prefs = ctx.prefs()
    return listOf(
        "AI Keyboard: voice journal",
        "version: ${BuildConfig.VERSION_NAME}",
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        "speech recognition: ${VoiceApiPrefs.engine(prefs).value}, consent: ${if (VoiceApiPrefs.consent(prefs)) "yes" else "no"}",
        "internet switch: ${if (AikbNetwork.internetAllowed(ctx)) "on" else "off"}, key rejected: ${if (VoiceApiPrefs.keyRejected(prefs)) "yes" else "no"}",
        "pause before sending: ${VoiceApiPrefs.endPauseMs(prefs)} ms, microphone source: ${helium314.keyboard.latin.voiceapi.Loudness.name(VoiceApiPrefs.audioSource(prefs))}",
        // stage 5g-16: the last decision about the fresh screenshot (numbers and words only, never a name or a path)
        prefs.getString(helium314.keyboard.latin.FreshScreenshot.PREF_LAST_LOG, null) ?: "screenshot: not asked yet",
    ).joinToString("\n")
}

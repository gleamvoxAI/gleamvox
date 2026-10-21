// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiConsent
import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.openai.AikbNetwork
import helium314.keyboard.latin.openai.ConnectionCheck
import helium314.keyboard.latin.openai.OpenAiProxy
import helium314.keyboard.latin.openai.OpenAiSetup
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SwitchPreference

/**
 * The "AI" screen (docs/AI.md): everything of the AI functions with the user's own OpenAI API key in one place.
 * Internet switch, the key (paste, check, replace, delete), voice, ✨ with the cleanup of dictation, excluded apps,
 * proxy, checks of the connection and the microphone, "Delete all". Texts follow the phone language.
 */
@Composable
fun AiScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    // after "Delete all" or a change of the key the blocks below read their state again
    var reload by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<ConnectionCheck.Result?>(null) }
    // opened by the dimmed ✨ (stage 5zh): straight to what is missing
    val target = remember { AiScreenTarget.take() }
    var consentDialog by remember { mutableStateOf(target == AiGuard.SettingsTarget.CONSENT) }
    if (consentDialog) helium314.keyboard.settings.features.UnifiedConsentDialog(onDismiss = { consentDialog = false }, onAccept = {
        AiConsent.giveAll(ctx.prefs())
        consentDialog = false
        reload++
    })

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.ai_title), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                if (target == AiGuard.SettingsTarget.INTERNET) TargetHint(R.string.ai_target_internet)
                key(reload) { InternetSwitch() }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ai_screen_intro), style = MaterialTheme.typography.bodyMedium)
                    key(reload) { OpenAiUnreachableCard() }
                    key(reload) {
                        SectionTitle(R.string.ai_section_key, "ai_section_key")
                        if (target == AiGuard.SettingsTarget.KEY) TargetHint(R.string.ai_target_key)
                        ApiKeySection(onKeyChanged = { reload++ })
                        SectionTitle(R.string.ai_section_voice, "ai_section_voice")
                        VoiceSection()
                        SectionTitle(R.string.ai_section_assistant, "ai_section_assistant")
                        AssistantSection()
                        SectionTitle(R.string.ai_excluded_title, "ai_section_excluded")
                        ExcludedAppsBlock()
                    }
                    // stage 5g-4: quality for everybody; the engineering items only in the developer mode, on their own screen
                    HorizontalDivider()
                    // stage 5g-16: the switch is also here, where it is easy to find
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && helium314.keyboard.latin.FreshScreenshot.inBuild()) ScreenshotSwitch()
                    CarModeBlock() // ux-11: "In the car" for everybody
                    AiQualityBlock()
                    ReportProblemRow()
                    AiAdvancedSection()
                    HorizontalDivider()
                    // the protection mode of the key is engineering: only in the developer mode (stage 5g-4)
                    if (helium314.keyboard.latin.ai.DeveloperMode.on(ctx.prefs()))
                        key(reload) { OpenAiSetup.keyProtectionText(ctx)?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    EraseEverythingButton { text -> message = text; reload++ }
                    message?.let { Text(it) }
                }
            }
        }
    }
}

/** why the dimmed ✨ brought the user here, in plain words */
@Composable
private fun TargetHint(res: Int) {
    Text(stringResource(res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai_target_hint"))
}

@Composable
private fun SectionTitle(res: Int, tag: String) {
    HorizontalDivider()
    Text(stringResource(res), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(tag))
}

/** different text for on and off */
fun internetSummaryRes(on: Boolean) = if (on) R.string.openai_internet_allowed_summary_on else R.string.openai_internet_allowed_summary_off

/** "Allow internet access", off by default; the text below says what the current position means */
@Composable
fun InternetSwitch() {
    val ctx = LocalContext.current
    val changes = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    changes?.value // recompose when a setting changes
    val on = ctx.prefs().getBoolean(AikbNetwork.PREF_INTERNET_ALLOWED, false)
    SwitchPreference(
        name = stringResource(R.string.openai_internet_allowed),
        key = AikbNetwork.PREF_INTERNET_ALLOWED,
        default = false,
        description = stringResource(internetSummaryRes(on)),
    )
}

/** optional "Proxy for OpenAI requests": only for the requests to OpenAI, only a proxy on this phone */
@Composable
fun ProxySettings() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val saved = remember { OpenAiProxy.read(prefs) }
    var type by remember { mutableStateOf(saved.type) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var problem by remember { mutableStateOf<OpenAiProxy.Problem?>(null) }
    var savedNote by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.openai_proxy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.openai_proxy_summary), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenAiProxy.Type.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t; savedNote = false }, label = {
                    Text(when (t) {
                        OpenAiProxy.Type.NONE -> stringResource(R.string.openai_proxy_none)
                        OpenAiProxy.Type.SOCKS5 -> "SOCKS5"
                        OpenAiProxy.Type.HTTP -> "HTTP"
                    })
                })
            }
        }
        if (type != OpenAiProxy.Type.NONE) {
            OutlinedTextField(value = host, onValueChange = { host = it; savedNote = false }, singleLine = true,
                label = { Text(stringResource(R.string.openai_proxy_host)) },
                supportingText = { Text(stringResource(R.string.openai_proxy_host_hint)) })
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); savedNote = false }, singleLine = true,
                label = { Text(stringResource(R.string.openai_proxy_port)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }
        problem?.let {
            Text(stringResource(if (it == OpenAiProxy.Problem.HOST_NOT_LOCAL) R.string.openai_proxy_error_host else R.string.openai_proxy_error_port),
                color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = {
            problem = OpenAiProxy.save(prefs, type, host, port.toIntOrNull())
            savedNote = problem == null
        }) { Text(stringResource(R.string.openai_proxy_save)) }
        if (savedNote) Text(stringResource(R.string.openai_proxy_saved), color = MaterialTheme.colorScheme.primary)
    }
}

/** result of "Check connection with OpenAI" as a banner (stage 5b): green "OK, N ms" or red with step and cause */
@Composable
fun ConnectionCheckBanner(c: ConnectionCheck.Result) {
    val ctx = LocalContext.current
    if (c.ok) {
        val title = stringResource(R.string.openai_check_ok_title, c.elapsedMs)
        val route = listOfNotNull(c.tls, c.route).joinToString(", ")
        val detail = stringResource(R.string.openai_check_ok_detail) + if (route.isEmpty()) "" else " ($route)"
        ResultBanner(true, title, detail, spoken = stringResource(R.string.openai_check_ok_spoken, c.elapsedMs))
        return
    }
    val title = stringResource(R.string.openai_check_failed)
    val d = c.diagnostic
    val cause = d?.let { stringResource(R.string.openai_step_check) + ": " + OpenAiSetup.causeText(ctx, it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ResultBanner(false, title, cause, spoken = title + (cause?.let { " $it" } ?: ""),
            copyText = d?.let { OpenAiSetup.details(ctx, it) })
        // only system certificates are trusted: say so instead of weakening it quietly
        if (d?.looksLikeCertificateProblem == true)
            Text(stringResource(R.string.openai_certificate_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.openai_proxy_suggestion), style = MaterialTheme.typography.bodySmall)
    }
}

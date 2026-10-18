// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.features.AikbFeatures
import helium314.keyboard.settings.features.FeaturesPage
import helium314.keyboard.settings.features.WizardSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val prefs = ctx.prefs()
    // AI Keyboard: after enabling and selecting the keyboard comes the "AI Keyboard features" screen (WizardSteps)
    // ai-dict-2: one step "AI dictionary" after the features, if it can be offered (not answered, not downloaded, internet allowed)
    fun aiDictOffer() = helium314.keyboard.latin.ailm.AiDictOffer.wizardStep(
        inBuild = helium314.keyboard.latin.ailm.AiDictionary.inBuild(),
        downloaded = helium314.keyboard.latin.ailm.AiDictionary.isDownloaded(ctx),
        unanswered = helium314.keyboard.latin.ailm.AiDictOffer.unanswered(prefs),
        internetAllowed = helium314.keyboard.latin.openai.AikbNetwork.internetAllowed(ctx),
    )
    fun determineStep(): Int = WizardSteps.determine(
        enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm),
        current = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm),
        featuresSeen = AikbFeatures.unseen(prefs).isEmpty(),
        aiDictOffer = aiDictOffer(),
    )
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope { Dispatchers.IO }
    LaunchedEffect(step) {
        if (step == WizardSteps.SELECT)
            scope.launch {
                while (step == WizardSteps.SELECT && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = determineStep()
            }
    }
    if (step == WizardSteps.FEATURES && helium314.keyboard.latin.openai.OpenAiPrefs.isAvailable()) {
        // stage 5zh: one page "Turn on AI" (microphone, contacts if ticked, internet, the single consent, the key)
        helium314.keyboard.settings.features.EnableAiPage {
            AikbFeatures.markSeen(prefs, AikbFeatures.available())
            step = WizardSteps.afterFeatures(aiDictOffer())
        }
        return
    }
    if (step == WizardSteps.AI_DICT) {
        helium314.keyboard.settings.screens.AiDictWizardPage { step = WizardSteps.DONE }
        return
    }
    if (step == WizardSteps.FEATURES) {
        val features = AikbFeatures.available()
        FeaturesPage(features, stringResource(R.string.features_next), onOpenAi = {
            // the key is pasted on the AI screen: the wizard ends there
            AikbFeatures.markSeen(prefs, features)
            close()
            SettingsDestination.navigateTo(SettingsDestination.Ai)
        }) {
            AikbFeatures.markSeen(prefs, features)
            step = WizardSteps.afterFeatures(aiDictOffer())
        }
        return
    }
    val useWideLayout = isWideScreen()
    val stepBackgroundColor = Color(ContextCompat.getColor(ctx, R.color.setup_step_background))
    val textColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_action))
    val textColorDim = textColor.copy(alpha = 0.5f)
    val titleColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_title))
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        val resource = if (step == WizardSteps.WELCOME) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 36.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = titleColor,
            )
            if (JniUtils.sHaveGestureLib)
                Text(
                    stringResource(R.string.setup_welcome_additional_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                    color = titleColor,
                    modifier = Modifier.fillMaxWidth()
                )
        }
    }
    @Composable
    fun ColumnScope.Step(step: Int, title: String, instruction: String, actionText: String, icon: Painter, action: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", color = if (step == WizardSteps.ENABLE) titleColor else textColorDim)
            Text("2", color = if (step == WizardSteps.SELECT) titleColor else textColorDim)
            Text("3", color = if (step == WizardSteps.FEATURES) titleColor else textColorDim)
            Text("4", color = if (step == WizardSteps.DONE) titleColor else textColorDim)
        }
        Column(Modifier
            .background(color = stepBackgroundColor)
            .padding(16.dp)
        ) {
            Text(title)
            Text(instruction, style = MaterialTheme.typography.bodyLarge.merge(color = textColor))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clickable { action() }
                .background(color = stepBackgroundColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.padding(end = 6.dp).size(32.dp), tint = textColor)
            Text(actionText, Modifier.weight(1f))
        }
    }
    @Composable fun steps() {
        if (step == WizardSteps.WELCOME)
            Step0 { step = WizardSteps.ENABLE }
        else
            Column {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    step = determineStep()
                }
                if (step == WizardSteps.ENABLE) {
                    Step(
                        step,
                        stringResource(R.string.setup_step1_title, appName),
                        stringResource(R.string.setup_step1_instruction, appName),
                        stringResource(R.string.setup_step1_action),
                        painterResource(R.drawable.ic_setup_key)
                    ) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    }
                } else if (step == WizardSteps.SELECT) {
                    Step(
                        step,
                        stringResource(R.string.setup_step2_title, appName),
                        stringResource(R.string.setup_step2_instruction, appName),
                        stringResource(R.string.setup_step2_action),
                        painterResource(R.drawable.ic_setup_select),
                        imm::showInputMethodPicker
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { close() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.sym_keyboard_language_switch),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_step3_action), Modifier.weight(1f))
                    }
                } else { // WizardSteps.DONE
                    Step(
                        step,
                        stringResource(R.string.setup_step3_title),
                        stringResource(R.string.setup_step3_instruction, appName),
                        stringResource(R.string.setup_step3_action),
                        painterResource(R.drawable.sym_keyboard_language_switch),
                        close
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { finish() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_setup_check),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_finish_action), Modifier.weight(1f))
                    }
                }
            }
    }
    Surface {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row {
                        Box(Modifier.weight(0.4f)) {
                            bigText()
                        }
                        Box(Modifier.weight(0.6f)) {
                            steps()
                        }
                    }
                else
                    Column {
                        bigText()
                        steps()
                    }
            }
        }
    }
}

@Composable
fun Step0(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.setup_welcome_image), null)
        Row(Modifier.clickable { onClick() }
            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
            //.background(color = MaterialTheme.colorScheme.primary)
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.setup_start_action),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SpannableStringUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import androidx.core.content.edit
import helium314.keyboard.latin.utils.IntentUtils
import java.util.Locale

@Composable
fun AboutScreen(
    onClickBack: () -> Unit,
) {
    val items = listOfNotNull(
        SettingsWithoutKey.APP,
        SettingsWithoutKey.VERSION,
        SettingsWithoutKey.LICENSE,
        SettingsWithoutKey.THIRD_PARTY_VAD,
        if (helium314.keyboard.latin.BuildConfig.AIKB_OPENAI) SettingsWithoutKey.THIRD_PARTY_SILERO else null,
        SettingsWithoutKey.THIRD_PARTY_DICTIONARIES,
        SettingsWithoutKey.DICTIONARY_CHECK,
        SettingsWithoutKey.HIDDEN_FEATURES,
        // play-3: no links to the wiki, the discussions and the page of the upstream project in the screens (the credit stays in the slogan)
        SettingsWithoutKey.GLEAMVOX_SOURCE,
        SettingsWithoutKey.GLEAMVOX_SITE,
        SettingsWithoutKey.SAVE_LOG,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_about),
        settings = items
    )
}

fun createAboutSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.APP, R.string.english_ime_name, R.string.app_slogan) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = { },
            icon = R.mipmap.ic_launcher_round
        )
    },
    Setting(context, SettingsWithoutKey.VERSION, R.string.version) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        var devCount by rememberSaveable { mutableIntStateOf(0) }
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        Preference(
            name = it.title,
            description = stringResource(R.string.version_text, BuildConfig.VERSION_NAME),
            onClick = {
                // stage 5g-4: 7 taps switch the developer mode on, as in Android
                devCount++
                when (val t = helium314.keyboard.latin.ai.DeveloperMode.tap(prefs, devCount)) {
                    is helium314.keyboard.latin.ai.DeveloperMode.Tap.More ->
                        Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.developer_mode_taps_left, t.left, t.left), Toast.LENGTH_SHORT).show()
                    helium314.keyboard.latin.ai.DeveloperMode.Tap.Enabled -> Toast.makeText(ctx, R.string.developer_mode_enabled, Toast.LENGTH_LONG).show()
                    helium314.keyboard.latin.ai.DeveloperMode.Tap.Already ->
                        if (devCount == helium314.keyboard.latin.ai.DeveloperMode.HINT_FROM) Toast.makeText(ctx, R.string.developer_mode_already, Toast.LENGTH_SHORT).show()
                    helium314.keyboard.latin.ai.DeveloperMode.Tap.Silent -> Unit
                }
                if (prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS) || BuildConfig.DEBUG)
                    return@Preference
                count++
                if (count < 5) return@Preference
                prefs.edit { putBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, true) }
                Toast.makeText(ctx, R.string.prefs_debug_settings_enabled, Toast.LENGTH_LONG).show()
            },
            icon = R.drawable.ic_settings_about_version
        )
    },
    Setting(context, SettingsWithoutKey.THIRD_PARTY_VAD, R.string.about_vad_title, R.string.about_vad_text) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SettingsWithoutKey.THIRD_PARTY_SILERO, R.string.about_silero_title, R.string.about_silero_text) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SettingsWithoutKey.THIRD_PARTY_DICTIONARIES, R.string.about_dictionaries_title, R.string.about_dictionaries_text) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SettingsWithoutKey.DICTIONARY_CHECK, R.string.dictionary_check_title, R.string.dictionary_check_text) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        Preference(name = it.title, description = it.description, onClick = {
            scope.launch(Dispatchers.IO) {
                val text = try { helium314.keyboard.latin.utils.DictionaryCheck.run(ctx) } catch (t: Throwable) { "Ошибка проверки: $t" }
                Log.i("DictionaryCheck", text)
                launch(Dispatchers.Main) {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("dictionary check", text))
                    Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
                }
            }
        })
    },
    Setting(context, SettingsWithoutKey.LICENSE, R.string.license, R.string.gnu_gpl) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.LICENSE.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_license
        )
    },
    Setting(context, SettingsWithoutKey.HIDDEN_FEATURES, R.string.hidden_features_title, R.string.hidden_features_summary) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                // Compose dialogs are in a rather sad state. They don't understand HTML, and don't scroll without customization.
                // this should be re-done in compose, but... bah
                val link = ("<a href=\"https://developer.android.com/reference/android/content/Context#createDeviceProtectedStorageContext()\">"
                        + ctx.getString(R.string.hidden_features_text) + "</a>")
                val message = ctx.getString(R.string.hidden_features_message, link)
                val dialogMessage = SpannableStringUtils.fromHtml(message)
                val builder = AlertDialog.Builder(ctx)
                    .setIcon(R.drawable.ic_settings_about_hidden_features)
                    .setTitle(R.string.hidden_features_title)
                    .setMessage(dialogMessage)
                    .setPositiveButton(R.string.dialog_close, null)
                    .create()
                builder.show()
                (builder.findViewById<View>(android.R.id.message) as TextView).movementMethod = LinkMovementMethod.getInstance()
            },
            icon = R.drawable.ic_settings_about_hidden_features
        )
    },
    Setting(context, SettingsWithoutKey.GITHUB_WIKI, R.string.about_wiki_link, R.string.about_wiki_link_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.WIKI_URL.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_wiki
        )
    },
    Setting(context, SettingsWithoutKey.COMMUNITY_LINKS, R.string.about_community_links, R.string.about_community_links_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.COMMUNITY_LINKS.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_community
        )
     },
    Setting(context, SettingsWithoutKey.GLEAMVOX_SOURCE, R.string.about_gleamvox_source, R.string.about_gleamvox_source_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Links.GLEAMVOX_SOURCE.toUri())) },
            icon = R.drawable.ic_settings_about_github
        )
    },
    Setting(context, SettingsWithoutKey.GLEAMVOX_SITE, R.string.about_gleamvox_site, R.string.about_gleamvox_site_description) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Links.GLEAMVOX_SITE.toUri())) },
            icon = R.drawable.ic_settings_about_github
        )
    },
    Setting(context, SettingsWithoutKey.GITHUB, R.string.about_github_link) {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent()
                intent.data = Links.GITHUB.toUri()
                intent.action = Intent.ACTION_VIEW
                ctx.startActivity(intent)
            },
            icon = R.drawable.ic_settings_about_github
        )
    },
    Setting(context, SettingsWithoutKey.SAVE_LOG, R.string.save_log) { setting ->
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.use { os ->
                    os.writer().use { writer ->
                        val logcat = Runtime.getRuntime().exec("logcat -d -b all *:W").inputStream.use { it.reader().readText() }
                        val internal = Log.getLog().joinToString("\n")
                        writer.write(logcat + "\n\n" + internal)
                    }
                }
            }
        }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = {
                val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Calendar.getInstance().time)
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "text/plain")
                    .setAction(Intent.ACTION_CREATE_DOCUMENT)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_log_$date.txt"
                    )
                launcher.launch(intent)
            },
            icon = R.drawable.ic_settings_about_log
        )
    },
)

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AboutScreen {  }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.dictionary.DictionaryFactory
import java.util.Locale

/**
 * play-2: "Проверка словарей" in About. Answers in a few lines: did the native library load, does the main dictionary of
 * Russian and English open, does it know ordinary words. Needed because the store build 4405 did not autocorrect and the
 * reason could not be seen from outside.
 */
object DictionaryCheck {
    private val CASES = listOf(Locale("ru") to listOf("привет", "спасибо"), Locale.US to listOf("hello", "thanks"))

    fun run(context: Context): String {
        JniUtils.loadNativeLibrary()
        val sb = StringBuilder()
        sb.append("Нативная библиотека: ").append(if (JniUtils.sNativeLoaded) "загружена" else "НЕ загружена " + JniUtils.sNativeError).append('\n')
        for ((locale, words) in CASES) {
            val assets = DictionaryInfoUtils.getAssetsDictionaryList(context)?.filter { it.startsWith("main_") && it.contains(locale.language) }
            sb.append(locale.toLanguageTag()).append(": в APK ").append(assets?.joinToString() ?: "-").append('\n')
            val dicts = try { DictionaryFactory.getMainDictsForLocale(context, locale, false) } catch (t: Throwable) {
                sb.append("  ошибка загрузки: ").append(t).append('\n'); continue
            }
            if (dicts.isEmpty()) sb.append("  словарь НЕ загрузился\n")
            for (d in dicts) {
                val known = words.joinToString { w -> w + "=" + (if (d.isInDictionary(w)) "да" else "нет") }
                sb.append("  ").append(d.mDictType).append(": ").append(if (d.isInitialized) "открыт" else "НЕ открыт")
                    .append(", ").append(known).append('\n')
                d.close()
            }
        }
        // swipe-2: the open glide typing: are the words of the keyboard's dictionaries read (in the keyboard's process)
        sb.append("Свайп: ").append(helium314.keyboard.latin.glide.OpenGlide.status()).append('\n')
        return sb.toString().trimEnd()
    }
}

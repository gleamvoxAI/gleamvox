// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.ImageRules.Show
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import helium314.keyboard.latin.R

/** stage 5g-15: pictures of the clipboard and the fresh screenshot in the suggestion strip */
@RunWith(RobolectricTestRunner::class)
class ImageSuggestionsTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val images = arrayOf("image/*")

    @Test fun aFieldThatTakesPicturesGetsThem() {
        assertEquals(Show.INSERT, ImageRules.clipImage(images, "image/png", text, 0, false))
        assertEquals(Show.INSERT, ImageRules.clipImage(arrayOf("image/png", "image/gif"), "image/png", text, 0, false))
        assertEquals(Show.INSERT, ImageRules.clipImage(arrayOf("*/*"), "image/jpeg", text, 0, false))
        assertTrue(ImageRules.screenshot(images, "image/png", text, 0, false))
    }

    @Test fun aFieldWithoutPicturesShowsThemGrayAndNoScreenshot() {
        assertEquals(Show.NOT_ACCEPTED, ImageRules.clipImage(emptyArray(), "image/png", text, 0, false))
        assertEquals(Show.NOT_ACCEPTED, ImageRules.clipImage(arrayOf("image/gif"), "image/png", text, 0, false))
        assertEquals(Show.NOT_ACCEPTED, ImageRules.clipImage(arrayOf("text/plain"), "image/png", text, 0, false))
        assertFalse(ImageRules.screenshot(emptyArray(), "image/png", text, 0, false))
    }

    @Test fun passwordsNumbersAndDoNotRememberShowNoPicture() {
        val hidden = listOf(
            "password" to (text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "web password" to (text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "visible password" to (text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "pin" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "number" to InputType.TYPE_CLASS_NUMBER,
        )
        for ((name, type) in hidden) {
            assertEquals(Show.HIDDEN, ImageRules.clipImage(images, "image/png", type, 0, false), name)
            assertFalse(ImageRules.screenshot(images, "image/png", type, 0, false), name)
        }
        assertEquals(Show.HIDDEN, ImageRules.clipImage(images, "image/png", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false), "do not remember")
        assertEquals(Show.HIDDEN, ImageRules.clipImage(images, "image/png", text, 0, true), "incognito")
    }

    private fun row(id: Long, agoS: Long, path: String? = "Pictures/Screenshots/", name: String? = "Screenshot_20261016.png") =
        FreshScreenshot.Row(id, NOW / 1000 - agoS, path, name, "image/png")

    @Test fun onlyAScreenshotOfTheLastTwoMinutesOnce() {
        assertEquals(7L, FreshScreenshot.pick(listOf(row(7, 60)), NOW, null)?.id)
        assertEquals(7L, FreshScreenshot.pick(listOf(row(7, 120)), NOW, null)?.id, "exactly 2 minutes")
        assertNull(FreshScreenshot.pick(listOf(row(7, 121)), NOW, null), "older than 2 minutes")
        assertNull(FreshScreenshot.pick(listOf(row(7, 180)), NOW, null), "3 minutes")
        assertNull(FreshScreenshot.pick(listOf(row(7, 10)), NOW, 7L), "inserted or dismissed: not again")
        assertEquals(8L, FreshScreenshot.pick(listOf(row(7, 50), row(8, 10)), NOW, 7L)?.id, "a newer one is offered")
        assertNull(FreshScreenshot.pick(listOf(row(7, 50), row(8, 10)), NOW, 8L), "the older one does not come back")
        assertNull(FreshScreenshot.pick(emptyList(), NOW, null))
    }

    @Test fun otherPhotosAreNoScreenshots() {
        assertNull(FreshScreenshot.pick(listOf(row(3, 5, "DCIM/Camera/", "IMG_20261016.jpg")), NOW, null))
        assertEquals(4L, FreshScreenshot.pick(listOf(row(4, 5, "Pictures/", "Screenshot_x.jpg")), NOW, null)?.id, "by the name")
        assertEquals(5L, FreshScreenshot.pick(listOf(row(5, 5, "DCIM/Screenshots/", "x.png")), NOW, null)?.id, "by the folder")
        assertEquals(6L, FreshScreenshot.pick(listOf(row(3, 1, "DCIM/Camera/", "IMG.jpg"), row(6, 5)), NOW, null)?.id, "the newest screenshot, not the newest photo")
    }

    @Test fun accessToPhotos() {
        val all = { p: String -> p == Manifest.permission.READ_MEDIA_IMAGES }
        val selected = { p: String -> p == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
        val none = { _: String -> false }
        assertEquals(FreshScreenshot.Access.FULL, FreshScreenshot.access(36, all))
        assertEquals(FreshScreenshot.Access.PARTIAL, FreshScreenshot.access(34, selected), "only selected photos (Android 14+)")
        assertEquals(FreshScreenshot.Access.NONE, FreshScreenshot.access(36, none), "not given")
        assertEquals(FreshScreenshot.Access.FULL, FreshScreenshot.access(33, all))
        assertEquals(FreshScreenshot.Access.NONE, FreshScreenshot.access(33, selected), "no partial access on Android 13")
        assertEquals(FreshScreenshot.Access.NONE, FreshScreenshot.access(32, all), "not before Android 13")
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), FreshScreenshot.permissions(34).toList())
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), FreshScreenshot.permissions(33).toList())
        assertTrue(FreshScreenshot.permissions(32).isEmpty())
    }

    @Test fun withoutPermissionNothingIsLookedUp() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(FreshScreenshot.Access.NONE, FreshScreenshot.access(ctx))
        assertTrue(FreshScreenshot.query(ctx).isEmpty())
    }

    @Test fun theProviderGivesOnlyTheOfferedScreenshot() {
        val provider = Robolectric.setupContentProvider(ScreenshotProvider::class.java)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        FreshScreenshot.offered = FreshScreenshot.Row(42, now / 1000 - 30, "Pictures/Screenshots/", "Screenshot.png", "image/png")
        try {
            assertEquals("image/png", provider.getType(FreshScreenshot.insertUri(ctx, 42)))
            assertNull(provider.getType(FreshScreenshot.insertUri(ctx, 41)), "another photo")
            assertNull(provider.query(Uri.parse("content://${ctx.packageName}.screenshot/41"), null, null, null, null))
            assertFailsWith<FileNotFoundException> { provider.openFile(FreshScreenshot.insertUri(ctx, 41), "r") }
            assertFailsWith<SecurityException> { provider.openFile(FreshScreenshot.insertUri(ctx, 42), "w") }
            FreshScreenshot.offered = FreshScreenshot.Row(42, now / 1000 - 31 * 60, null, "Screenshot.png", "image/png")
            assertNull(provider.getType(FreshScreenshot.insertUri(ctx, 42)), "not readable for ever")
        } finally { FreshScreenshot.offered = null }
    }

    // ---- stage 5g-16 ----

    @Test fun theSwitchesAreOnByDefault() {
        assertTrue(helium314.keyboard.latin.settings.Defaults.PREF_SUGGEST_SCREENSHOT)
        assertTrue(helium314.keyboard.latin.settings.Defaults.PREF_ADD_TO_PERSONAL_DICTIONARY)
        // nothing stored (an installed copy that never switched it off): on
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        assertTrue(prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_SUGGEST_SCREENSHOT, helium314.keyboard.latin.settings.Defaults.PREF_SUGGEST_SCREENSHOT))
        // switched off by the owner: stays off
        prefs.edit().putBoolean(helium314.keyboard.latin.settings.Settings.PREF_SUGGEST_SCREENSHOT, false).commit()
        assertFalse(prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_SUGGEST_SCREENSHOT, helium314.keyboard.latin.settings.Defaults.PREF_SUGGEST_SCREENSHOT))
    }

    private fun prompt(on: Boolean = true, sdk: Int = 36, access: FreshScreenshot.Access = FreshScreenshot.Access.NONE, fieldOk: Boolean = true,
                       changedAgoMs: Long? = 5_000, askUntil: Long = 0, hintShown: Boolean = false) =
        FreshScreenshot.prompt(on, sdk, access, fieldOk, changedAgoMs?.let { NOW - it }, NOW, askUntil, hintShown)

    @Test fun theAllowCardNeedsAFreshImageAndAFieldWithPictures() {
        assertEquals(FreshScreenshot.Prompt.ASK, prompt())
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(changedAgoMs = null), "no image appeared")
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(changedAgoMs = 121_000), "older than 2 minutes")
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(fieldOk = false), "a field without pictures, a password")
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(on = false), "switched off (Never)")
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(sdk = 32), "before Android 13")
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(access = FreshScreenshot.Access.FULL), "access is there: the thumbnail, not the card")
    }

    @Test fun notNowHidesTheCardForSevenDays() {
        val until = NOW + FreshScreenshot.SNOOZE_MS
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(askUntil = until))
        assertEquals(FreshScreenshot.Prompt.ASK, prompt(askUntil = NOW - 1), "the week is over")
    }

    @Test fun selectedPhotosOnlyGiveTheHintOnce() {
        assertEquals(FreshScreenshot.Prompt.PARTIAL_HINT, prompt(access = FreshScreenshot.Access.PARTIAL))
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(access = FreshScreenshot.Access.PARTIAL, hintShown = true))
        assertEquals(FreshScreenshot.Prompt.NONE, prompt(access = FreshScreenshot.Access.PARTIAL, fieldOk = false))
    }

    @Test fun theJournalLine() {
        assertEquals("screenshot: access partial, found 0, offered no, none found", FreshScreenshot.logLine(FreshScreenshot.Access.PARTIAL, 0, false, "none found"))
        assertEquals("screenshot: access full, found 1, offered yes, ok", FreshScreenshot.logLine(FreshScreenshot.Access.FULL, 1, true, "ok"))
        assertEquals("screenshot: access none, found 0, offered yes, card allow", FreshScreenshot.logLine(FreshScreenshot.Access.NONE, 0, true, "card allow"))
    }

    @Test fun aNewImageInTheGalleryRefreshesTheStripWithoutReopeningTheField() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var refreshed = 0
        ScreenshotWatch.lastChangeMs = null
        ScreenshotWatch.onRefresh = { refreshed++ }
        val observer = ScreenshotWatch.register(ctx, handler) {}
        try {
            ctx.contentResolver.notifyChange(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
            kotlin.test.assertNotNull(ScreenshotWatch.lastChangeMs, "the time of the change is noted")
            assertEquals(1, refreshed, "the strip is refreshed once, after the file is written")
        } finally {
            ctx.contentResolver.unregisterContentObserver(observer)
            ScreenshotWatch.onRefresh = null; ScreenshotWatch.lastChangeMs = null
        }
    }

    // ---- the cards in the strip (the real manager) ----

    private fun manager(): Pair<ClipboardHistoryManager, LatinIME> {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val f = LatinIME::class.java.getDeclaredField("mClipboardHistoryManager").apply { isAccessible = true }
        return f.get(ime) as ClipboardHistoryManager to ime
    }

    private fun info(types: Array<String>? = arrayOf("image/*"), inputType: Int = text, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType; this.imeOptions = imeOptions
        if (types != null) androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(this, types)
    }

    private fun card(m: ClipboardHistoryManager, ime: LatinIME, i: EditorInfo): android.view.View? {
        val parent = android.widget.FrameLayout(ime)
        return m.getClipboardSuggestionView(i, parent)
    }

    private fun texts(v: android.view.View): String =
        (v.findViewById<android.widget.TextView>(R.id.clipboard_suggestion_text).text.toString() + "|" +
            v.findViewById<android.widget.TextView>(R.id.clipboard_suggestion_never).let { if (it.visibility == android.view.View.VISIBLE) it.text else "" })

    @Test fun theAllowCardAppearsOnlyAfterAFreshImage() {
        val (m, ime) = manager()
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(ime)
        prefs.edit().clear().commit()
        ScreenshotWatch.lastChangeMs = null
        assertNull(card(m, ime, info()), "no image appeared: nothing")
        ScreenshotWatch.changed()
        val v = card(m, ime, info())!!
        assertEquals("📷 Insert fresh screenshots? Allow|Never", texts(v))
        assertEquals("screenshot: access none, found 0, offered yes, card allow", prefs.getString(FreshScreenshot.PREF_LAST_LOG, null))
        assertNull(card(m, ime, info(types = null)), "a field without pictures")
        assertNull(card(m, ime, info(inputType = text or InputType.TYPE_TEXT_VARIATION_PASSWORD)), "a password")
        assertNull(card(m, ime, info(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)), "do not remember")
        ScreenshotWatch.lastChangeMs = null
    }

    @Test fun allowOpensTheInvisibleActivityNotNowAndNever() {
        val (m, ime) = manager()
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(ime)
        prefs.edit().clear().commit()
        ScreenshotWatch.changed()
        // Allow: the invisible activity with the system dialog
        card(m, ime, info())!!.findViewById<android.view.View>(R.id.clipboard_suggestion_text).performClick()
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(ScreenshotPermissionActivity::class.java.name, started.component!!.className)
        // Not now (the cross): no card for 7 days
        card(m, ime, info())!!.findViewById<android.view.View>(R.id.clipboard_suggestion_close).performClick()
        assertTrue(prefs.getLong(FreshScreenshot.PREF_ASK_UNTIL, 0) > System.currentTimeMillis() + FreshScreenshot.SNOOZE_MS - 60_000)
        assertNull(card(m, ime, info()))
        // Never: the feature is off
        prefs.edit().remove(FreshScreenshot.PREF_ASK_UNTIL).commit()
        card(m, ime, info())!!.findViewById<android.view.View>(R.id.clipboard_suggestion_never).performClick()
        assertFalse(prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_SUGGEST_SCREENSHOT, true))
        assertNull(card(m, ime, info()))
        ScreenshotWatch.lastChangeMs = null
    }

    @Test fun selectedPhotosGiveTheHintOnceAndOpenTheAppPermissions() {
        val (m, ime) = manager()
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(ime)
        prefs.edit().clear().commit()
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ScreenshotWatch.changed()
        val v = card(m, ime, info())!!
        assertEquals("📷 New screenshots are hidden. Allow all photos|", texts(v))
        assertNull(card(m, ime, info()), "once")
        v.findViewById<android.view.View>(R.id.clipboard_suggestion_text).performClick()
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:" + ime.packageName, started.dataString)
        ScreenshotWatch.lastChangeMs = null
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}

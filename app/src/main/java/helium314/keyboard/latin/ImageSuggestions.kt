// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import java.io.FileNotFoundException

/**
 * Stage 5g-15 (the owner: "the last screenshot should be offered for inserting, like in Gboard"): when a picture may be
 * offered in the suggestion strip. A picture goes into a field only through commitContent, so only a field that says
 * it takes images (EditorInfoCompat.getContentMimeTypes) gets it; passwords, "do not remember" fields (incognito,
 * IME_FLAG_NO_PERSONALIZED_LEARNING) and number fields never show a picture.
 */
object ImageRules {
    enum class Show { INSERT, NOT_ACCEPTED, HIDDEN }

    /** the field never shows a picture: passwords, numbers, "do not remember" */
    fun hiddenField(inputType: Int, imeOptions: Int, incognito: Boolean): Boolean =
        incognito || imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 ||
            InputTypeUtils.isAnyPasswordInputType(inputType) || InputTypeUtils.isNumberInputType(inputType)

    /** a type like image/png against what the field takes (image/any, image/png, any/any) */
    fun accepts(fieldTypes: Array<String>, imageType: String): Boolean = fieldTypes.any { matches(it, imageType) }

    private fun matches(pattern: String, type: String): Boolean {
        val (pa, pb) = pattern.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "*" } }
        val (ta, tb) = type.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "*" } }
        return (pa == "*" || ta == "*" || pa == ta) && (pb == "*" || tb == "*" || pb == tb)
    }

    /** a picture from the clipboard: inserted, shown gray ("this field does not take pictures"), or not shown */
    fun clipImage(fieldTypes: Array<String>, imageType: String, inputType: Int, imeOptions: Int, incognito: Boolean): Show = when {
        hiddenField(inputType, imeOptions, incognito) -> Show.HIDDEN
        accepts(fieldTypes, imageType) -> Show.INSERT
        else -> Show.NOT_ACCEPTED
    }

    /** a fresh screenshot is offered only where it can be inserted (it was not copied by the user) */
    fun screenshot(fieldTypes: Array<String>, imageType: String, inputType: Int, imeOptions: Int, incognito: Boolean): Boolean =
        clipImage(fieldTypes, imageType, inputType, imeOptions, incognito) == Show.INSERT
}

/**
 * Stage 5g-15, step 2 ("Offer a fresh screenshot", off by default): the newest screenshot of the last 2 minutes, once.
 * Only rows of screenshots are asked for (the query itself selects by the folder Screenshots or a name "Screenshot…"),
 * no other photo is read; nothing is copied or sent, the picture is read again from the gallery when it is inserted.
 */
object FreshScreenshot {
    const val MAX_AGE_MS = 2 * 60 * 1000L

    /** play-1: the store version has no photo access until Google reviewed it; the feature is not there at all */
    fun inBuild(): Boolean = helium314.keyboard.latin.BuildConfig.AIKB_SCREENSHOT

    enum class Access { FULL, PARTIAL, NONE }

    class Row(val id: Long, val dateAddedS: Long, val relativePath: String?, val displayName: String?, val mime: String?)

    fun isScreenshot(r: Row) = r.relativePath?.contains("screenshot", ignoreCase = true) == true ||
        r.displayName?.startsWith("screenshot", ignoreCase = true) == true

    /** the newest screenshot not older than 2 minutes, unless it was inserted or dismissed already */
    fun pick(rows: List<Row>, nowMs: Long, done: Long?): Row? {
        val newest = rows.filter { isScreenshot(it) }.maxByOrNull { it.dateAddedS } ?: return null
        if (nowMs - newest.dateAddedS * 1000 > MAX_AGE_MS) return null
        return newest.takeIf { it.id != done }
    }

    /** what the gallery lets the keyboard see: everything, the photos the user selected (Android 14+), or nothing */
    fun access(sdk: Int, granted: (String) -> Boolean): Access = when {
        sdk < Build.VERSION_CODES.TIRAMISU -> Access.NONE
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }

    /** the permissions asked for when the switch is turned on */
    fun permissions(sdk: Int): Array<String> = when {
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> emptyArray()
    }

    fun access(context: Context): Access =
        access(Build.VERSION.SDK_INT) { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** the screenshots of the last 2 minutes (only those rows), newest first; empty without access */
    fun query(context: Context, nowMs: Long = System.currentTimeMillis()): List<Row> {
        if (access(context) == Access.NONE) return emptyList()
        val since = (nowMs - MAX_AGE_MS) / 1000
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND (${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf(since.toString(), "%Screenshot%", "Screenshot%")
        val rows = mutableListOf<Row>()
        try {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < 3)
                    rows.add(Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        } catch (e: Exception) {
            Log.w("FreshScreenshot", "no screenshots: ${e.javaClass.simpleName}") // never the names or paths
        }
        return rows
    }

    /** the one screenshot that may be read through [ScreenshotProvider] now (offered in the strip), and its type */
    @Volatile var offered: Row? = null

    fun galleryUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** what the field is given: our own address, readable only while this screenshot is offered */
    fun insertUri(context: Context, id: Long): Uri = Uri.parse("content://${context.packageName}.screenshot/$id")

    const val PREF_DONE = "aikb_screenshot_done_id"
    /** stage 5g-16: "Not now" on the card: no card until this moment (ms) */
    const val PREF_ASK_UNTIL = "aikb_screenshot_ask_until"
    /** stage 5g-16: the hint "allow all photos" was shown (once) */
    const val PREF_PARTIAL_HINT = "aikb_screenshot_partial_hint"
    /** stage 5g-16: the last line of the diagnostics ("screenshot: access …") for the copy of the journal */
    const val PREF_LAST_LOG = "aikb_screenshot_last_log"
    const val SNOOZE_MS = 7 * 24 * 60 * 60 * 1000L

    enum class Prompt { NONE, ASK, PARTIAL_HINT }

    /**
     * stage 5g-16: which card the strip shows instead of a screenshot. Only when the switch is on, the field takes
     * pictures ([fieldOk]) and an image appeared in the gallery in the last 2 minutes ([changedMs], only the time of the
     * change is known, nothing is read). No access: "Allow" (unless "Not now" is still valid); selected photos only: a
     * hint, once.
     */
    fun prompt(on: Boolean, sdk: Int, access: Access, fieldOk: Boolean, changedMs: Long?, nowMs: Long, askUntilMs: Long, hintShown: Boolean): Prompt {
        if (!on || sdk < Build.VERSION_CODES.TIRAMISU || !fieldOk) return Prompt.NONE
        if (changedMs == null || nowMs - changedMs > MAX_AGE_MS) return Prompt.NONE
        return when (access) {
            Access.FULL -> Prompt.NONE
            Access.NONE -> if (nowMs < askUntilMs) Prompt.NONE else Prompt.ASK
            Access.PARTIAL -> if (hintShown) Prompt.NONE else Prompt.PARTIAL_HINT
        }
    }

    /** "screenshot: access full/partial/none, found N, offered yes/no, reason" */
    fun logLine(access: Access, found: Int, offered: Boolean, reason: String) =
        "screenshot: access ${access.name.lowercase()}, found $found, offered ${if (offered) "yes" else "no"}, $reason"
}

/**
 * Stage 5g-16: knows only WHEN an image appeared in the gallery (a ContentObserver needs no access and tells nothing
 * else): the time is kept in memory. A card "Allow" or the hint appears only after such a change, and a new screenshot
 * shows up at once while the keyboard is open.
 */
object ScreenshotWatch {
    @Volatile var lastChangeMs: Long? = null
    /** the keyboard shows a card or a thumbnail again (set by the strip manager) */
    @Volatile var onRefresh: (() -> Unit)? = null

    fun changed(nowMs: Long = System.currentTimeMillis()) { lastChangeMs = nowMs }
    /** the access to photos was just given or refused */
    fun accessChanged() { onRefresh?.invoke() }

    fun register(context: Context, handler: android.os.Handler, onChange: () -> Unit): android.database.ContentObserver {
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                changed()
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 800) // the file may still be written
                onChange()
            }
            private val refresh = Runnable { onRefresh?.invoke() }
        }
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: Exception) { Log.w("ScreenshotWatch", "no observer: ${e.javaClass.simpleName}") }
        return observer
    }
}

/** Stage 5g-16: invisible activity that only shows the system dialog for access to photos (a keyboard can't). */
class ScreenshotPermissionActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = FreshScreenshot.permissions(Build.VERSION.SDK_INT)
        if (wanted.isEmpty() || FreshScreenshot.access(this) == FreshScreenshot.Access.FULL) { finish(); return }
        if (savedInstanceState == null) requestPermissions(wanted, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val access = FreshScreenshot.access(this)
        val prefs = helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(this)
        // refused: no card for 7 days; partial: the hint comes by itself
        if (access == FreshScreenshot.Access.NONE)
            prefs.edit().putLong(FreshScreenshot.PREF_ASK_UNTIL, System.currentTimeMillis() + FreshScreenshot.SNOOZE_MS).apply()
        prefs.edit().putString(FreshScreenshot.PREF_LAST_LOG, FreshScreenshot.logLine(access, 0, false, "asked")).apply()
        ScreenshotWatch.accessChanged()
        finish()
    }
}

/**
 * Stage 5g-15: the field reads the offered screenshot through this provider (the gallery address cannot be handed on,
 * the field has no access to the gallery). No copy: the file of the gallery is opened read-only when the field reads it,
 * and only the screenshot offered now; every other address is "not found".
 */
class ScreenshotProvider : ContentProvider() {
    override fun onCreate() = true

    private fun offered(uri: Uri): FreshScreenshot.Row? {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        // the field may read it a while after the tap (when the message is sent), not for ever
        return FreshScreenshot.offered?.takeIf { it.id == id && System.currentTimeMillis() - it.dateAddedS * 1000 <= READABLE_MS }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val row = offered(uri) ?: throw FileNotFoundException()
        return context?.contentResolver?.openFileDescriptor(FreshScreenshot.galleryUri(row.id), "r") ?: throw FileNotFoundException()
    }

    override fun getType(uri: Uri): String? = offered(uri)?.let { it.mime ?: "image/png" }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val row = offered(uri) ?: return null
        val ext = if (row.mime == "image/jpeg") "jpg" else "png"
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("screenshot.$ext")) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    companion object { const val READABLE_MS = 30 * 60 * 1000L }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

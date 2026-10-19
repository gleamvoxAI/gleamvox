// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * ux-7: "Screenshot or photo" without any access to photos (the store version must not hold READ_MEDIA_IMAGES). The
 * keyboard opens the system Photo Picker; the user chooses the picture; only that one picture is copied into the
 * private folder of the keyboard (the clipboard folder, the same address provider as for pictures of the clipboard) and
 * goes into the field with commitContent, like a picture of the clipboard. Nothing else of the gallery is seen.
 */
object PhotoPick {
    private const val TAG = "PhotoPick"
    private const val TTL_MS = 2 * 60 * 1000L
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val PREFIX = "picked_"

    class Pending(val file: File, val mime: String, val atMs: Long)
    @Volatile var pending: Pending? = null

    /** the keyboard that is running (to give the picture to the field after the picker closed) */
    @Volatile var keyboard: LatinIME? = null

    fun start(context: Context) {
        context.startActivity(Intent(context, PhotoPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** copies the chosen picture (not more than 20 MB, only pictures) to the private folder; null if that did not work */
    fun copy(context: Context, uri: Uri, nowMs: Long = System.currentTimeMillis()): Pending? {
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: return null
        val dir = File(context.filesDir, "clipboard").apply { mkdirs() } // the folder of the clipboard provider (res/xml/clipboard_provider_path.xml)
        cleanOld(dir, nowMs)
        val ext = when (mime) { "image/png" -> ".png"; "image/gif" -> ".gif"; "image/webp" -> ".webp"; else -> ".jpg" }
        val file = File(dir, "$PREFIX$nowMs$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { file.delete(); return null }
                        out.write(buffer, 0, n)
                    }
                }
            } ?: return null
        } catch (e: Exception) {
            file.delete()
            Log.w(TAG, "could not copy the picture: ${e.javaClass.simpleName}") // never the address
            return null
        }
        return Pending(file, mime, nowMs)
    }

    /** pictures of earlier picks are not kept longer than a day */
    private fun cleanOld(dir: File, nowMs: Long) {
        dir.listFiles()?.filter { it.name.startsWith(PREFIX) && nowMs - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
    }

    fun picked(pending: Pending) { this.pending = pending; consumeLater() }

    /** gives the picture to the field a moment later: the window of the app takes some time to be the active one again */
    fun consumeLater() {
        val ime = keyboard ?: return
        Handler(Looper.getMainLooper()).postDelayed({ consume(ime) }, 400)
    }

    enum class Decision { INSERT, EXPIRED, HIDDEN, NOT_ACCEPTED }

    /**
     * what happens with the picked picture in this field: passwords, numbers and "do not remember" fields never get it;
     * a field that names the picture types it takes but not this one says so; a field that names none gets the system
     * paste (as for pictures of the clipboard); a picture older than 2 minutes is forgotten
     */
    fun decide(ageMs: Long, fieldTypes: Array<String>, mime: String, inputType: Int, imeOptions: Int, incognito: Boolean): Decision = when {
        ageMs > TTL_MS -> Decision.EXPIRED
        ImageRules.hiddenField(inputType, imeOptions, incognito) -> Decision.HIDDEN
        fieldTypes.isNotEmpty() && !ImageRules.accepts(fieldTypes, mime) -> Decision.NOT_ACCEPTED
        else -> Decision.INSERT
    }

    /** the picture goes into the current field once (not older than 2 minutes); false: nothing to insert or not possible */
    fun consume(ime: LatinIME, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = pending ?: return false
        if (!p.file.isFile) { pending = null; return false }
        val info = ime.currentInputEditorInfo ?: return false // the field is not there yet: it comes with onStartInputView
        val decision = decide(nowMs - p.atMs, EditorInfoCompat.getContentMimeTypes(info), p.mime, info.inputType, info.imeOptions,
            ime.mSettings.current.mIncognitoModeEnabled)
        pending = null
        if (decision == Decision.NOT_ACCEPTED) Toast.makeText(ime, R.string.photo_pick_not_accepted, Toast.LENGTH_LONG).show()
        if (decision != Decision.INSERT) return false
        val uri = FileProvider.getUriForFile(ime, ime.getString(R.string.clipboard_provider_authority), p.file)
        ime.mKeyboardActionListener.onContent(InputContentInfoCompat(uri, ClipDescription("picture", arrayOf(p.mime)), null))
        return true
    }
}

/** ux-7: invisible activity that only shows the system Photo Picker (a keyboard can't); the result goes to [PhotoPick] */
class PhotoPickActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        val app = applicationContext
        Thread {
            val pending = PhotoPick.copy(app, uri)
            Handler(Looper.getMainLooper()).post {
                if (pending != null) PhotoPick.picked(pending)
                else Toast.makeText(app, R.string.photo_pick_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

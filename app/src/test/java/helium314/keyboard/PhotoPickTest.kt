// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.PhotoPick
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** a stand-in for the system Photo Picker: gives one file with a type */
class FakePickerProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = typeOf
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    companion object { lateinit var file: File; var typeOf: String? = "image/png" }
}

/** ux-7: a picture chosen in the system Photo Picker (the store version has no access to photos) */
@RunWith(RobolectricTestRunner::class)
class PhotoPickTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val uri = Uri.parse("content://test.picker/1")

    private fun picker(type: String?, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        FakePickerProvider.file = File.createTempFile("picked", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        FakePickerProvider.typeOf = type
        Robolectric.setupContentProvider(FakePickerProvider::class.java, "test.picker")
    }

    @Test fun theChosenPictureIsCopiedToThePrivateFolder() {
        picker("image/png")
        val p = assertNotNull(PhotoPick.copy(context, uri, nowMs = 1000))
        assertEquals("image/png", p.mime)
        assertEquals(File(context.filesDir, "clipboard").absolutePath, p.file.parentFile!!.absolutePath)
        assertTrue(p.file.name.startsWith("picked_") && p.file.name.endsWith(".png"), p.file.name)
        assertEquals(listOf<Byte>(1, 2, 3, 4), p.file.readBytes().toList())
    }

    @Test fun onlyPicturesAreTaken() {
        picker("text/plain")
        assertNull(PhotoPick.copy(context, uri))
        picker(null)
        assertNull(PhotoPick.copy(context, uri))
    }

    @Test fun oldPicksAreRemovedAfterADay() {
        picker("image/jpeg")
        val dir = File(context.filesDir, "clipboard").apply { mkdirs() }
        val old = File(dir, "picked_1.jpg").apply { writeText("x"); setLastModified(1_000) }
        val other = File(dir, "other.png").apply { writeText("y"); setLastModified(1_000) }
        PhotoPick.copy(context, uri, nowMs = 1_000 + 25 * 60 * 60 * 1000L)
        assertFalse(old.exists(), "an old pick is gone")
        assertTrue(other.exists(), "files of the clipboard history are never touched")
    }

    private fun decide(age: Long = 1000, types: Array<String> = arrayOf("image/*"), input: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, incognito: Boolean = false) =
        PhotoPick.decide(age, types, "image/png", input, options, incognito)

    @Test fun rulesOfTheField() {
        assertEquals(PhotoPick.Decision.INSERT, decide())
        assertEquals(PhotoPick.Decision.INSERT, decide(types = emptyArray()), "a field without types gets the system paste, like the clipboard pictures")
        assertEquals(PhotoPick.Decision.NOT_ACCEPTED, decide(types = arrayOf("image/gif")))
        assertEquals(PhotoPick.Decision.EXPIRED, decide(age = 3 * 60 * 1000L))
        assertEquals(PhotoPick.Decision.HIDDEN, decide(input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(PhotoPick.Decision.HIDDEN, decide(input = InputType.TYPE_CLASS_NUMBER))
        assertEquals(PhotoPick.Decision.HIDDEN, decide(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertEquals(PhotoPick.Decision.HIDDEN, decide(incognito = true))
    }

    @Test fun noPermissionToPhotosInTheStoreVersion() {
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue("READ_MEDIA_IMAGES\" tools:node=\"remove\"" in play, "the store version still has no access to photos")
        val main = File("src/main/AndroidManifest.xml").readText()
        assertTrue("helium314.keyboard.latin.PhotoPickActivity" in main && "exported=\"false\"" in main.substringAfter("PhotoPickActivity").substringBefore("/>"))
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var tempPrimaryClip = false

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
        // stage 5g-16: only the time of a change in the gallery is noted; a new screenshot shows up while the keyboard is open
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (FreshScreenshot.inBuild()) mediaObserver = ScreenshotWatch.register(latinIME, handler) {}
        ScreenshotWatch.onRefresh = { if (latinIME.isInputViewShown) latinIME.tryShowClipboardSuggestion() }
    }

    private var mediaObserver: android.database.ContentObserver? = null

    fun onDestroy() {
        mediaObserver?.let { latinIME.contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
        ScreenshotWatch.onRefresh = null
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // todo for later
    //  setting whether to store sensitive clip data?
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip) return // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val clipItem = clipData.getItemAt(0) ?: return
        val description = clipData.description ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)

        if (description.hasMimeType("text/*")) {
            val content = clipItem.coerceToText(latinIME)
            if (TextUtils.isEmpty(content)) return
            if (isSecretClip(description, content)) return // AI Keyboard: never kept in the history on disk
            clipboardDao?.addClip(timeStamp, false, content.toString())
        } else if (maySaveFromUri(clipItem.uri, latinIME)) {
            clipboardDao?.addClipUri(timeStamp, false, clipItem.uri, description, latinIME)
        }
    }

    fun getPrimaryClipIfText(): String? {
        if (tempPrimaryClip) return null // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        return if (clipData.description?.hasMimeType("text/*") == true)
            clipItem.coerceToText(latinIME).toString().takeIf { it.isNotEmpty() }
        else null
    }

    // fallback method because in some apps there is no supported mime type and commitContend does nothing,
    // but KeyEvent.KEYCODE_PASTE for pasting from primary clip works fine
    // (actually we do change the primary clip, but (try to) revert immediately)
    fun pasteWithoutChangingClips(content: InputContentInfoCompat) {
        Log.d(TAG, "trying fallback pasting with system clipboard")
        val primaryClip = clipboardManager.primaryClip
        val tempClip = ClipData(content.description, ClipData.Item(content.contentUri))
        tempPrimaryClip = true
        clipboardManager.setPrimaryClip(tempClip)
        latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        tempPrimaryClip = false
        if (primaryClip == null)
            return
        // we need to wait a little before switching back to the original primary clip
        // a. it can happen that we switch back before the pasting has started, in that case we only past the primary clip
        // b. if we switch while the clip is pasted, it might crash the app (tested with joplin and logseq)
        // todo: replacing the current primary clip is far from ideal, try finding a different way
        GlobalScope.launch {
            delay(500)
            try {
                clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
                // happens wen the clip was a file
                // try to find it in out clipboard entries
                val clip = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == ClipboardManagerCompat.getClipTimestamp(primaryClip) }
                if (clip?.filename != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription(clip.text, clip.mimeTypes?.toTypedArray()),
                        ClipData.Item(clip.getContentUri(latinIME))
                    ))
                else if (clip != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription("", arrayOf("text/*")),
                        ClipData.Item(clip.text)
                    ))
            }
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    /** stage 5g-15: the current field shows no pictures (password, number, "do not remember", incognito) */
    fun imagesHiddenHere(): Boolean {
        val info = latinIME.currentInputEditorInfo
        return ImageRules.hiddenField(info?.inputType ?: InputType.TYPE_NULL, info?.imeOptions ?: 0, latinIME.mSettings.current.mIncognitoModeEnabled)
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        shownScreenshot = null
        if (parent == null) return null
        return clipSuggestionView(editorInfo, parent) ?: screenshotSuggestionView(editorInfo, parent) ?: aiDictOfferView(editorInfo, parent)
    }

    /**
     * ai-dict-2: the offer of the AI dictionary, ONCE (shown = used up) in the empty strip, after 3 days and 300 words of
     * real use, on Wi-Fi, outside password / e-mail / number / "do not remember" fields and incognito. It stays only until
     * the first key is pressed (typing replaces the strip; [AiDictOffer.keyPressed]). "×" and "Not now" = never again.
     */
    private fun aiDictOfferView(editorInfo: EditorInfo?, parent: ViewGroup): View? {
        if (!helium314.keyboard.latin.ailm.AiDictionary.inBuild()) return null
        val prefs = latinIME.prefs()
        val offer = helium314.keyboard.latin.ailm.AiDictOffer
        if (offer.sessionOffered == offer.session) return if (offer.keyPressed) null else buildAiDictOffer(parent) // same field again (strip refresh)
        val hidden = imagesHiddenHere() || helium314.keyboard.latin.ai.AiGuard.blocked(editorInfo,
            helium314.keyboard.latin.ai.AiPrefs.excludedApps(prefs), latinIME.mSettings.current.mIncognitoModeEnabled)
        if (!offer.showInStrip(offer.stripState(latinIME, hidden))) return null
        offer.markStripShown(prefs)
        offer.sessionOffered = offer.session
        return buildAiDictOffer(parent)
    }

    private fun buildAiDictOffer(parent: ViewGroup): View {
        val prefs = latinIME.prefs()
        return pictureSuggestion(parent, null, latinIME.getString(R.string.ai_dict_strip), gray = false,
            onClose = { helium314.keyboard.latin.ailm.AiDictOffer.decline(prefs) },
            neverText = latinIME.getString(R.string.ai_dict_not_now),
            onNever = { helium314.keyboard.latin.ailm.AiDictOffer.decline(prefs) }) {
            // the window with the size and "Download" (the permission for notifications is asked there)
            latinIME.requestHideSelf(0)
            try {
                latinIME.startActivity(android.content.Intent(latinIME, helium314.keyboard.settings.SettingsActivity2::class.java)
                    .setAction(helium314.keyboard.settings.SettingsActivity.ACTION_OPEN_AI_DICT)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (e: Exception) { Log.w(TAG, "can't open the AI dictionary settings") }
        }
    }

    /** stage 5g-15: the screenshot shown in the strip now (dismissed or inserted: never offered again) */
    private var shownScreenshot: FreshScreenshot.Row? = null

    /**
     * Stage 5g-15, step 2: the newest screenshot of the last 2 minutes, once, only where the field takes pictures and the
     * switch "Offer a fresh screenshot" is on (off by default) and the gallery may be read. Inserted with commitContent
     * through [ScreenshotProvider], without a copy.
     */
    private fun screenshotSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup): View? {
        if (!FreshScreenshot.inBuild()) return null // play-1: not in the store version
        val prefs = latinIME.prefs()
        val sdk = android.os.Build.VERSION.SDK_INT
        val access = FreshScreenshot.access(latinIME)
        fun log(found: Int, offered: Boolean, reason: String): View? {
            val line = FreshScreenshot.logLine(access, found, offered, reason)
            prefs.edit().putString(FreshScreenshot.PREF_LAST_LOG, line).apply()
            Log.i(TAG, line)
            return null
        }
        if (!prefs.getBoolean(Settings.PREF_SUGGEST_SCREENSHOT, Defaults.PREF_SUGGEST_SCREENSHOT)) return log(0, false, "switch off")
        if (sdk < android.os.Build.VERSION_CODES.Q) return log(0, false, "old Android")
        val info = editorInfo ?: return log(0, false, "no field")
        val incognito = latinIME.mSettings.current.mIncognitoModeEnabled
        val fieldTypes = EditorInfoCompat.getContentMimeTypes(info)
        val fieldOk = ImageRules.screenshot(fieldTypes, "image/png", info.inputType, info.imeOptions, incognito)
        if (!fieldOk) return log(0, false, if (ImageRules.hiddenField(info.inputType, info.imeOptions, incognito)) "field hides pictures" else "field takes no pictures")
        val now = System.currentTimeMillis()
        // stage 5g-16: no access / selected photos only: a card (nothing is read, only the time of a change is known)
        val prompt = FreshScreenshot.prompt(true, sdk, access, fieldOk, ScreenshotWatch.lastChangeMs, now,
            prefs.getLong(FreshScreenshot.PREF_ASK_UNTIL, 0L), prefs.getBoolean(FreshScreenshot.PREF_PARTIAL_HINT, false))
        when (prompt) {
            FreshScreenshot.Prompt.ASK -> {
                log(0, true, "card allow")
                return askCard(parent)
            }
            FreshScreenshot.Prompt.PARTIAL_HINT -> {
                prefs.edit().putBoolean(FreshScreenshot.PREF_PARTIAL_HINT, true).apply() // once
                log(0, true, "card partial")
                return partialCard(parent)
            }
            FreshScreenshot.Prompt.NONE -> {}
        }
        if (access == FreshScreenshot.Access.NONE) return log(0, false, "no access")
        val done = prefs.getLong(FreshScreenshot.PREF_DONE, -1L)
        val rows = FreshScreenshot.query(latinIME)
        val row = FreshScreenshot.pick(rows, now, done) ?: return log(rows.size, false, if (rows.isEmpty()) "none found" else "already done")
        val mime = row.mime ?: "image/png"
        val thumb = try {
            val h = parent.height.takeIf { it > 0 } ?: 150
            latinIME.contentResolver.loadThumbnail(FreshScreenshot.galleryUri(row.id), android.util.Size(h * 2, h), null)
        } catch (e: Exception) { Log.w(TAG, "no thumbnail of the screenshot: ${e.javaClass.simpleName}"); return log(rows.size, false, "no thumbnail") }
        FreshScreenshot.offered = row
        shownScreenshot = row
        log(rows.size, true, "ok")
        return pictureSuggestion(parent, thumb, latinIME.getString(R.string.aikb_screenshot_suggestion), gray = false) {
            screenshotDone(row)
            latinIME.mKeyboardActionListener.onContent(InputContentInfoCompat(FreshScreenshot.insertUri(latinIME, row.id),
                ClipDescription("screenshot", arrayOf(mime)), null))
        }
    }

    /** stage 5g-16: "Allow" opens the system dialog (through an invisible activity); × = not now (7 days); "Never" turns the feature off */
    private fun askCard(parent: ViewGroup): View = pictureSuggestion(parent, null, latinIME.getString(R.string.aikb_screenshot_ask), gray = false,
        onClose = { latinIME.prefs().edit().putLong(FreshScreenshot.PREF_ASK_UNTIL, System.currentTimeMillis() + FreshScreenshot.SNOOZE_MS).apply() },
        neverText = latinIME.getString(R.string.aikb_screenshot_never),
        onNever = { latinIME.prefs().edit().putBoolean(Settings.PREF_SUGGEST_SCREENSHOT, false).apply() }) {
        latinIME.startActivity(android.content.Intent(latinIME, ScreenshotPermissionActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** stage 5g-16: selected photos only: new screenshots are not visible; the button opens the permissions of the app */
    private fun partialCard(parent: ViewGroup): View = pictureSuggestion(parent, null, latinIME.getString(R.string.aikb_screenshot_partial_card), gray = false) {
        android.widget.Toast.makeText(latinIME, R.string.aikb_screenshot_partial_toast, android.widget.Toast.LENGTH_LONG).show()
        latinIME.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + latinIME.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * stage 5g-15: a picture in the strip (the thumbnail is kept only in this view): [gray] the field does not take
     * pictures, a tap only dismisses it; else a tap inserts it ([onInsert])
     */
    fun pictureSuggestion(parent: ViewGroup, picture: android.graphics.Bitmap?, label: String, gray: Boolean,
                          onClose: (() -> Unit)? = null, neverText: String? = null, onNever: (() -> Unit)? = null, onInsert: () -> Unit): View {
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val imageView = binding.clipboardSuggestionImage
        imageView.isVisible = picture != null
        imageView.setImageBitmap(picture)
        imageView.contentDescription = label
        textView.text = label
        textView.isSingleLine = false; textView.maxLines = 1 // one line, "…" at the end (singleLine draws nothing in the tests)
        if (gray) { imageView.alpha = 0.35f; textView.alpha = 0.6f }
        val click = View.OnClickListener {
            if (gray) { removeClipboardSuggestion(); return@OnClickListener }
            onInsert()
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        textView.setOnClickListener(click)
        imageView.setOnClickListener(click)
        styleSuggestion(binding, textView, null)
        if (onClose != null) binding.clipboardSuggestionClose.setOnClickListener { onClose(); removeClipboardSuggestion() }
        if (neverText != null && onNever != null) binding.clipboardSuggestionNever.apply {
            isVisible = true
            text = neverText
            setTextColor(latinIME.mSettings.current.mColors.get(ColorType.KEY_TEXT))
            setOnClickListener { onNever(); removeClipboardSuggestion() }
        }
        clipboardSuggestionView = binding.root
        return binding.root
    }

    private fun screenshotDone(row: FreshScreenshot.Row) {
        latinIME.prefs().edit().putLong(FreshScreenshot.PREF_DONE, row.id).apply()
        shownScreenshot = null
    }

    private fun styleSuggestion(binding: ClipboardSuggestionBinding, textView: android.widget.TextView, clipIcon: android.graphics.drawable.Drawable?) {
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.layoutParams.width = textView.lineHeight // scale the icon to the text
        closeButton.layoutParams.height = textView.lineHeight
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.CLIPBOARD_SUGGESTION_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
    }

    private fun clipSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup): View? {
        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val clipIcon = KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        clipIcon?.setBounds(0, 0, textView.lineHeight, textView.lineHeight) // scale the icon to the text
        textView.setCompoundDrawablesRelative(clipIcon, null, null, null)
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (hasText) {
            if (TextUtils.isEmpty(content)) return null
            if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
            val shortenedContent = content.take(200) // for display only
            KeyboardTypeface.applyToTextView(textView)
            textView.text = (if (isClipSensitive(inputType)) "*".repeat(shortenedContent.length) else shortenedContent)
        }
        // stage 5g-15: a picture only where the field takes it (commitContent); passwords and "do not remember": none
        val imageType = clipData.description?.takeIf { hasImage }?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") } } ?: "image/*"
        val imageShow = if (!hasImage || hasText) ImageRules.Show.INSERT else ImageRules.clipImage(EditorInfoCompat.getContentMimeTypes(editorInfo ?: EditorInfo()),
            imageType, inputType, editorInfo?.imeOptions ?: 0, latinIME.mSettings.current.mIncognitoModeEnabled)
        if (imageShow == ImageRules.Show.HIDDEN) return null
        val onClickListener = View.OnClickListener {
            if (imageShow == ImageRules.Show.NOT_ACCEPTED) { removeClipboardSuggestion(); return@OnClickListener }
            dontShowCurrentSuggestion = true
            if (hasText) latinIME.onTextInput(content.toString())
            else pasteImage(timeStamp)
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        textView.setOnClickListener(onClickListener)

        if (hasImage) {
            if (InputTypeUtils.isNumberInputType(inputType)) return null
            val imageView = binding.clipboardSuggestionImage
            imageView.isVisible = true
            if (imageShow == ImageRules.Show.NOT_ACCEPTED && !hasText) {
                imageView.alpha = 0.35f // gray: this field does not take pictures
                textView.text = latinIME.getString(R.string.aikb_image_not_accepted)
                textView.alpha = 0.6f
            }
            try {
                imageView.setImageURI(clipItem.uri)
            } catch (e: Exception) {
                Log.w(TAG, "error setting clipboard image", e) // happens with SecurityException: Permission Denial
                return null
            }
            imageView.setOnClickListener(onClickListener)
        }

        styleSuggestion(binding, textView, clipIcon)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    /**
     * stage 5g-15: the picture of the clipboard goes in with commitContent: from the copy in the clipboard history of the
     * keyboard (the field may read it); without that copy the field pastes the clipboard itself (the system paste)
     */
    private fun pasteImage(timeStamp: Long) {
        val entry = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == timeStamp && it.filename != null }
        val info = entry?.let { runCatching { it.getContentInfo(latinIME) }.getOrNull() }
        if (info != null) latinIME.mKeyboardActionListener.onContent(info)
        else latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
    }

    private fun removeClipboardSuggestion() {
        shownScreenshot?.let { screenshotDone(it) }
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        /**
         * AI Keyboard (docs/VOICE_API.md, section 6): marked as sensitive by the app that copied it (Android 13+), or
         * shaped like an OpenAI API key. Such text is not kept in the clipboard history.
         */
        @JvmStatic
        fun isSecretClip(description: ClipDescription?, text: CharSequence): Boolean =
            description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true ||
                helium314.keyboard.latin.voiceapi.ApiKeyStore.looksLikeKey(text)

        private fun maySaveFromUri(uri: Uri?, context: Context): Boolean {
            val maxSize = context.prefs().getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
            val saveUriData = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
            if (uri == null || !saveUriData) return false
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
                    if (it?.moveToFirst() != true) return false
                    val size = it.getLong(0)
                    return size <= maxSize * 1000000 // maxSize is megabytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "error checking clip size", e) // happens with SecurityException: Permission Denial
                return false
            }
        }
    }
}

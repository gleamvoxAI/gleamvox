/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import helium314.keyboard.latin.common.StringUtilsKt;
import helium314.keyboard.compat.AppWorkarounds;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.InputTypeUtils;

import java.util.ArrayList;
import java.util.Arrays;

import static helium314.keyboard.latin.common.Constants.ImeOption.NO_FLOATING_GESTURE_PREVIEW;
import static helium314.keyboard.latin.common.Constants.ImeOption.NO_MICROPHONE;

import androidx.annotation.NonNull;
import helium314.keyboard.latin.voice.SensitiveInput;

/**
 * Class to hold attributes of the input field.
 */
public final class InputAttributes {
    private final String TAG = InputAttributes.class.getSimpleName();

    final public String mTargetApplicationPackageName;
    final public boolean mInputTypeShouldAutoCorrect;
    final public boolean mIsPasswordField;
    final public boolean mShouldShowSuggestions;
    final public boolean mMayOverrideShowingSuggestions;
    /** ux-5: the field asks for no suggestions (the flag of an ordinary text field, e.g. a search bar), we show them anyway,
     *  but without auto-correction: only a tap on a suggestion changes the word. Never in password, e-mail, address, number,
     *  "do not remember" and web fields. */
    final public boolean mSuggestionsByTapOnly;
    final public boolean mApplicationSpecifiedCompletionOn;
    final public boolean mShouldInsertSpacesAutomatically;
    final public boolean mShouldShowVoiceInputKey;
    /** AI Keyboard: microphone may be shown if there is any voice input (ours or another keyboard) */
    final public boolean mMayShowVoiceInputKey;
    /** AI Keyboard: password, e-mail, non-text or "no learning" field (the address bar is allowed since 5g-8), see {@link SensitiveInput} */
    final public boolean mIsSensitiveField;
    final public boolean mNoLearning;
    /**
     * Whether the floating gesture preview should be disabled. If true, this should override the
     * corresponding keyboard settings preference, always suppressing the floating preview text.
     * {@link helium314.keyboard.latin.settings.SettingsValues#mGestureFloatingPreviewTextEnabled}
     */
    final public boolean mDisableGestureFloatingPreviewText;
    final public boolean mIsGeneralTextInput;
    final public int mInputType;
    final private EditorInfo mEditorInfo;
    final private String mPackageNameForPrivateImeOptions;

    public InputAttributes(final EditorInfo editorInfo, final boolean isFullscreenMode,
            final String packageNameForPrivateImeOptions) {
        mEditorInfo = editorInfo;
        mPackageNameForPrivateImeOptions = packageNameForPrivateImeOptions;
        mTargetApplicationPackageName = null != editorInfo ? editorInfo.packageName : null;
        mInputType = AppWorkarounds.INSTANCE.adjustInputType(null != editorInfo ? editorInfo.inputType : 0, mTargetApplicationPackageName);
        final int inputClass = mInputType & InputType.TYPE_MASK_CLASS;
        mIsPasswordField = InputTypeUtils.isPasswordInputType(mInputType)
                || InputTypeUtils.isVisiblePasswordInputType(mInputType);
        mIsSensitiveField = SensitiveInput.isSensitiveInputType(mInputType)
                || (editorInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            // If we are not looking at a TYPE_CLASS_TEXT field, the following strange
            // cases may arise, so we do a couple sanity checks for them. If it's a
            // TYPE_CLASS_TEXT field, these special cases cannot happen, by construction
            // of the flags.
            if (null == editorInfo) {
                Log.w(TAG, "No editor info for this field. Bug?");
            } else if (InputType.TYPE_NULL == mInputType) {
                // TODO: We should honor TYPE_NULL specification.
                Log.i(TAG, "InputType.TYPE_NULL is specified");
            } else if (inputClass == 0) {
                // TODO: is this check still necessary?
                Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x"
                        + " imeOptions=0x%08x", mInputType, editorInfo.imeOptions));
            }
            mShouldShowSuggestions = false;
            mSuggestionsByTapOnly = false;
            mMayOverrideShowingSuggestions = false;
            mInputTypeShouldAutoCorrect = false;
            mApplicationSpecifiedCompletionOn = false;
            mShouldInsertSpacesAutomatically = false;
            mShouldShowVoiceInputKey = false;
            mMayShowVoiceInputKey = false;
            mDisableGestureFloatingPreviewText = false;
            mIsGeneralTextInput = false;
            mNoLearning = false;
            return;
        }

        final int variation = mInputType & InputType.TYPE_MASK_VARIATION;
        final boolean flagNoSuggestions = 0 != (mInputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final boolean flagMultiLine = 0 != (mInputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final boolean flagAutoCorrect = 0 != (mInputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        final boolean flagAutoComplete = 0 != (mInputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);

        // TODO: Have a helper method in InputTypeUtils
        // Make sure that passwords are not displayed in {@link SuggestionStripView}.
        // ux-5: a field with the "no suggestions" flag still gets the strip (tap only), unless it is a field with private or special text
        mSuggestionsByTapOnly = flagNoSuggestions && allowsTapOnlySuggestions(mInputType, mIsPasswordField, mIsSensitiveField, editorInfo);
        mShouldShowSuggestions = !mIsPasswordField && (!flagNoSuggestions || mSuggestionsByTapOnly);
        mMayOverrideShowingSuggestions = !mIsPasswordField;

        mShouldInsertSpacesAutomatically = InputTypeUtils.isAutoSpaceFriendlyType(mInputType);

        // AI Keyboard: no microphone at all in sensitive fields (neither our voice input nor another keyboard)
        final boolean noMicrophone = mIsPasswordField
                || mIsSensitiveField
                || InputTypeUtils.isEmailVariation(variation)
                || hasNoMicrophoneKeyOption()
                || !RichInputMethodManager.isInitialized(); // avoid crash when only using spell checker
        mMayShowVoiceInputKey = !noMicrophone;
        mShouldShowVoiceInputKey = !noMicrophone && RichInputMethodManager.getInstance().isShortcutImeReady();

        mDisableGestureFloatingPreviewText = InputAttributes.inPrivateImeOptions(
                mPackageNameForPrivateImeOptions, NO_FLOATING_GESTURE_PREVIEW, editorInfo);

        // autocorrect if explicitly wanted, but also for most multi-line input types (like AOSP keyboard)
        // originally, URI and email were always excluded from autocorrect (in Suggest.java), but this is
        //  and unexpected place, and if the input field explicitly requests autocorrect we should follow the flag
        mInputTypeShouldAutoCorrect = flagAutoCorrect || (
                flagMultiLine
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && variation != InputType.TYPE_TEXT_VARIATION_URI
                && !InputTypeUtils.isEmailVariation(variation)
                && !flagNoSuggestions
        );

        mApplicationSpecifiedCompletionOn = flagAutoComplete && isFullscreenMode;

        // If we come here, inputClass is always TYPE_CLASS_TEXT
        mIsGeneralTextInput = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != variation
                && InputType.TYPE_TEXT_VARIATION_PASSWORD != variation
                && InputType.TYPE_TEXT_VARIATION_PHONETIC != variation
                && InputType.TYPE_TEXT_VARIATION_URI != variation
                && InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != variation
                && InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS != variation
                && InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != variation;


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            mNoLearning = (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        else
            mNoLearning = false;
    }

    /** ux-5: ordinary text (search bar, chat, notes) only: not password, e-mail, address, phonetic, web field (the page changes the composing text),
     *  "do not remember" (incognito) or any non-text field. */
    public static boolean allowsTapOnlySuggestions(final int inputType, final boolean isPassword, final boolean isSensitive,
            final EditorInfo editorInfo) {
        if (isPassword || isSensitive) return false;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        if (editorInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false;
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        // ux-8 (the owner: the search bar of Chrome/Google showed nothing): the address bar of a browser is a search bar
        // too, people type ordinary words there; suggestions by tap, no auto-correction (an address must stay as typed)
        return variation == InputType.TYPE_TEXT_VARIATION_NORMAL
                || variation == InputType.TYPE_TEXT_VARIATION_URI
                || variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                || variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
                || variation == InputType.TYPE_TEXT_VARIATION_FILTER
                || variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT;
    }

    /**
     * ux-8: in the address / search bar of a browser only ordinary words are learned: never what looks like an address
     * or an e-mail ("." "/" "@" ":")
     */
    public static boolean mayLearnInAddressBar(final int inputType, final String word) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (inputType & InputType.TYPE_MASK_VARIATION) != InputType.TYPE_TEXT_VARIATION_URI) return true;
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            if (c == '.' || c == '/' || c == '@' || c == ':') return false;
        }
        return true;
    }

    public boolean isTypeNull() {
        return InputType.TYPE_NULL == mInputType;
    }

    public boolean isSameInputType(final EditorInfo editorInfo) {
        return editorInfo.inputType == mInputType && mEditorInfo != null
                && (mEditorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) == (editorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII);
    }

    private boolean hasNoMicrophoneKeyOption() {
        return InputAttributes.inPrivateImeOptions(mPackageNameForPrivateImeOptions, NO_MICROPHONE, mEditorInfo);
    }

    @SuppressWarnings("unused")
    private void dumpFlags(final int inputType) {
        final int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        final String inputClassString = toInputClassString(inputClass);
        final String variationString = toVariationString(
                inputClass, inputType & InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final String flagsString = toFlagsString(inputType & InputType.TYPE_MASK_FLAGS);
        Log.i(TAG, "Input class: " + inputClassString);
        Log.i(TAG, "Variation: " + variationString);
        Log.i(TAG, "Flags: " + flagsString);
    }

    private static String toInputClassString(final int inputClass) {
        return switch (inputClass) {
            case InputType.TYPE_CLASS_TEXT -> "TYPE_CLASS_TEXT";
            case InputType.TYPE_CLASS_PHONE -> "TYPE_CLASS_PHONE";
            case InputType.TYPE_CLASS_NUMBER -> "TYPE_CLASS_NUMBER";
            case InputType.TYPE_CLASS_DATETIME -> "TYPE_CLASS_DATETIME";
            default -> String.format("unknownInputClass<0x%08x>", inputClass);
        };
    }

    private static String toVariationString(final int inputClass, final int variation) {
        return switch (inputClass) {
            case InputType.TYPE_CLASS_TEXT -> toTextVariationString(variation);
            case InputType.TYPE_CLASS_NUMBER -> toNumberVariationString(variation);
            case InputType.TYPE_CLASS_DATETIME -> toDatetimeVariationString(variation);
            default -> "";
        };
    }

    private static String toTextVariationString(final int variation) {
        return switch (variation) {
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> " TYPE_TEXT_VARIATION_EMAIL_ADDRESS";
            case InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "TYPE_TEXT_VARIATION_EMAIL_SUBJECT";
            case InputType.TYPE_TEXT_VARIATION_FILTER -> "TYPE_TEXT_VARIATION_FILTER";
            case InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "TYPE_TEXT_VARIATION_LONG_MESSAGE";
            case InputType.TYPE_TEXT_VARIATION_NORMAL -> "TYPE_TEXT_VARIATION_NORMAL";
            case InputType.TYPE_TEXT_VARIATION_PASSWORD -> "TYPE_TEXT_VARIATION_PASSWORD";
            case InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "TYPE_TEXT_VARIATION_PERSON_NAME";
            case InputType.TYPE_TEXT_VARIATION_PHONETIC -> "TYPE_TEXT_VARIATION_PHONETIC";
            case InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "TYPE_TEXT_VARIATION_POSTAL_ADDRESS";
            case InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "TYPE_TEXT_VARIATION_SHORT_MESSAGE";
            case InputType.TYPE_TEXT_VARIATION_URI -> "TYPE_TEXT_VARIATION_URI";
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD";
            case InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "TYPE_TEXT_VARIATION_WEB_EDIT_TEXT";
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS";
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "TYPE_TEXT_VARIATION_WEB_PASSWORD";
            default -> String.format("unknownVariation<0x%08x>", variation);
        };
    }

    private static String toNumberVariationString(final int variation) {
        return switch (variation) {
            case InputType.TYPE_NUMBER_VARIATION_NORMAL -> "TYPE_NUMBER_VARIATION_NORMAL";
            case InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "TYPE_NUMBER_VARIATION_PASSWORD";
            default -> String.format("unknownVariation<0x%08x>", variation);
        };
    }

    private static String toDatetimeVariationString(final int variation) {
        return switch (variation) {
            case InputType.TYPE_DATETIME_VARIATION_NORMAL -> "TYPE_DATETIME_VARIATION_NORMAL";
            case InputType.TYPE_DATETIME_VARIATION_DATE -> "TYPE_DATETIME_VARIATION_DATE";
            case InputType.TYPE_DATETIME_VARIATION_TIME -> "TYPE_DATETIME_VARIATION_TIME";
            default -> String.format("unknownVariation<0x%08x>", variation);
        };
    }

    private static String toFlagsString(final int flags) {
        final ArrayList<String> flagsArray = new ArrayList<>();
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
            flagsArray.add("TYPE_TEXT_FLAG_NO_SUGGESTIONS");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_MULTI_LINE))
            flagsArray.add("TYPE_TEXT_FLAG_MULTI_LINE");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE))
            flagsArray.add("TYPE_TEXT_FLAG_IME_MULTI_LINE");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_CAP_WORDS))
            flagsArray.add("TYPE_TEXT_FLAG_CAP_WORDS");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
            flagsArray.add("TYPE_TEXT_FLAG_CAP_SENTENCES");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
            flagsArray.add("TYPE_TEXT_FLAG_CAP_CHARACTERS");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
            flagsArray.add("TYPE_TEXT_FLAG_AUTO_CORRECT");
        if (0 != (flags & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE))
            flagsArray.add("TYPE_TEXT_FLAG_AUTO_COMPLETE");
        return flagsArray.isEmpty() ? "" : Arrays.toString(flagsArray.toArray());
    }

    // Pretty print
    @NonNull
    @Override
    public String toString() {
        return String.format(
                "%s: inputType=0x%08x%s%s%s%s%s targetApp=%s\n", getClass().getSimpleName(),
                mInputType,
                (mInputTypeShouldAutoCorrect ? " shouldAutoCorrect" : ""),
                (mIsPasswordField ? " password" : ""),
                (mShouldShowSuggestions ? " shouldShowSuggestions" : ""),
                (mApplicationSpecifiedCompletionOn ? " appSpecified" : ""),
                (mShouldInsertSpacesAutomatically ? " insertSpaces" : ""),
                mTargetApplicationPackageName);
    }

    public static boolean inPrivateImeOptions(final String packageName, final String key, final EditorInfo editorInfo) {
        if (editorInfo == null) return false;
        final String findingKey = (packageName != null) ? packageName + "." + key : key;
        return StringUtilsKt.containsValueWhenSplit(editorInfo.privateImeOptions, findingKey, ",");
    }
}

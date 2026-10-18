/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.keyboard.KeyboardTheme;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.PunctuationSuggestions;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayList;

final class SuggestionStripLayoutHelper {
    private static final int DEFAULT_SUGGESTIONS_COUNT_IN_STRIP = 3;
    private static final float DEFAULT_CENTER_SUGGESTION_PERCENTILE = 0.40f;
    private static final int DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2;
    private static final int PUNCTUATIONS_IN_STRIP = 5;
    private static final float MIN_TEXT_XSCALE = 0.70f;

    public final int mPadding;
    public final int mDividerWidth;
    public final int mSuggestionsStripHeight;
    private final int mSuggestionsCountInStrip;
    public final int mMoreSuggestionsRowHeight;
    private int mMaxMoreSuggestionsRow;
    public final float mMinMoreSuggestionsWidth;
    public final int mMoreSuggestionsBottomGap;
    private boolean mMoreSuggestionsAvailable;

    // The index of these {@link ArrayList} is the position in the suggestion strip. The indices
    // increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
    // The position of the most important suggestion is in {@link #mCenterPositionInStrip}
    private final ArrayList<TextView> mWordViews;
    private final ArrayList<View> mDividerViews;
    private final ArrayList<TextView> mDebugInfoViews;

    private final int mColorValidTypedWord;
    private final int mColorTypedWord;
    private final int mColorAutoCorrect;
    private final int mColorSuggested;
    private final float mAlphaObsoleted;
    private final float mCenterSuggestionWeight;
    private final int mCenterPositionInStrip;
    private final int mTypedWordPositionWhenAutocorrect;
    private final Drawable mMoreSuggestionsHint;
    /** AI Keyboard style: thin dividers between suggestions (no bar and no dots under the words) */
    private final boolean mAikbStyle;
    private static final String MORE_SUGGESTIONS_HINT = "…";

    private static final CharacterStyle BOLD_SPAN = new StyleSpan(Typeface.BOLD);
    private static final CharacterStyle UNDERLINE_SPAN = new UnderlineSpan();

    private final int mSuggestionStripOptions;
    // These constants are the flag values of
    // {@link R.styleable#SuggestionStripView_suggestionStripOptions} attribute.
    private static final int AUTO_CORRECT_BOLD = 0x01;
    private static final int AUTO_CORRECT_UNDERLINE = 0x02;
    private static final int VALID_TYPED_WORD_BOLD = 0x04;

    public SuggestionStripLayoutHelper(final Context context, final AttributeSet attrs,
            final int defStyle, final ArrayList<TextView> wordViews,
            final ArrayList<View> dividerViews, final ArrayList<TextView> debugInfoViews) {
        mWordViews = wordViews;
        mDividerViews = dividerViews;
        mDebugInfoViews = debugInfoViews;

        final Colors themeColors = Settings.getValues().mColors;
        mAikbStyle = KeyboardTheme.STYLE_AIKB.equals(themeColors.getThemeStyle());
        if (mAikbStyle) {
            final float density = context.getResources().getDisplayMetrics().density;
            // divider: a bit lighter than the strip in dark themes, a bit darker in light themes
            final int stripColor = themeColors.get(ColorType.STRIP_BACKGROUND);
            final boolean lightStrip = ColorUtils.calculateLuminance(stripColor) > 0.5;
            final int dividerColor = ColorUtils.blendARGB(stripColor, lightStrip ? Color.BLACK : Color.WHITE, 0.12f);
            for (final View v : dividerViews) {
                final GradientDrawable line = new GradientDrawable();
                line.setColor(dividerColor);
                line.setSize(Math.max(1, Math.round(density)), Math.round(24 * density));
                ((ImageView) v).setImageDrawable(line);
            }
        }

        final TextView wordView = wordViews.get(0);
        final View dividerView = dividerViews.get(0);
        mPadding = wordView.getCompoundPaddingLeft() + wordView.getCompoundPaddingRight();
        dividerView.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mDividerWidth = dividerView.getMeasuredWidth();

        final Resources res = wordView.getResources();
        mSuggestionsStripHeight = res.getDimensionPixelSize(
                R.dimen.config_suggestions_strip_height);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.SuggestionStripView, defStyle, R.style.SuggestionStripView);
        mSuggestionStripOptions = a.getInt(R.styleable.SuggestionStripView_suggestionStripOptions, 0);
        mAlphaObsoleted = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_alphaObsoleted, 1.0f);

        final Colors colors = Settings.getValues().mColors;
        mColorValidTypedWord = colors.get(ColorType.SUGGESTION_VALID_WORD);
        mColorTypedWord = colors.get(ColorType.SUGGESTION_TYPED_WORD);
        mColorAutoCorrect = colors.get(ColorType.SUGGESTION_AUTO_CORRECT);
        mColorSuggested = colors.get(ColorType.SUGGESTED_WORD);
        final int colorMoreSuggestionsHint = colors.get(ColorType.MORE_SUGGESTIONS_HINT);

        mSuggestionsCountInStrip = a.getInt(
                R.styleable.SuggestionStripView_suggestionsCountInStrip,
                DEFAULT_SUGGESTIONS_COUNT_IN_STRIP);
        mCenterSuggestionWeight = ResourceUtils.getFraction(a,
                R.styleable.SuggestionStripView_centerSuggestionPercentile,
                DEFAULT_CENTER_SUGGESTION_PERCENTILE);
        mMaxMoreSuggestionsRow = a.getInt(
                R.styleable.SuggestionStripView_maxMoreSuggestionsRow,
                DEFAULT_MAX_MORE_SUGGESTIONS_ROW);
        mMinMoreSuggestionsWidth = ResourceUtils.getFraction(a,
                R.styleable.SuggestionStripView_minMoreSuggestionsWidth, 1.0f);
        a.recycle();

        mMoreSuggestionsHint = getMoreSuggestionsHint(res,
                res.getDimension(R.dimen.config_more_suggestions_hint_text_size),
                colorMoreSuggestionsHint);
        mCenterPositionInStrip = mSuggestionsCountInStrip / 2;
        // Assuming there are at least three suggestions. Also, note that the suggestions are
        // laid out according to script direction, so this is left of the center for LTR scripts
        // and right of the center for RTL scripts.
        mTypedWordPositionWhenAutocorrect = mCenterPositionInStrip - 1;
        mMoreSuggestionsBottomGap = res.getDimensionPixelOffset(
                R.dimen.config_more_suggestions_bottom_gap);
        mMoreSuggestionsRowHeight = res.getDimensionPixelSize(
                R.dimen.config_more_suggestions_row_height);
    }

    public int getMaxMoreSuggestionsRow() {
        return mMaxMoreSuggestionsRow;
    }

    private int getMoreSuggestionsHeight() {
        return mMaxMoreSuggestionsRow * mMoreSuggestionsRowHeight + mMoreSuggestionsBottomGap;
    }

    public void setMoreSuggestionsHeight(final int remainingHeight) {
        final int currentHeight = getMoreSuggestionsHeight();
        if (currentHeight <= remainingHeight) {
            return;
        }
        mMaxMoreSuggestionsRow = (remainingHeight - mMoreSuggestionsBottomGap) / mMoreSuggestionsRowHeight;
    }

    private static Drawable getMoreSuggestionsHint(final Resources res, final float textSize, final int color) {
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Align.CENTER);
        paint.setTextSize(textSize);
        paint.setColor(color);
        final Rect bounds = new Rect();
        paint.getTextBounds(MORE_SUGGESTIONS_HINT, 0, MORE_SUGGESTIONS_HINT.length(), bounds);
        final int width = Math.round(bounds.width() + 0.5f);
        final int height = Math.round(bounds.height() + 0.5f);
        final Bitmap buffer = Bitmap.createBitmap(width, (height * 3 / 2), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(buffer);
        canvas.drawText(MORE_SUGGESTIONS_HINT, width / 2, height, paint);
        BitmapDrawable bitmapDrawable = new BitmapDrawable(res, buffer);
        bitmapDrawable.setTargetDensity(canvas);
        return bitmapDrawable;
    }

    private CharSequence getStyledSuggestedWord(final SuggestedWords suggestedWords,
            final int indexInSuggestedWords) {
        if (indexInSuggestedWords >= suggestedWords.size()) {
            return null;
        }
        final String word = suggestedWords.getLabel(indexInSuggestedWords);
        // TODO: don't use the index to decide whether this is the auto-correction/typed word, as
        // this is brittle
        final boolean isAutoCorrection = suggestedWords.mWillAutoCorrect
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION;
        final boolean isTypedWordValid = suggestedWords.mTypedWordValid
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD;
        if (!isAutoCorrection && !isTypedWordValid) {
            return word;
        }

        final Spannable spannedWord = new SpannableString(word);
        final int options = mSuggestionStripOptions;
        if ((isAutoCorrection && (options & AUTO_CORRECT_BOLD) != 0)
                || (isTypedWordValid && (options & VALID_TYPED_WORD_BOLD) != 0)) {
            addStyleSpan(spannedWord, BOLD_SPAN);
        }
        if (isAutoCorrection && (options & AUTO_CORRECT_UNDERLINE) != 0) {
            addStyleSpan(spannedWord, UNDERLINE_SPAN);
        }
        return spannedWord;
    }

    /**
     * Convert an index of {@link SuggestedWords} to position in the suggestion strip.
     * @param indexInSuggestedWords the index of {@link SuggestedWords}.
     * @param suggestedWords the suggested words list
     * @return Non-negative integer of the position in the suggestion strip.
     *         Negative integer if the word of the index shouldn't be shown on the suggestion strip.
     */
    private int getPositionInSuggestionStrip(final int indexInSuggestedWords,
            final SuggestedWords suggestedWords) {
        final SettingsValues settingsValues = Settings.getValues();
        final boolean shouldOmitTypedWord = shouldOmitTypedWord(suggestedWords.mInputStyle,
                settingsValues.mGestureFloatingPreviewTextEnabled, true);
        if (shouldOmitTypedWord && suggestedWords.size() == 1 && !suggestedWords.mWillAutoCorrect
                && suggestedWords.mTypedWordInfo != null && !suggestedWords.mTypedWordInfo.mWord.isEmpty()
                && suggestedWords.getInfo(0).mWord.equals(suggestedWords.mTypedWordInfo.mWord)) {
            return indexInSuggestedWords == 0 ? mCenterPositionInStrip : -1; // ux-1: nothing else to offer: the typed word alone
        }
        if (shouldOmitTypedWord && typedWordGoesLeft(suggestedWords)) {
            // ux-1: the typed word is always visible: left of the center, the best suggestion in the center, the next to its
            // right (a third suggestion would not be in the strip; "more suggestions" start after it)
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD) return mTypedWordPositionWhenAutocorrect;
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION) return mCenterPositionInStrip;
            return indexInSuggestedWords == 2 ? mCenterPositionInStrip + 1 : -1;
        }
        return getPositionInSuggestionStrip(indexInSuggestedWords, suggestedWords.mWillAutoCorrect,
                shouldOmitTypedWord, mCenterPositionInStrip, mTypedWordPositionWhenAutocorrect);
    }

    /**
     * ux-1: while typing, the typed word (index 0) is hidden in the old strip unless there is a copy of it in the list (an
     * incoming autocorrection, a capitalised word, the setting "center the suggestion text to enter"). Without a copy
     * it would be invisible: then it is shown at the left of the center.
     */
    static boolean typedWordGoesLeft(final SuggestedWords words) {
        final SuggestedWordInfo typed = words.mTypedWordInfo;
        if (typed == null || typed.mWord.isEmpty() || words.mWillAutoCorrect || words.size() < 2) return false;
        if (words.size() == 0 || !words.getInfo(SuggestedWords.INDEX_OF_TYPED_WORD).mWord.equals(typed.mWord)) return false;
        for (int i = 1; i < words.size(); i++) {
            if (words.getInfo(i).mWord.equalsIgnoreCase(typed.mWord)) return false; // shown as that copy
        }
        return true;
    }

    static boolean shouldOmitTypedWord(final int inputStyle,
            final boolean gestureFloatingPreviewTextEnabled,
            final boolean shouldShowUiToAcceptTypedWord) {
        final boolean omitTypedWord = (inputStyle == SuggestedWords.INPUT_STYLE_TYPING)
                || (inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH)
                || (inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH && gestureFloatingPreviewTextEnabled);
        return shouldShowUiToAcceptTypedWord && omitTypedWord;
    }

    static int getPositionInSuggestionStrip(final int indexInSuggestedWords,
            final boolean willAutoCorrect, final boolean omitTypedWord,
            final int centerPositionInStrip, final int typedWordPositionWhenAutoCorrect) {
        if (omitTypedWord) {
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD) {
                // Ignore.
                return -1;
            }
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
                // Center in the suggestion strip.
                return centerPositionInStrip;
            }
            // If neither of those, the order in the suggestion strip is left of the center first
            // then right of the center, to both edges of the suggestion strip.
            // For example, center-1, center+1, center-2, center+2, and so on.
            final int offsetFromCenter = (indexInSuggestedWords % 2) == 0 ? -(indexInSuggestedWords / 2) : (indexInSuggestedWords / 2);
            return centerPositionInStrip + offsetFromCenter;
        }
        final int indexToDisplayMostImportantSuggestion;
        final int indexToDisplaySecondMostImportantSuggestion;
        if (willAutoCorrect) {
            indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION;
            indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD;
        } else {
            indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD;
            indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION;
        }
        if (indexInSuggestedWords == indexToDisplayMostImportantSuggestion) {
            // Center in the suggestion strip.
            return centerPositionInStrip;
        }
        if (indexInSuggestedWords == indexToDisplaySecondMostImportantSuggestion) {
            // Center-1.
            return typedWordPositionWhenAutoCorrect;
        }
        // If neither of those, the order in the suggestion strip is right of the center first
        // then left of the center, to both edges of the suggestion strip.
        // For example, Center+1, center-2, center+2, center-3, and so on.
        final int n = indexInSuggestedWords + 1;
        final int offsetFromCenter = (n % 2) == 0 ? -(n / 2) : (n / 2);
        return centerPositionInStrip + offsetFromCenter;
    }

    private int getSuggestionTextColor(final SuggestedWords suggestedWords,
            final int indexInSuggestedWords) {
        // Use identity for strings, not #equals : it's the typed word if it's the same object
        final boolean isTypedWord = suggestedWords.getInfo(indexInSuggestedWords).isKindOf(SuggestedWordInfo.KIND_TYPED);

        final int color;
        if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION && suggestedWords.mWillAutoCorrect) {
            color = mColorAutoCorrect;
        } else if (isTypedWord && suggestedWords.mTypedWordValid) {
            color = mColorValidTypedWord;
        } else if (isTypedWord) {
            color = mColorTypedWord;
        } else {
            color = mColorSuggested;
        }
        if (suggestedWords.mIsObsoleteSuggestions && !isTypedWord) {
            return applyAlpha(color, mAlphaObsoleted);
        }
        return color;
    }

    private static int applyAlpha(final int color, final float alpha) {
        final int newAlpha = (int)(Color.alpha(color) * alpha);
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void addDivider(final ViewGroup stripView, final View dividerView) {
        stripView.addView(dividerView);
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)dividerView.getLayoutParams();
        params.gravity = Gravity.CENTER;
    }

    /**
     * Layout suggestions to the suggestions strip. And returns the start index of more
     * suggestions.
     *
     * @param suggestedWords suggestions to be shown in the suggestions strip.
     * @param stripView the suggestions strip view.
     * @param placerView the view where the debug info will be placed.
     * @return the start index of more suggestions.
     */
    public int layoutAndReturnStartIndexOfMoreSuggestions(
            final Context context,
            final SuggestedWords suggestedWords,
            final ViewGroup stripView,
            final ViewGroup placerView) {
        if (suggestedWords.isPunctuationSuggestions()) {
            return layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
                    (PunctuationSuggestions)suggestedWords, stripView);
        }

        final int wordCountToShow = suggestedWords.getWordCountToShow();
        final int startIndexOfMoreSuggestions = setupWordViewsAndReturnStartIndexOfMoreSuggestions(
                suggestedWords, mSuggestionsCountInStrip);
        final TextView centerWordView = mWordViews.get(mCenterPositionInStrip);
        final int stripWidth = stripView.getWidth();
        // ux-1: long words are shown with a smaller font (down to a minimum) instead of being cut with "…"; the typed
        // word is never cut: when the words do not fit, the less important suggestions go away, not the typed word
        final int[] fitted = fitWordsToStrip(suggestedWords, stripWidth);
        int visible = 0;
        for (int i = 0; i < mSuggestionsCountInStrip; i++) if (!TextUtils.isEmpty(mWordViews.get(i).getText())) visible++;
        if (wordCountToShow == 1 || visible <= 1) {
            // Layout only the most relevant suggested word at the center of the suggestion strip
            // by consolidating all slots in the strip.
            final int countInStrip = 1;
            mMoreSuggestionsAvailable = (wordCountToShow > countInStrip);
            layoutWord(context, mCenterPositionInStrip, mIsTypedWordAt[mCenterPositionInStrip] ? NO_LIMIT : stripWidth - mPadding);
            stripView.addView(centerWordView);
            setLayoutWeight(centerWordView, 1.0f, ViewGroup.LayoutParams.MATCH_PARENT);
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(mCenterPositionInStrip, placerView, stripWidth);
            }
            final Integer lastIndex = (Integer)centerWordView.getTag();
            return (lastIndex == null ? 0 : lastIndex) + 1;
        }

        final int countInStrip = mSuggestionsCountInStrip;
        mMoreSuggestionsAvailable = (wordCountToShow > countInStrip);
        @SuppressWarnings("unused")
        int x = 0;
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                final View divider = mDividerViews.get(positionInStrip);
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, divider);
                if (mAikbStyle) { // keep the width, but show the line only between two real suggestions
                    divider.setVisibility(TextUtils.isEmpty(mWordViews.get(positionInStrip - 1).getText())
                            || TextUtils.isEmpty(mWordViews.get(positionInStrip).getText())
                            ? View.INVISIBLE : View.VISIBLE);
                }
                x += divider.getMeasuredWidth();
            }

            final int width = mIsTypedWordAt[positionInStrip] ? NO_LIMIT : Math.max(1, fitted[positionInStrip] - mPadding);
            final TextView wordView = layoutWord(context, positionInStrip, width);
            stripView.addView(wordView);
            setLayoutWeight(wordView, Math.max(0.0001f, fitted[positionInStrip] / (float) Math.max(1, stripWidth)), ViewGroup.LayoutParams.MATCH_PARENT);
            x += wordView.getMeasuredWidth();

            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(positionInStrip, placerView, (int) stripView.getX() + x);
            }
        }
        return startIndexOfMoreSuggestions;
    }

    /**
     * Format appropriately the suggested word in {@link #mWordViews} specified by
     * <code>positionInStrip</code>. When the suggested word doesn't exist, the corresponding
     * {@link TextView} will be disabled and never respond to user interaction. The suggested word
     * may be shrunk or ellipsized to fit in the specified width.
     * <p>
     * The <code>positionInStrip</code> argument is the index in the suggestion strip. The indices
     * increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
     * The position of the most important suggestion is in {@link #mCenterPositionInStrip}. This
     * usually doesn't match the index in <code>suggedtedWords</code> -- see
     * {@link #getPositionInSuggestionStrip(int,SuggestedWords)}.
     *
     * @param positionInStrip the position in the suggestion strip.
     * @param width the maximum width for layout in pixels.
     * @return the {@link TextView} containing the suggested word appropriately formatted.
     */
    private TextView layoutWord(final Context context, final int positionInStrip, final int width) {
        final TextView wordView = mWordViews.get(positionInStrip);
        final CharSequence word = wordView.getText();
        if (mAikbStyle) {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        } else if (positionInStrip == mCenterPositionInStrip && mMoreSuggestionsAvailable) {
            // TODO: This "more suggestions hint" should have a nicely designed icon.
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, mMoreSuggestionsHint);
            // HACK: Align with other TextViews that have no compound drawables.
            wordView.setCompoundDrawablePadding(-mMoreSuggestionsHint.getIntrinsicHeight());
        } else {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
        // {@link StyleSpan} in a content description may cause an issue of TTS/TalkBack.
        // Use a simple {@link String} to avoid the issue.
        wordView.setContentDescription(
                TextUtils.isEmpty(word)
                    ? context.getResources().getString(R.string.spoken_empty_suggestion)
                    : word.toString());
        final CharSequence text = getEllipsizedTextWithSettingScaleX(
                word, width, wordView.getPaint());
        final float scaleX = wordView.getTextScaleX();
        wordView.setText(text); // TextView.setText() resets text scale x to 1.0.
        wordView.setTextScaleX(scaleX);
        // A <code>wordView</code> should be disabled when <code>word</code> is empty in order to
        // make it unclickable.
        // With accessibility touch exploration on, <code>wordView</code> should be enabled even
        // when it is empty to avoid announcing as "disabled".
        wordView.setEnabled(!TextUtils.isEmpty(word)
                || AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled());
        return wordView;
    }

    private void layoutDebugInfo(final int positionInStrip, final ViewGroup placerView,
            final int x) {
        final TextView debugInfoView = mDebugInfoViews.get(positionInStrip);
        final CharSequence debugInfo = debugInfoView.getText();
        if (debugInfo == null) {
            return;
        }
        placerView.addView(debugInfoView);
        debugInfoView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int infoWidth = debugInfoView.getMeasuredWidth();
        ViewLayoutUtils.placeViewAt(debugInfoView, x - infoWidth, 0, infoWidth, debugInfoView.getMeasuredHeight());
    }

    /** a width that never cuts the word (used for the typed word) */
    private static final int NO_LIMIT = Integer.MAX_VALUE / 4;
    private static final float[] FONT_STEPS = {1.0f, 0.9f, 0.82f, 0.74f, 0.66f, 0.6f};
    /** the font size of the words as the theme made it (the fit changes the size of the views, this is the reference) */
    private float mBaseTextSize = 0f;
    /** per position in the strip: the word there is the word the person typed (laid out by the last fit) */
    private final boolean[] mIsTypedWordAt = new boolean[8];

    /**
     * ux-1: decides the font size and the width of every slot. All words that fit with the normal font keep it; else the
     * font gets smaller (to 60 %); if it still does not fit, the suggestions that are neither the typed word nor the
     * center one are left out (2 suggestions instead of 3); the typed word is shown whole, in typographic quotes when the
     * dictionary does not know it. Returns the width of each slot in pixels (0 for an empty one); a slot's width includes
     * its padding. Sets the text size of the word views.
     */
    private int[] fitWordsToStrip(final SuggestedWords suggestedWords, final int stripWidth) {
        final int count = mSuggestionsCountInStrip;
        if (mBaseTextSize <= 0f) mBaseTextSize = mWordViews.get(0).getTextSize();
        java.util.Arrays.fill(mIsTypedWordAt, false);
        final String typed = suggestedWords.mTypedWordInfo != null ? suggestedWords.mTypedWordInfo.mWord : null;
        for (int pos = 0; pos < count; pos++) {
            final TextView v = mWordViews.get(pos);
            final CharSequence text = v.getText();
            final Object tag = v.getTag();
            if (TextUtils.isEmpty(text) || !(tag instanceof Integer)) continue;
            final int index = (Integer) tag;
            if (typed != null && !typed.isEmpty() && index < suggestedWords.size()
                    && suggestedWords.getInfo(index).mWord.equalsIgnoreCase(typed)) {
                mIsTypedWordAt[pos] = true;
                // the typed word is quoted when the dictionary does not know it (shows what exactly will be written)
                if (!suggestedWords.mTypedWordValid && !(text.length() > 1 && text.charAt(0) == '\u201C')) {
                    v.setText("\u201C" + text + "\u201D");
                }
            }
        }
        final int dividers = mDividerWidth * (count - 1);
        final int avail = Math.max(1, stripWidth - dividers);
        final int[] result = new int[count];
        float chosen = FONT_STEPS[FONT_STEPS.length - 1];
        final float[] need = new float[count];
        while (true) {
            boolean fits = false;
            for (final float step : FONT_STEPS) {
                float sum = 0;
                for (int pos = 0; pos < count; pos++) {
                    final TextView v = mWordViews.get(pos);
                    final CharSequence text = v.getText();
                    if (TextUtils.isEmpty(text)) { need[pos] = 0; continue; }
                    final TextPaint paint = new TextPaint(v.getPaint());
                    paint.setTextScaleX(1.0f);
                    paint.setTextSize(mBaseTextSize * step);
                    // bold (the autocorrection) is a little wider than the measured regular text
                    need[pos] = paint.measureText(text.toString()) * (pos == mCenterPositionInStrip ? 1.08f : 1.0f) + mPadding;
                    sum += need[pos];
                }
                if (sum <= avail) { chosen = step; fits = true; break; }
            }
            if (fits) break;
            // not even with the smallest font: leave out a suggestion that is neither the typed word nor the center one
            int drop = -1;
            for (int pos = 0; pos < count; pos++) {
                if (need[pos] > 0 && pos != mCenterPositionInStrip && !mIsTypedWordAt[pos]) drop = pos;
            }
            if (drop < 0) { chosen = FONT_STEPS[FONT_STEPS.length - 1]; break; }
            final TextView v = mWordViews.get(drop);
            v.setText(null);
            v.setTag(null);
            need[drop] = 0;
        }
        for (final TextView v : mWordViews) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, mBaseTextSize * chosen);
        // widths: what each word needs, the rest is shared in the proportion of the usual weights
        float needSum = 0, weightSum = 0;
        for (int pos = 0; pos < count; pos++) {
            final CharSequence text = mWordViews.get(pos).getText();
            need[pos] = TextUtils.isEmpty(text) ? 0 : need[pos];
            needSum += need[pos];
            if (need[pos] > 0) weightSum += getSuggestionWeight(pos);
        }
        if (needSum <= avail) {
            final float spare = avail - needSum;
            for (int pos = 0; pos < count; pos++)
                result[pos] = need[pos] <= 0 ? 0 : (int) (need[pos] + spare * getSuggestionWeight(pos) / Math.max(0.0001f, weightSum));
        } else {
            // pathological (very long words even at the smallest font): the typed word whole, the others share the rest
            float typedNeed = 0, others = 0;
            for (int pos = 0; pos < count; pos++) { if (mIsTypedWordAt[pos]) typedNeed += need[pos]; else others += need[pos]; }
            final float typedShare = Math.min(typedNeed, avail * 0.8f);
            final float rest = Math.max(avail * 0.2f, avail - typedShare);
            for (int pos = 0; pos < count; pos++) {
                if (need[pos] <= 0) continue;
                result[pos] = mIsTypedWordAt[pos] ? (int) (typedShare * need[pos] / Math.max(1f, typedNeed))
                        : (int) (rest * need[pos] / Math.max(1f, others));
            }
        }
        return result;
    }

    private int getSuggestionWidth(final int positionInStrip, final int maxWidth) {
        final int paddings = mPadding * mSuggestionsCountInStrip;
        final int dividers = mDividerWidth * (mSuggestionsCountInStrip - 1);
        final int availableWidth = maxWidth - paddings - dividers;
        return (int)(availableWidth * getSuggestionWeight(positionInStrip));
    }

    private float getSuggestionWeight(final int positionInStrip) {
        if (positionInStrip == mCenterPositionInStrip) {
            return mCenterSuggestionWeight;
        }
        // TODO: Revisit this for cases of 5 or more suggestions
        return (1.0f - mCenterSuggestionWeight) / (mSuggestionsCountInStrip - 1);
    }

    private int setupWordViewsAndReturnStartIndexOfMoreSuggestions(
            final SuggestedWords suggestedWords, final int maxSuggestionInStrip) {
        // Clear all suggestions first
        for (int positionInStrip = 0; positionInStrip < maxSuggestionInStrip; ++positionInStrip) {
            final TextView wordView = mWordViews.get(positionInStrip);
            wordView.setText(null);
            wordView.setTag(null);
            // Make this inactive for touches in {@link #layoutWord(int,int)}.
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(null);
            }
        }
        int count = 0;
        int indexInSuggestedWords;
        for (indexInSuggestedWords = 0; indexInSuggestedWords < suggestedWords.size()
                && count < maxSuggestionInStrip; indexInSuggestedWords++) {
            final int positionInStrip =
                    getPositionInSuggestionStrip(indexInSuggestedWords, suggestedWords);
            if (positionInStrip < 0) {
                continue;
            }
            final TextView wordView = mWordViews.get(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(indexInSuggestedWords);
            wordView.setText(getStyledSuggestedWord(suggestedWords, indexInSuggestedWords));
            wordView.setTextColor(getSuggestionTextColor(suggestedWords, indexInSuggestedWords));
            KeyboardTypeface.applyToTextView(wordView);
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(suggestedWords.getDebugString(indexInSuggestedWords));
            }
            count++;
        }
        return indexInSuggestedWords;
    }

    private int layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
            final PunctuationSuggestions punctuationSuggestions, final ViewGroup stripView) {
        final int countInStrip = Math.min(punctuationSuggestions.size(), PUNCTUATIONS_IN_STRIP);
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, mDividerViews.get(positionInStrip));
            }

            final TextView wordView = mWordViews.get(positionInStrip);
            final String punctuation = punctuationSuggestions.getLabel(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(positionInStrip);
            wordView.setText(punctuation);
            wordView.setContentDescription(punctuation);
            wordView.setTextScaleX(1.0f);
            wordView.setCompoundDrawables(null, null, null, null);
            wordView.setTextColor(mColorAutoCorrect);
            KeyboardTypeface.applyToTextView(wordView);
            stripView.addView(wordView);
            setLayoutWeight(wordView, 1.0f, mSuggestionsStripHeight);
        }
        mMoreSuggestionsAvailable = (punctuationSuggestions.size() > countInStrip);
        return countInStrip;
    }

    static void setLayoutWeight(final View v, final float weight, final int height) {
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof final LinearLayout.LayoutParams llp) {
            llp.weight = weight;
            llp.width = 0;
            llp.height = height;
        }
    }

    private static float getTextScaleX(@Nullable final CharSequence text, final int maxWidth, final TextPaint paint) {
        paint.setTextScaleX(1.0f);
        final int width = getTextWidth(text, paint);
        if (width <= maxWidth || maxWidth <= 0) {
            return 1.0f;
        }
        return maxWidth / (float) width;
    }

    @Nullable
    private static CharSequence getEllipsizedTextWithSettingScaleX(
            @Nullable final CharSequence text, final int maxWidth, @NonNull final TextPaint paint) {
        if (text == null) {
            return null;
        }
        final float scaleX = getTextScaleX(text, maxWidth, paint);
        if (scaleX >= MIN_TEXT_XSCALE) {
            paint.setTextScaleX(scaleX);
            return text;
        }

        // <code>text</code> must be ellipsized with minimum text scale x.
        paint.setTextScaleX(MIN_TEXT_XSCALE);
        final boolean hasBoldStyle = hasStyleSpan(text, BOLD_SPAN);
        final boolean hasUnderlineStyle = hasStyleSpan(text, UNDERLINE_SPAN);
        // TextUtils.ellipsize erases any span object existed after ellipsized point.
        // We have to restore these spans afterward.
        final CharSequence ellipsizedText = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.MIDDLE);
        if (!hasBoldStyle && !hasUnderlineStyle) {
            return ellipsizedText;
        }
        final Spannable spannableText = (ellipsizedText instanceof Spannable)
                ? (Spannable)ellipsizedText : new SpannableString(ellipsizedText);
        if (hasBoldStyle) {
            addStyleSpan(spannableText, BOLD_SPAN);
        }
        if (hasUnderlineStyle) {
            addStyleSpan(spannableText, UNDERLINE_SPAN);
        }
        return spannableText;
    }

    private static boolean hasStyleSpan(@Nullable final CharSequence text,
            final CharacterStyle style) {
        if (text instanceof Spanned) {
            return ((Spanned)text).getSpanStart(style) >= 0;
        }
        return false;
    }

    private static void addStyleSpan(@NonNull final Spannable text, final CharacterStyle style) {
        text.removeSpan(style);
        text.setSpan(style, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private static int getTextWidth(@Nullable final CharSequence text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        final int length = text.length();
        final float[] widths = new float[length];
        final int count;
        final Typeface savedTypeface = paint.getTypeface();
        try {
            paint.setTypeface(getTextTypeface(text));
            count = paint.getTextWidths(text, 0, length, widths);
        } finally {
            paint.setTypeface(savedTypeface);
        }
        int width = 0;
        for (int i = 0; i < count; i++) {
            width += Math.round(widths[i] + 0.5f);
        }
        return width;
    }

    private static Typeface getTextTypeface(@Nullable final CharSequence text) {
        return hasStyleSpan(text, BOLD_SPAN) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }
}

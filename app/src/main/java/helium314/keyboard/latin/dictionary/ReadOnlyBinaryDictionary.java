/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.dictionary;

import com.android.inputmethod.latin.BinaryDictionary;

import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.makedict.WordProperty;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class provides binary dictionary reading operations with locking. An instance of this class
 * can be used by multiple threads. Note that different session IDs must be used when multiple
 * threads get suggestions using this class.
 */
public final class ReadOnlyBinaryDictionary extends Dictionary {
    /**
     * A lock for accessing binary dictionary. Only closing binary dictionary is the operation
     * that change the state of dictionary.
     */
    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();

    private final BinaryDictionary mBinaryDictionary;

    public ReadOnlyBinaryDictionary(final String filename, final long offset, final long length,
            final boolean useFullEditDistance, final Locale locale, final String dictType) {
        super(dictType, locale);
        mBinaryDictionary = new BinaryDictionary(filename, offset, length, useFullEditDistance,
                locale, dictType, false /* isUpdatable */);
    }

    public boolean isValidDictionary() {
        return mBinaryDictionary.isValidDictionary();
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final ComposedData composedData,
            final NgramContext ngramContext, final long proximityInfoHandle,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int sessionId, final float weightForLocale,
            final float[] inOutWeightOfLangModelVsSpatialModel) {
        if (composedData.mIsBatchMode && helium314.keyboard.latin.glide.OpenGlide.active())
            return helium314.keyboard.latin.glide.OpenGlide.suggest(this, composedData); // swipe-1: no closed library
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getSuggestions(composedData, ngramContext,
                        proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                        weightForLocale, inOutWeightOfLangModelVsSpatialModel);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return null;
    }

    /**
     * swipe-1: calls [consumer] for every word of the dictionary with its probability (0..255); for the open glide typing.
     * swipe-2: only the word and its probability (the full word properties made it take minutes on the phone); the read
     * lock is taken for 500 words at a time, so typing is never held up long.
     */
    public void forEachWord(final java.util.function.BiConsumer<String, Integer> consumer) {
        final int[] codePoints = new int[BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH];
        int token = 0;
        do {
            mLock.readLock().lock();
            try {
                if (!mBinaryDictionary.isValidDictionary()) return; // closed meanwhile (e.g. by "Проверка словарей")
                for (int n = 0; n < 500; n++) {
                    java.util.Arrays.fill(codePoints, 0);
                    token = mBinaryDictionary.getNextWord(token, codePoints);
                    int len = 0;
                    while (len < codePoints.length && codePoints[len] != 0) len++;
                    if (len > 0) {
                        final int probability = mBinaryDictionary.getProbability(java.util.Arrays.copyOf(codePoints, len));
                        if (probability >= 0) consumer.accept(new String(codePoints, 0, len), probability);
                    }
                    if (token == 0) break;
                }
            } finally {
                mLock.readLock().unlock();
            }
        } while (token != 0);
    }

    @Override
    public boolean isInDictionary(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.isInDictionary(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    @Override
    public boolean shouldAutoCommit(final SuggestedWordInfo candidate) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.shouldAutoCommit(candidate);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    @Override
    public int getFrequency(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getFrequency(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }

    @Override
    public int getMaxFrequencyOfExactMatches(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getMaxFrequencyOfExactMatches(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }

    @Override
    public WordProperty getWordProperty(String word, boolean isBeginningOfSentence) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getWordProperty(word, isBeginningOfSentence);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return null;
    }

    @Override
    public void close() {
        mLock.writeLock().lock();
        try {
            mBinaryDictionary.close();
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public String getHash() {
        return mBinaryDictionary.getHash();
    }
}

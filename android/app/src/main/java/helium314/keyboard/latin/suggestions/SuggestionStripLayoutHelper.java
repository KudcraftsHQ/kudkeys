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
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.PunctuationSuggestions;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayList;

final class SuggestionStripLayoutHelper {
    private static final int DEFAULT_SUGGESTIONS_COUNT_IN_STRIP = 3;
    private static final float DEFAULT_CENTER_SUGGESTION_PERCENTILE = 0.40f;
    private static final int DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2;
    private static final int PUNCTUATIONS_IN_STRIP = 5;
    // Kud Keys: a long suggestion shrinks its font down to this share of the normal size, then ellipsizes
    static final float MIN_TEXT_SIZE_SCALE = 0.60f;

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
    private final Drawable mMoreSuggestionsHint;
    private static final String MORE_SUGGESTIONS_HINT = "…";

    private static final CharacterStyle BOLD_SPAN = new StyleSpan(Typeface.BOLD);
    private static final CharacterStyle UNDERLINE_SPAN = new UnderlineSpan();

    private final int mSuggestionStripOptions;
    // Kud Keys: the word views' normal text size in px, before any shrinking
    private final float mBaseTextSize;
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

        final TextView wordView = wordViews.get(0);
        final View dividerView = dividerViews.get(0);
        mPadding = wordView.getCompoundPaddingLeft() + wordView.getCompoundPaddingRight();
        mBaseTextSize = wordView.getTextSize();
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
        // Kud Keys: the typed word on the left is never bold (VALID_TYPED_WORD_BOLD is ignored); emphasis belongs
        // to the best guess in the centre, see isBestGuess
        if (!isAutoCorrection) {
            return word;
        }

        final Spannable spannedWord = new SpannableString(word);
        final int options = mSuggestionStripOptions;
        if ((options & AUTO_CORRECT_BOLD) != 0) {
            addStyleSpan(spannedWord, BOLD_SPAN);
        }
        if (isAutoCorrection && (options & AUTO_CORRECT_UNDERLINE) != 0) {
            addStyleSpan(spannedWord, UNDERLINE_SPAN);
        }
        return spannedWord;
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

    /**
     * Kud Keys (SwiftKey): with nothing about to auto-correct, the centre slot is the best guess and is drawn in the
     * full text colour at medium weight, while the side slots stay muted. With autocorrect on, the auto-correction
     * keeps its own style.
     */
    private boolean isBestGuess(final SuggestedWords suggestedWords, final int positionInStrip,
            final int indexInSuggestedWords) {
        if (positionInStrip != mCenterPositionInStrip || suggestedWords.mWillAutoCorrect) return false;
        return !suggestedWords.getInfo(indexInSuggestedWords).isKindOf(SuggestedWordInfo.KIND_TYPED);
    }

    private int applyObsoleteAlpha(final SuggestedWords suggestedWords, final int color) {
        return suggestedWords.mIsObsoleteSuggestions ? applyAlpha(color, mAlphaObsoleted) : color;
    }

    /** Medium (500) weight of the slot's typeface, where the platform has weights (API 28); emoji are left alone. */
    static Typeface getBestGuessTypeface(@Nullable final Typeface typeface, @Nullable final CharSequence text) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return typeface;
        if (text != null && helium314.keyboard.latin.common.StringUtilsKt.isEmoji(text)) return typeface;
        return Typeface.create(typeface != null ? typeface : Typeface.DEFAULT, BEST_GUESS_WEIGHT, false);
    }

    static final int BEST_GUESS_WEIGHT = 500;

    private static int applyAlpha(final int color, final float alpha) {
        final int newAlpha = (int)(Color.alpha(color) * alpha);
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void addDivider(final ViewGroup stripView, final View dividerView) {
        // Kud Keys: no lines between suggestions (SwiftKey has none); the divider keeps its width so spacing stays.
        dividerView.setVisibility(View.INVISIBLE);
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

        // Kud Keys (SwiftKey): always the same three slots, never one wide word; a long word shrinks instead
        final int wordCountToShow = suggestedWords.getWordCountToShow();
        final int startIndexOfMoreSuggestions = setupWordViewsAndReturnStartIndexOfMoreSuggestions(
                suggestedWords, mSuggestionsCountInStrip);
        final int stripWidth = stripView.getWidth();

        final int countInStrip = mSuggestionsCountInStrip;
        mMoreSuggestionsAvailable = (wordCountToShow > countInStrip);
        @SuppressWarnings("unused")
        int x = 0;
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                final View divider = mDividerViews.get(positionInStrip);
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, divider);
                x += divider.getMeasuredWidth();
            }

            final int width = getSuggestionWidth(positionInStrip, stripWidth);
            final TextView wordView = layoutWord(context, positionInStrip, width);
            stripView.addView(wordView);
            setLayoutWeight(wordView, getSuggestionWeight(positionInStrip), ViewGroup.LayoutParams.MATCH_PARENT);
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
     * usually doesn't match the index in <code>suggedtedWords</code> -- see {@link #getSlotIndices}.
     *
     * @param positionInStrip the position in the suggestion strip.
     * @param width the maximum width for layout in pixels.
     * @return the {@link TextView} containing the suggested word appropriately formatted.
     */
    private TextView layoutWord(final Context context, final int positionInStrip, final int width) {
        final TextView wordView = mWordViews.get(positionInStrip);
        final CharSequence word = wordView.getText();
        // Kud Keys: no "…" hint under the middle word; long-press still opens more suggestions.
        wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        // {@link StyleSpan} in a content description may cause an issue of TTS/TalkBack.
        // Use a simple {@link String} to avoid the issue.
        wordView.setContentDescription(
                TextUtils.isEmpty(word)
                    ? context.getResources().getString(R.string.spoken_empty_suggestion)
                    : word.toString());
        // Kud Keys: shrink the font (not the letters' width) to fit, down to MIN_TEXT_SIZE_SCALE, then ellipsize
        final float textSizeScale = getTextSizeScale(word, width, wordView.getPaint(), mBaseTextSize);
        wordView.setTextScaleX(1.0f);
        wordView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mBaseTextSize * Math.max(textSizeScale, MIN_TEXT_SIZE_SCALE));
        final CharSequence text = textSizeScale >= MIN_TEXT_SIZE_SCALE || word == null
                ? word : getEllipsizedText(word, width, wordView.getPaint());
        wordView.setText(text);
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

    /**
     * Kud Keys (SwiftKey): which suggestion goes in each of the three slots, as indices into the suggestions
     * ({@code -1} = empty), in strip order (left, centre, right for LTR). While a word is being typed the left slot
     * is the typed word exactly as typed, the centre the best other suggestion and the right the next one. With
     * nothing typed (predictions) the centre is the best, left and right the 2nd and 3rd. The typed word never
     * shows twice, and neither does a word in another case ({@code ma} never shows {@code MA} beside it), except
     * the typed word's capitalised form ({@code andi} → {@code Andi}), which is a real suggestion.
     *
     * @param words the suggestions' words in rank order
     * @param typing whether index 0 is the word being typed
     */
    static int[] getSlotIndices(final java.util.List<String> words, final boolean typing) {
        final int[] slots = {-1, -1, -1};
        final String typed = typing && !words.isEmpty() ? words.get(0) : null;
        final int[] order = typing ? new int[] {1, 2} : new int[] {1, 0, 2};
        int next = 0;
        if (typing && typed != null) {
            slots[0] = 0;
        }
        final ArrayList<String> shown = new ArrayList<>(3);
        for (int i = typing ? 1 : 0; i < words.size() && next < order.length; i++) {
            final String word = words.get(i);
            if (typed != null && typed.equals(word)) continue;
            if (typed != null && typed.equalsIgnoreCase(word) && !isCapitalisedFormOf(word, typed)) continue;
            if (containsIgnoreCase(shown, word)) continue;
            shown.add(word);
            slots[order[next++]] = i;
        }
        return slots;
    }

    private static boolean containsIgnoreCase(final java.util.List<String> words, final String word) {
        for (final String w : words) if (w.equalsIgnoreCase(word)) return true;
        return false;
    }

    /** Whether {@code word} is {@code typed} (all lower case) with only its first letter capitalised. */
    private static boolean isCapitalisedFormOf(final String word, final String typed) {
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) return false;
        for (int i = 0; i < typed.length(); i++) if (Character.isUpperCase(typed.charAt(i))) return false;
        for (int i = 1; i < word.length(); i++) if (Character.isUpperCase(word.charAt(i))) return false;
        return true;
    }

    static boolean isTyping(final SuggestedWords suggestedWords) {
        return !suggestedWords.isPrediction() && suggestedWords.getTypedWordInfoOrNull() != null;
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
        final ArrayList<String> words = new ArrayList<>(suggestedWords.size());
        for (int i = 0; i < suggestedWords.size(); i++) {
            words.add(suggestedWords.getWord(i));
        }
        final int[] slots = getSlotIndices(words, isTyping(suggestedWords));
        int lastShown = -1;
        for (int positionInStrip = 0; positionInStrip < Math.min(maxSuggestionInStrip, slots.length); positionInStrip++) {
            final int indexInSuggestedWords = slots[positionInStrip];
            if (indexInSuggestedWords < 0) continue;
            lastShown = Math.max(lastShown, indexInSuggestedWords);
            final TextView wordView = mWordViews.get(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(indexInSuggestedWords);
            final CharSequence styled = getStyledSuggestedWord(suggestedWords, indexInSuggestedWords);
            wordView.setText(styled);
            final boolean bestGuess = isBestGuess(suggestedWords, positionInStrip, indexInSuggestedWords);
            wordView.setTextColor(bestGuess
                    ? applyObsoleteAlpha(suggestedWords, mColorAutoCorrect)
                    : getSuggestionTextColor(suggestedWords, indexInSuggestedWords));
            KeyboardTypeface.applyToTextView(wordView);
            if (bestGuess) wordView.setTypeface(getBestGuessTypeface(wordView.getTypeface(), styled));
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(suggestedWords.getDebugString(indexInSuggestedWords));
            }
        }
        return lastShown + 1;
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

    /** Kud Keys: the share of the normal text size at which {@code text} fits {@code maxWidth} (1 when it fits). */
    static float getTextSizeScale(@Nullable final CharSequence text, final int maxWidth, final TextPaint paint,
            final float baseTextSize) {
        paint.setTextScaleX(1.0f);
        paint.setTextSize(baseTextSize);
        final int width = getTextWidth(text, paint);
        if (width <= maxWidth || maxWidth <= 0) {
            return 1.0f;
        }
        return maxWidth / (float) width;
    }

    /** Kud Keys: {@code text} cut with "…" at the end to fit {@code maxWidth} at the paint's current size. */
    private static CharSequence getEllipsizedText(@NonNull final CharSequence text, final int maxWidth,
            @NonNull final TextPaint paint) {
        final boolean hasBoldStyle = hasStyleSpan(text, BOLD_SPAN);
        final boolean hasUnderlineStyle = hasStyleSpan(text, UNDERLINE_SPAN);
        final CharSequence ellipsized = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END);
        if (!hasBoldStyle && !hasUnderlineStyle) return ellipsized;
        final Spannable spannable = (ellipsized instanceof Spannable)
                ? (Spannable) ellipsized : new SpannableString(ellipsized);
        if (hasBoldStyle) addStyleSpan(spannable, BOLD_SPAN);
        if (hasUnderlineStyle) addStyleSpan(spannable, UNDERLINE_SPAN);
        return spannable;
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
            paint.setTypeface(getTextTypeface(text, savedTypeface));
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

    // Kud Keys: measure with the view's own typeface (custom font, the best guess's medium weight), bold if spanned
    private static Typeface getTextTypeface(@Nullable final CharSequence text, @Nullable final Typeface typeface) {
        final Typeface base = typeface != null ? typeface : Typeface.DEFAULT;
        return hasStyleSpan(text, BOLD_SPAN) ? Typeface.create(base, Typeface.BOLD) : base;
    }
}

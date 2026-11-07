/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.LayoutDirective;
import helium314.keyboard.keyboard.internal.ShiftMode;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.CapsMode;
import helium314.keyboard.latin.InputAttributes;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsKt;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

import java.util.ArrayList;

public final class KeyboardSwitcher {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView; // kudkeys: null until emoji are first shown, see getEmojiPalettesView
    private ViewStub mEmojiPalettesStub;
    private View mEmojiTabStripView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private TextView mFakeToastView;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    // kudkeys: input views that can be shown without inflating. One is built while the IME is idle after it starts
    // (prebuildInputView), so the first open doesn't inflate; and the view being replaced when only the look changes
    // (a private field's incognito theme and back) is kept for when that look comes back. A view only fits while
    // nothing else changed since it was built: anything else bumps mViewGeneration and drops them.
    private static final int MAX_READY_VIEWS = 2;
    private static final class ReadyView {
        final InputView view; final String key; final int generation;
        ReadyView(InputView view, String key, int generation) { this.view = view; this.key = key; this.generation = generation; }
    }
    private final ArrayList<ReadyView> mReadyViews = new ArrayList<>();
    private int mViewGeneration;
    private String mCurrentViewKey;
    private int mCurrentViewGeneration = -1;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(new SwitchActions());
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        // kudkeys: the reason is logged, and only a change of theme alone (a private field's look) keeps ready views
        final String envChange;
        if (mThemeNeedsReload) envChange = "reload requested";
        else if (mThemeContext == null) envChange = "first";
        else if (mCurrentDpi != res.getDisplayMetrics().densityDpi) envChange = "density";
        else if (mCurrentOrientation != res.getConfiguration().orientation) envChange = "orientation";
        else if ((mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)) envChange = "night mode";
        else if (!mThemeContext.getResources().equals(res)) envChange = "resources";
        else if (Settings.getValues().mColors.haveColorsChanged(context)) envChange = "colors";
        else envChange = null;
        if (envChange != null || !keyboardTheme.equals(mKeyboardTheme)) {
            Log.i(TAG, "theme reload: " + (envChange != null ? envChange : "theme " + (mKeyboardTheme == null ? -1 : mKeyboardTheme.getThemeId()) + " -> " + keyboardTheme.getThemeId()));
            if (envChange != null) invalidateReadyViews();
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            if (envChange != null) KeyboardLayoutSet.Companion.onKeyboardThemeChanged();
            else KeyboardLayoutSet.Companion.onKeyboardLookChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(EditorInfo editorInfo, SettingsValues settingsValues, int currentAutoCapsState,
                             @Nullable RecapitalizeMode currentRecapitalizeState, KeyboardLayoutSet.InternalAction internalAction) {
        KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(mThemeContext, editorInfo, settingsValues);
        int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled);
        } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.getKeyboardId(), e.getCause());
            try {
                InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.getKeyboardId(), e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes()) {
            mState.onSaveKeyboardState();
        }
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    @Nullable public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet() {
        mState.onResetKeyboardStateToAlphabet(mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState());
    }

    public void onPressKey(int code, int pointerCount, int currentAutoCapsState,
            @Nullable RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    public void setEmojiKeyboard() {
        mState.setLayout(LayoutDirective.Utility.EMOJI);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState) ? View.GONE : View.VISIBLE;
        final int stripVisibility = mLatinIME.hasSuggestionStripView()? View.VISIBLE : View.GONE;
        mStripContainer.setVisibility(stripVisibility);
        PointerTracker.switchTo(mKeyboardView);
        mKeyboardView.setVisibility(visibility);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame.setVisibility(visibility);
        mKeyboardViewWrapper.setVisibility(Settings.getInstance().readShowToolbarOnly() ? View.GONE : View.VISIBLE);
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.setVisibility(View.GONE);
            mEmojiPalettesView.stopEmojiPalettes();
        }
        mEmojiTabStripView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(stripVisibility);
    }

    public void toggleLayout(@NonNull LayoutDirective.Utility layout, int autoCapsFlags, @Nullable RecapitalizeMode recapitalizeMode) {
        mState.toggleLayout(layout, autoCapsFlags, recapitalizeMode);
    }

    public void onLongPressAlphaSymbolForNumpad() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol");
        }
        mState.onLongPressAlphaSymbolForNumpad();
    }

    public enum KeyboardSwitchState {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        OTHER(null);

        @Nullable final KeyboardElement mKeyboardElement;

        KeyboardSwitchState(@Nullable KeyboardElement keyboardElement) {
            mKeyboardElement = keyboardElement;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingKeyboardId(KeyboardElement.SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            mLatinIME.stopShowingInputView();
            mLatinIME.hideWindow();
            resetKeyboardStateToAlphabet();
        } else {
            mLatinIME.startShowingInputView(true);
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else {
                if (mEmojiPalettesView != null) {
                    mEmojiPalettesView.stopEmojiPalettes();
                    mEmojiPalettesView.setVisibility(View.GONE);
                }

                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                if (toggleState == KeyboardSwitchState.SYMBOLS_SHIFTED)
                    // possible other states OTHER and HIDDEN have keyboardElement null, which we just ignore
                    // might need to be adjusted when functionality is extended
                    mState.setLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED);
            }
        }
    }

    public void updateShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "updateShiftState: " + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags) + " recapitalizeMode=" + recapitalizeMode);
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        // kudkeys: one-handed / split / floating size changed: a parked view of the other look has the old geometry
        invalidateReadyViews();
        if (mEmojiPalettesView != null)
            mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = isShowingEmojiPalettes();
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().isSecondaryStripVisible()? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView.getVisibility() == View.VISIBLE) return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull KeyboardElement... keyboardElements) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        KeyboardElement activeKeyboardId = keyboard.mId.getElement();
        for (KeyboardElement keyboardElement : keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
        mReadyViews.clear(); // kudkeys: spare input views go first
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, boolean isHardwareAcceleratedDrawingEnabled) {
        Log.d(TAG, "onCreateInputView");
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        // kudkeys: not PointerTracker.clearOldViewData(): it drops what a ready view's MainKeyboardView registered when
        // it was built (switchTo then crashes); the map is weak, so views that are gone leave it by themselves
        SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        final String key = inputViewKey();
        // kudkeys: keep the view being replaced when only the look changed, for when that look comes back
        if (mCurrentInputView != null && mCurrentViewGeneration == mViewGeneration && !key.equals(mCurrentViewKey))
            addReadyView(mCurrentInputView, mCurrentViewKey);
        InputView view = takeReadyView(key);
        if (view == null) {
            Log.d(TAG, "create new input view");
            view = inflateInputView();
        } else {
            Log.d(TAG, "use ready input view");
        }
        mCurrentInputView = view;
        mCurrentViewKey = key;
        mCurrentViewGeneration = mViewGeneration;
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view); // null until first inflated
        mEmojiPalettesStub = mCurrentInputView.findViewById(R.id.emoji_palettes_stub);
        if (mEmojiPalettesView != null) setUpEmojiPalettesView(isHardwareAcceleratedDrawingEnabled);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    private InputView inflateInputView() {
        return (InputView) LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
    }

    /** kudkeys: what a ready view must match: the theme, and the normal or incognito colours baked in at inflation. */
    private String inputViewKey() {
        return mKeyboardTheme.getThemeId() + ":" + Settings.getValues().mKudIncognitoLook;
    }

    private void addReadyView(@NonNull InputView view, @NonNull String key) {
        for (int i = mReadyViews.size() - 1; i >= 0; i--) {
            final ReadyView r = mReadyViews.get(i);
            if (r.view == view || r.key.equals(key) || r.generation != mViewGeneration) mReadyViews.remove(i);
        }
        mReadyViews.add(new ReadyView(view, key, mViewGeneration));
        while (mReadyViews.size() > MAX_READY_VIEWS) mReadyViews.remove(0);
    }

    /** A ready view built for [key] in the current generation, taken out of the list; never one still on screen. */
    @Nullable private InputView takeReadyView(@NonNull String key) {
        for (int i = mReadyViews.size() - 1; i >= 0; i--) {
            final ReadyView r = mReadyViews.get(i);
            if (r.generation != mViewGeneration) {
                mReadyViews.remove(i);
            } else if (r.key.equals(key) && r.view.getParent() == null) {
                mReadyViews.remove(i);
                return r.view;
            }
        }
        return null;
    }

    private boolean hasReadyView(@NonNull String key) {
        for (ReadyView r : mReadyViews)
            if (r.generation == mViewGeneration && r.key.equals(key) && r.view.getParent() == null) return true;
        return false;
    }

    private void invalidateReadyViews() {
        mViewGeneration++;
        mReadyViews.clear();
    }

    /** kudkeys: any configuration change may have changed what a view baked in (font scale, locale…): build anew. */
    public void onConfigurationChanged() {
        invalidateReadyViews();
    }

    /**
     * kudkeys: inflates the input view while the IME is idle before its first show, so that show takes it instead of
     * inflating (about 40 ms on a fast phone). Runs the same theme check as a show: a theme, density, orientation,
     * night mode or colour change before the show still leads to a new view there. Returns whether it built one.
     */
    public boolean prebuildInputView(@NonNull Context displayContext, boolean incognitoLook) {
        if (mLatinIME == null || mCurrentInputView != null || mLatinIME.isInputViewShown()) return false;
        // The IME usually restarts while a launcher (a private field: incognito look) has the focus, but the first
        // open is mostly in a normal field: build the asked look, with settings loaded for it, then put them back.
        final Settings settings = Settings.getInstance();
        final SettingsValues current = settings.getCurrent();
        final boolean swap = current.mKudIncognitoLook != incognitoLook;
        if (swap) {
            final EditorInfo look = new EditorInfo();
            look.inputType = InputType.TYPE_CLASS_TEXT;
            if (incognitoLook) look.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            settings.loadSettings(displayContext, current.mLocale, new InputAttributes(look, false, displayContext.getPackageName()));
        }
        try {
            updateKeyboardTheme(displayContext);
            final String key = inputViewKey();
            if (hasReadyView(key)) return false;
            addReadyView(inflateInputView(), key);
            return true;
        } finally {
            if (swap) {
                settings.loadSettings(displayContext, current.mLocale, current.mInputAttributes);
                updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
            }
        }
    }

    /**
     * kudkeys: builds the alphabet keyboard for the current field in the background of the first show: parsing the
     * layout files and loading the classes involved is the slow part, and those caches are what the first show's
     * loadKeyboard then finds (the keyboard itself too, when the field is the same).
     */
    public void prewarmKeyboard(@Nullable EditorInfo editorInfo, @NonNull KeyboardElement element) {
        if (mThemeContext == null || mCurrentInputView != null) return;
        try {
            final SettingsValues settingsValues = Settings.getValues();
            new KeyboardLayoutSet.Builder(mThemeContext, editorInfo, settingsValues)
                    .setKeyboardGeometry(ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues),
                            ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues))
                    .setSubtype(mRichImm.getCurrentSubtype())
                    .build()
                    .getKeyboard(element);
        } catch (Throwable e) {
            Log.w(TAG, "keyboard prewarm failed", e);
        }
    }

    /** kudkeys: the emoji palette, inflated from its stub the first time it is needed. */
    @Nullable private EmojiPalettesView getEmojiPalettesView() {
        if (mEmojiPalettesView == null && mEmojiPalettesStub != null) {
            mEmojiPalettesView = (EmojiPalettesView) mEmojiPalettesStub.inflate();
            mEmojiPalettesStub = null;
            setUpEmojiPalettesView(mIsHardwareAcceleratedDrawingEnabled);
        }
        return mEmojiPalettesView;
    }

    private void setUpEmojiPalettesView(boolean isHardwareAcceleratedDrawingEnabled) {
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
    }

    public CapsMode getKeyboardCapsMode() {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return CapsMode.OFF;
        }
        return keyboard.mId.getElement().getCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        invalidateReadyViews(); // kudkeys
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }

    // private SwitchActions implementation so e.g. setEmojiKeyboard can only be called via KeyboardState (avoid inconsistencies!)
    private class SwitchActions implements KeyboardState.SwitchActions {
        @Override
        public void setAlphabetKeyboard(@NonNull ShiftMode shiftMode) {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setAlphabetKeyboard");
            }
            setKeyboard(shiftMode.element, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsShiftedKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsShiftedKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
        }

        @Override
        public void setEmojiKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setEmojiKeyboard");
            }
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mKeyboardView.setVisibility(View.GONE);
            mSuggestionStripView.setVisibility(View.GONE);
            mStripContainer.setVisibility(getSecondaryStripVisibility());
            mEmojiTabStripView.setVisibility(View.VISIBLE);
            final EmojiPalettesView emojiPalettesView = getEmojiPalettesView();
            emojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            emojiPalettesView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setNumpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setNumpadKeyboard");
            }
            setKeyboard(KeyboardElement.NUMPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setDpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setDpadKeyboard");
            }
            setKeyboard(KeyboardElement.DPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void startDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "startDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.startDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void cancelDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "cancelDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.cancelDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void setOneHandedModeEnabled(boolean enabled) {
            KeyboardSwitcher.this.setOneHandedModeEnabled(enabled, false);
        }

        @Override
        public void switchOneHandedMode() {
            mKeyboardViewWrapper.switchOneHandedModeSide();
            Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
            invalidateReadyViews(); // kudkeys: a parked view of the other look would come back on the old side
        }

        @Override
        public void setFloatingKeyboardEnabled(boolean enabled) {
            if (enabled != Settings.getValues().mIsFloatingKeyboard)
                // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
                SettingsKt.setFloatingKeyboardEnabled(mThemeContext, enabled);
            if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView);
            else FloatingKeyboardUtils.disableFloating(mCurrentInputView);
        }

        @Override
        public boolean popDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            return keyboardView != null && keyboardView.popDoubleTapShiftKeyTimer();
        }

        // not a SwitchAction, but should only be called from a SwitchAction to avoid inconsistent state / actual layout
        private void setKeyboard(KeyboardElement keyboardElement, @NonNull KeyboardSwitchState toggleState) {
            // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
            if (mKeyboardView == null) return;

            // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
            SettingsValues currentSettingsValues = Settings.getValues();
            setMainKeyboardFrame(currentSettingsValues, toggleState);
            // TODO: pass this object to setKeyboard instead of getting the current values.
            MainKeyboardView keyboardView = mKeyboardView;
            Keyboard oldKeyboard = keyboardView.getKeyboard();
            Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardElement);
            keyboardView.setKeyboard(newKeyboard);
            mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
            keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
            keyboardView.updateShortcutKey(true); // kudkeys: quick voice is always available
            boolean subtypeChanged = (oldKeyboard == null) || !newKeyboard.mId.getSubtype().equals(oldKeyboard.mId.getSubtype());
            int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.getSubtype());
            boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
            keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

            if (currentSettingsValues.needsToLookupSuggestions()
                && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
                EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
            }
        }
    }
}

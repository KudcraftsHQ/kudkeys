# Kud Keys Android — notes for the next agents

Paths below are relative to `android/app/src/main/` unless they start with `android/`. Source packages are
still `helium314.keyboard.*` (see `UPSTREAM.md` for why); `J/` = `java/helium314/keyboard/`.

## Versions

| | |
|---|---|
| Upstream | HeliBoard v4.2 (`UPSTREAM.md`) |
| AGP | 9.1.1 (`android/build.gradle.kts`) |
| Gradle | 9.7.1 (wrapper) |
| Kotlin | 2.4.0 (+ compose and serialization plugins) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 21 |
| NDK | 28.0.13004108 (native dictionary code in `jni/`, built with ndk-build) |
| JDK | **21** to run unit tests (Robolectric with `sdk=36` refuses Java 17); 17 compiles but tests fail |
| applicationId | `com.kudcrafts.kudkeys` (debug: `com.kudcrafts.kudkeys.debug`); namespace `helium314.keyboard.latin` |

Build types: `debug` (no minify, `.debug` suffix) and `release` (R8 minify; signed only when `KC_KEYSTORE_FILE`
is set; `voice/consumer-rules.pro` keeps the voice module). CI builds the unsigned release too. Version comes from
`KC_VERSION_NAME` / `KC_VERSION_CODE` (default `0.0.0-dev` / 1); `release.yaml` sets the code from the tag as
`(major*10000 + minor*100 + patch)*100 + (rc ? N : 99)` for `vX.Y.Z-rc.N` / `vX.Y.Z`.
Android backup is on, but `res/xml/kud_backup_rules.xml` + `kud_data_extraction_rules.xml` exclude
`kudkeys_voice.xml` (token) in both storage domains.

## Build and test

```bash
source ~/.config/android-env.sh
export JAVA_HOME=$HOME/.local/opt/jdk21 PATH=$HOME/.local/opt/jdk21/bin:$PATH   # tests need JDK 21
cd android
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g testDebugUnitTest assembleDebug
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g testDebugUnitTest --tests 'helium314.keyboard.KudKeysDefaultsTest'
./gradlew --stop
```

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`; test report:
  `android/app/build/reports/tests/testDebugUnitTest/index.html`.
- A full clean build takes a few minutes (native code for 4 ABIs). Another agent's `./gradlew --stop` kills
  your daemon mid-build ("stop command received"); just rerun.
- Unit tests are Robolectric (`app/src/test/`, `@Config(shadows = [ShadowInputMethodManager2::class])`).
  `Robolectric.setupService(LatinIME::class.java)` gives a working IME with settings loaded.
  `SubtypeSettings` is a singleton shared across test classes: reset what you rely on (see
  `KudKeysDefaultsTest`). `XLinkTest` is `@Ignore`d (it checks live URLs).
- Our own test: `app/src/test/java/helium314/keyboard/KudKeysDefaultsTest.kt` pins the en+id default, the
  bundled dictionaries, the dark/borderless defaults and "no gesture, no clipboard keys".
- CI: `.github/workflows/android.yaml` (unit tests + debug APK on PRs and pushes touching `android/**`),
  `.github/workflows/release.yaml` (tag `v*` → signed `kudkeys-<tag>.apk` + `SHA256SUMS`).

## Keyboard layouts (the SwiftKey layout work)

- Letter rows: `assets/layouts/main/qwerty.txt` (one key per line, blank line = new row; popups after the
  key). Format: `android/layouts.md` and the HeliBoard wiki "Layouts" page.
- Functional keys and the bottom row (shift, backspace, `123`, emoji, `,`, space, `.`, enter):
  `assets/layouts/functional/functional_keys.json` (widths as fractions of the keyboard width, e.g.
  `"width": 0.15`). Tablet variant: `functional_keys_tablet.json`.
- Symbols / number row / numpad: `assets/layouts/symbols/`, `number_row/`, `numpad/`, …
- Parsing: `J/keyboard/internal/keyboard_parser/` (`KeyboardParser.kt`, `floris/TextKeyData.kt`,
  `LayoutParser.kt`); key geometry in `J/keyboard/internal/KeyboardParams.java` and `KeyboardBuilder.kt`.
- Key drawing (labels, hints, borders, spacebar): `J/keyboard/KeyboardView.java` and
  `J/keyboard/MainKeyboardView.java` (spacebar language label: `drawLanguageOnSpacebar`, text from
  `J/latin/utils/LanguageOnSpacebarUtils.java`).
- Sizes: `res/values/config.xml` (`config_default_keyboard_height` 205.6dp, key gaps), `res/values/dimens.xml`;
  user-scalable height/padding defaults in `Defaults.kt` (`PREF_KEYBOARD_HEIGHT_SCALE`, `PREF_BOTTOM_PADDING_SCALE`).
- Number-hint visibility on letter keys: `PREF_SHOW_NUMBER_ROW_HINTS` / popup hint settings in `Defaults.kt`.
- Layout unit tests: `app/src/test/java/helium314/keyboard/LayoutTest.kt`, `KeyboardParserTest.kt`.

## SwiftKey look (v0.1)

- Measured from SwiftKey's dark theme on a 411 dp phone: rows ~51.6 dp (`config_default_keyboard_height` 213dp,
  min `-50%p`), letters at 40% of key height (`config_key_letter_ratio_lxx`), keys 0.1 wide, shift/delete 0.15,
  bottom row `123` · emoji · `,` · space · `.` · enter (`functional_keys.json`; enter is `"type": "function"` so it
  has no accent circle).
- Colours: `THEME_DARK` in `KeyboardTheme.kt` is SwiftKey's (#171717 background, #d6d6d6 glyphs, #787878 hints).
- Space bar `‹ en/id ›` and the mic / `,!?` hints above comma and period are drawn in `MainKeyboardView`
  (`swiftKeyLanguageLabel`, `drawSpacebarChevrons`, `drawSwiftKeyPunctuationHint`). Letter hints are off
  (`PREF_SHOW_HINTS = false`); key-press vibration on.
- Icons: `res/drawable/sym_keyboard_{shift,shifted,shift_lock,delete,return}_lxx.xml`.
- `KudKeysLayoutScreenshotTest` renders letters / shifted / symbols to `$TMPDIR/kudkeys-layout/*.png`; compare
  them with the SwiftKey screenshot by eye after any layout change.

## Top bar, privacy and quick voice (Kud Keys code in `J/kudkeys/`)

- **The bar** is `J/latin/suggestions/SuggestionStripView.kt` + `res/layout/suggestions_strip.xml`: one row,
  ✦ Fix (left) · middle · Instruct · Mic (right). The icons are fixed 44 dp slots and never move. The middle is a
  FrameLayout: HeliBoard's suggestions, and on top of them the amber Private pill (centred while the strip is empty;
  while suggestions show, a compact `kud_private_tag` sits before them and the strip shrinks), the quick-voice row (red dot, time, live text, "‹ Slide to cancel") and short
  messages. Fix/Instruct show a "Coming soon" toast. Strings/dimens: `res/values/kudkeys_bar.xml`.
- The stock toolbar is gone: `Settings.readToolbarMode` always returns `SUGGESTION_STRIP`, the Toolbar settings
  screen and key customiser are deleted, no toolbar/pinned keys are created. `ToolbarUtils.kt` remains only for
  the clipboard chip's close button and icon names.
- **Privacy**: `J/kudkeys/KudPrivacy.kt` `isPrivate(editorInfo, packageName)` (incognito flag, passwords, URI,
  search action / FILTER, Nova + the default HOME app). `InputAttributes.mIsPrivate` feeds
  `SettingsValues.mIncognitoModeEnabled`, which already gates every learning path. `LatinIME` re-checks privacy on
  every `onStartInput` / `onStartInputView` (also `restarting=true`) and reloads settings when it differs. It (user history, auto-add to the
  personal dictionary, emoji history, emoji recents); `InputLogic.unlearnWord` is gated too.
- **Quick voice**: `J/kudkeys/QuickVoiceController.kt` is the touch/state machine (pure Kotlin, tested with fake
  touches and a fake engine); `KudQuickVoice.kt` hosts it in `LatinIME` (one per IME). Every start-input
  reports the field (`VoiceField`: fieldId, fieldName, package, inputType, imeOptions, privateness); another field
  cancels the session, even during the post-release wait, and text only goes into the field it started in.
  Hiding the keyboard or screen-off drops a recording but lets a released one finish; `onFinishInput` drops all.
  Errors insert whatever arrived. Comma long-press (`KeyCode.VOICE_INPUT`) starts quick voice locked; nothing
  switches to the system voice IME any more. Mic denied for good → "Microphone blocked — tap to open settings"
  (flag in `kudkeys_voice` prefs). Text goes in through `InputLogic.onVoiceTextInput` (smart leading space,
  composing word kept as typed). Mic permission: `MicPermissionActivity`. Voice client: the `:voice` module.
- **Settings → Voice**: `J/settings/screens/VoiceScreen.kt` (server URL, token, Test connection via
  `J/kudkeys/VoiceConnectionTest.kt`, which streams silence and sends `stop` once the server is ready).
- Tests: `app/src/test/java/helium314/keyboard/kudkeys/` (privacy rule, learning skipped, voice state machine,
  `KudKeysToolbarScreenshotTest` → `$TMPDIR/kudkeys-toolbar/*.png`).
- Input-field info (`EditorInfo` flags): `J/latin/InputAttributes.java`, `LatinIME.onStartInputView*`.

## Settings defaults

- Default values: `J/latin/settings/Defaults.kt`. Pref keys: `J/latin/settings/Settings.java`. Values read at
  runtime: `J/latin/settings/SettingsValues.java` (`Settings.getValues()`).
- Kud Keys changes there: `PREF_THEME_COLORS = THEME_DARK`, `PREF_THEME_DAY_NIGHT = false`,
  `KUDKEYS_DEFAULT_SUBTYPE` (en-US with `SecondaryLocales=id`) used for `PREF_ENABLED_SUBTYPES`,
  `PREF_SELECTED_SUBTYPE` and added to `PREF_ADDITIONAL_SUBTYPES`. Key borders and the number row were
  already off upstream.
- Subtypes / multilingual typing: `J/latin/utils/SubtypeSettings.kt`, `J/latin/settings/SettingsSubtype.kt`,
  `res/xml/method.xml` (Indonesian is `imeSubtypeLocale="in"`, `languageTag="id"`).
- Settings UI is Compose: `J/settings/` (`SettingsNavHost.kt`, `screens/*Screen.kt`, `SettingsContainer.kt`).
  New settings screens are added to `SettingsNavHost.kt` + `KudMainSettingsScreen` (or `KudAdvancedScreen`).
- About screen with the GPL/HeliBoard credit: `J/settings/screens/AboutScreen.kt`; links in
  `J/latin/common/Constants.kt` (`Links`).

## Theme colours

- Built-in themes: `J/keyboard/KeyboardTheme.kt` (`getThemeColors`, `THEME_DARK` →
  `DefaultColors(…)` with background `#263238` etc.). Styles: `STYLE_MATERIAL` (default), holo, rounded.
- Colour roles and how each view gets coloured: `J/latin/common/Colors.kt` (`ColorType`, `DefaultColors`,
  `setColor`, `setBackground`).
- XML colours and per-style themes: `res/values/colors.xml`, `res/values/themes-*.xml`
  (`themes-lxx-base.xml` = material without borders).

## Removed in v0.1 foundation

- Gesture typing: settings screen, library loading (`J/latin/utils/JniUtils.java` loads only
  `jni_latinime`), gesture-data gathering. `SettingsValues.mGestureInputEnabled` is always false; the batch
  input plumbing in `PointerTracker` / `InputLogic` is upstream code that is now unreachable.
- Clipboard history: view, bottom row, DB (`latin/database/` is gone), provider, toolbar keys.
  `KeyCode.CLIPBOARD` now pastes.
- Unused translated strings for those features are still in `res/values*/strings.xml` (harmless).

## Settings organised like SwiftKey (v0.2)

- Home = `KudMainSettingsScreen` in `J/settings/screens/KudSettingsScreens.kt`: Languages · Typing · Emoji ·
  Rich input · Layout & keys · Sound & vibration · Advanced (= `KudAdvancedScreen`: HeliBoard's Preferences,
  Appearance, Text correction, Secondary layouts, Dictionaries, Advanced, About). HeliBoard's `MainSettingsScreen`
  is gone. Room for **Account** at the top: pref key `KudSettings.PREF_ACCOUNT` (`kudkeys_account`), not shown yet.
- A SwiftKey row that is exactly one HeliBoard pref reuses that `Setting` (its title/summary were renamed to
  SwiftKey's wording in the HeliBoard `create*Settings`, so search and Advanced show the same names). Rows that
  combine or translate prefs have `KudSettingKeys.*` keys in `createKudSettings`; their pref logic is
  `KudSettingsRules` (cursor control = horizontal space swipe MOVE_CURSOR vs SWITCH_LANGUAGE, accented =
  more popup keys "all", large key text = font scale 1.2, Android default vibration = duration -1, …).
- New prefs (`J/kudkeys/KudSettings.kt`, read into `SettingsValues.mKud*`): quick prediction insert
  (`InputLogic.kudQuickPredictionToInsert`, called from `LatinIME.onEvent` on space; a second space within the
  double-space timeout is still a period), emoji access on enter key (`TextKeyData.createActionPopupKeys` keeps the
  enter key's emoji popup only when the emoji key is hidden and this is on), voice typing (off: Mic invisible in
  the bar, no mic hint above the comma, voice dropped from popups in `PopupKeysUtils`, comma long-press ignored),
  keypress sound profile (classic = one click, `AudioAndHapticFeedbackManager`). Clear typing data:
  `KudTypingData.clear` (user history files + the system personal dictionary).
- The emoji key in `functional_keys.json` is behind `emojiKeyEnabled` (`PREF_SHOW_EMOJI_KEY`, default now true).
- Defaults changed to SwiftKey's: backspace reverts autocorrect off, autospace after punctuation on, emoji
  suggestions off, D-pad (Arrow keys) off, key pop-ups on, long-press 450 ms. `InputLogicTest.reset` puts back
  HeliBoard's two typing defaults, because those tests pin HeliBoard's logic.
- Resize (`KudResizeScreen.kt`): `KudResize` is the handle math (pure, tested) on the height / side padding /
  bottom padding scales; `KudResizeStore` reads/writes them for the current orientation; the keyboard picture is
  `KudKeyboardPreview` (a `KeyboardView` drawn into a bitmap, keys area stretched into the live frame).

## Incognito look (v0.2)

- Private fields (`InputAttributes.mIsPrivate`, the same rule as the Private pill) set `SettingsValues.mKudIncognitoLook`:
  `KeyboardTheme.getKudIncognitoColors` (background #1c1f26, letter keys and space #2a2e37, functional keys
  borderless via `DefaultColors(kudBorderlessFunctionalKeys = true)`), `KeyboardTheme.getKeyboardTheme` returns
  the LXX border theme (rounded key shapes), and `MainKeyboardView.onDraw` draws `res/drawable/kud_incognito_watermark.xml`
  faintly over the keys. The normal look is unchanged (borderless).
- `KudV02ScreenshotTest` writes every settings screen and both keyboard looks to `$TMPDIR/kudkeys-v02-shots/`.

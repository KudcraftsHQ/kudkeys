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
is set). Version comes from `KC_VERSION_NAME` / `KC_VERSION_CODE` (default `0.0.0-dev` / 1).

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

## Toolbar and suggestion strip (the toolbar/voice-UI work)

- The whole top bar is `J/latin/suggestions/SuggestionStripView.kt`: suggestions, the toolbar (expand key,
  toolbar keys, pinned keys) and the incognito icon. Layout XML: `res/layout/suggestions_strip.xml`, inside
  `res/layout/strip_container.xml` (which also holds the emoji tab strip).
- Suggestion layout and widths: `J/latin/suggestions/SuggestionStripLayoutHelper.java`.
- Toolbar keys: enum `ToolbarKey`, `createToolbarKey()`, default key lists and the key → `KeyCode` mapping in
  `J/latin/utils/ToolbarUtils.kt`. `hiddenToolbarKeys` (CLIPBOARD, CLEAR_CLIPBOARD, CLOSE_HISTORY) are never
  offered; they remain only for icon/layout compatibility. Icons per style in
  `J/keyboard/internal/KeyboardIconsSet.kt`.
- Toolbar mode setting: `ToolbarMode` (EXPANDABLE default, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN);
  settings screen `J/settings/screens/ToolbarScreen.kt`.
- Key presses from the toolbar go through `J/keyboard/KeyboardActionListenerImpl.kt` →
  `J/latin/inputlogic/InputLogic.java` (`handleFunctionalEvent`). `KeyCode.VOICE_INPUT` is where the stock
  voice key lands today.
- The clipboard paste chip in the strip: `J/latin/ClipboardHistoryManager.kt` (`getClipboardSuggestionView`),
  shown from `LatinIME.java` (`getClipboardSuggestionView` call). History itself is gone.
- Input-field info for the privacy rules (`EditorInfo` flags): `J/latin/InputAttributes.java`
  (`mIsPasswordField`, `mNoLearning`, …) and `LatinIME.onStartInputView*`; incognito handling in
  `SettingsValues.java` (`mIncognitoModeEnabled`, `PREF_ALWAYS_INCOGNITO_MODE`).

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
  New settings screens (e.g. Settings → Voice) are added to `SettingsNavHost.kt` + `MainSettingsScreen.kt`.
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

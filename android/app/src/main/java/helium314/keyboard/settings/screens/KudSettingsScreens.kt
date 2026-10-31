// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.kudkeys.KudSettings
import helium314.keyboard.kudkeys.KudTypingData
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.mainLayoutNameOrQwerty
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlin.math.roundToInt

/*
 * Kud Keys: settings organised like SwiftKey's. Each row is HeliBoard's existing setting under SwiftKey's name
 * where one exists (see the renamed titles in the HeliBoard screens), plus the few Kud Keys ones in [KudSettings].
 * HeliBoard's own screens stay reachable under Advanced.
 */

/** Setting keys for rows that are not a single HeliBoard pref (they combine or translate prefs). */
object KudSettingKeys {
    const val CURSOR_CONTROL = "kudkeys_row_cursor_control"
    const val PHYSICAL_KEYBOARD = "kudkeys_row_physical_keyboard"
    const val CLEAR_TYPING_DATA = "kudkeys_row_clear_typing_data"
    const val VOICE = "kudkeys_row_voice"
    const val RESIZE = "kudkeys_row_resize"
    const val NUMBER_POSITION = "kudkeys_row_number_position"
    const val ACCENTED = "kudkeys_row_accented"
    const val LARGE_KEY_TEXT = "kudkeys_row_large_key_text"
    const val LONG_PRESS_DURATION = "kudkeys_row_long_press_duration"
    const val SOUND_VOLUME = "kudkeys_row_sound_volume"
    const val ANDROID_VIBRATION = "kudkeys_row_android_vibration"
    const val KEYPRESS_VIBRATION = "kudkeys_row_keypress_vibration"
}

// ---- the screens ----

@Composable
fun KudMainSettingsScreen(
    onClickLanguages: () -> Unit,
    onClickTyping: () -> Unit,
    onClickEmoji: () -> Unit,
    onClickRichInput: () -> Unit,
    onClickLayoutKeys: () -> Unit,
    onClickSound: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickBack: () -> Unit,
    onClickAccount: () -> Unit = {},
) {
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.ime_settings), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KudAccountRow(onClickAccount) // KudSettings.PREF_ACCOUNT
            Preference(
                name = stringResource(R.string.kud_section_languages),
                description = SubtypeSettings.getEnabledSubtypes(true).map { kudLanguageName(it, LocalContext.current) }.distinct().joinToString(", "),
                onClick = onClickLanguages,
                icon = R.drawable.ic_settings_languages,
            ) { NextScreenIcon() }
            SectionRow(R.string.kud_section_typing, R.string.kud_section_typing_summary, R.drawable.ic_settings_correction, onClickTyping)
            SectionRow(R.string.kud_section_emoji, R.string.kud_section_emoji_summary, R.drawable.sym_keyboard_smiley_lxx, onClickEmoji)
            SectionRow(R.string.kud_section_rich_input, R.string.kud_section_rich_input_summary, R.drawable.sym_keyboard_voice_lxx, onClickRichInput)
            SectionRow(R.string.kud_section_layout_keys, R.string.kud_section_layout_keys_summary, R.drawable.ic_settings_layout, onClickLayoutKeys)
            SectionRow(R.string.kud_section_sound, R.string.kud_section_sound_summary, R.drawable.kud_ic_sound, onClickSound)
            SectionRow(R.string.kud_section_advanced, R.string.kud_section_advanced_summary, R.drawable.ic_settings_advanced, onClickAdvanced)
        }
    }
}

@Composable
private fun SectionRow(name: Int, description: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
    Preference(name = stringResource(name), description = stringResource(description), onClick = onClick, icon = icon) {
        NextScreenIcon()
    }
}

/** HeliBoard's own screens, under Kud Keys' Advanced. */
@Composable
fun KudAdvancedScreen(
    onClickPreferences: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAbout: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_section_advanced), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Preference(stringResource(R.string.settings_screen_preferences), onClickPreferences, icon = R.drawable.ic_settings_preferences) { NextScreenIcon() }
            Preference(stringResource(R.string.settings_screen_appearance), onClickAppearance, icon = R.drawable.ic_settings_appearance) { NextScreenIcon() }
            Preference(stringResource(R.string.settings_screen_correction), onClickTextCorrection, icon = R.drawable.ic_settings_correction) { NextScreenIcon() }
            Preference(stringResource(R.string.settings_screen_secondary_layouts), onClickLayouts, icon = R.drawable.ic_settings_layout) { NextScreenIcon() }
            Preference(stringResource(R.string.dictionary_settings_category), onClickDictionaries, icon = R.drawable.ic_dictionary) { NextScreenIcon() }
            Preference(stringResource(R.string.settings_screen_advanced), onClickAdvanced, icon = R.drawable.ic_settings_advanced) { NextScreenIcon() }
            Preference(stringResource(R.string.settings_screen_about), onClickAbout, icon = R.drawable.ic_settings_about) { NextScreenIcon() }
        }
    }
}

/** SwiftKey's Languages: enabled languages with their layout and a gear, Add languages, two explainers. */
@Composable
fun KudLanguagesScreen(onClickAddLanguages: () -> Unit, onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    rememberPrefChanges()
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_section_languages), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SubtypeSettings.getEnabledSubtypes(true).distinct().forEach { subtype ->
                val layout = SubtypeLocaleUtils.getMainLayoutDisplayName(subtype.mainLayoutNameOrQwerty())
                    ?: subtype.mainLayoutNameOrQwerty()
                val name = kudLanguageName(subtype, ctx)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = null))
                        Text(
                            stringResource(R.string.kud_languages_layout, layout),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    IconButton(onClick = {
                        SettingsDestination.navigateTo(SettingsDestination.Subtype + subtype.toSettingsSubtype().toPref())
                    }) {
                        Icon(painterResource(R.drawable.kud_ic_gear), stringResource(R.string.kud_languages_settings))
                    }
                }
            }
            Button(onClick = onClickAddLanguages, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp).height(48.dp)) {
                Text("+  " + stringResource(R.string.kud_languages_add), style = MaterialTheme.typography.titleMedium.copy(fontWeight = null))
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            Explainer(R.drawable.kud_ic_keyboard, R.string.kud_languages_changing_title, R.string.kud_languages_changing_text)
            Explainer(R.drawable.kud_ic_info, R.string.kud_languages_multilingual_title, R.string.kud_languages_multilingual_text)
        }
    }
}

/** "English (US) + Indonesian": the subtype's name with its multilingual (secondary) languages. */
private fun kudLanguageName(subtype: android.view.inputmethod.InputMethodSubtype, ctx: Context): String {
    val secondary = subtype.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)
        ?.split(Separators.KV)?.filter { it.isNotBlank() }
        ?.joinToString(" + ") { it.constructLocale().localizedDisplayName(ctx.resources) }
    return subtype.displayName() + (secondary?.takeIf { it.isNotEmpty() }?.let { " + $it" } ?: "")
}

@Composable
private fun Explainer(@DrawableRes icon: Int, title: Int, text: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = null))
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun KudTypingScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_section_typing),
    settings = listOf(
        Settings.PREF_AUTO_CORRECTION,
        Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT,
        KudSettings.PREF_QUICK_PREDICTION_INSERT,
        Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD,
        Settings.PREF_AUTO_CAP,
        Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
        KudSettingKeys.CURSOR_CONTROL,
        Settings.PREF_DELETE_SWIPE,
        KudSettingKeys.PHYSICAL_KEYBOARD,
        KudSettingKeys.CLEAR_TYPING_DATA,
    )
)

@Composable
fun KudPhysicalKeyboardScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_physical_keyboard),
    settings = listOf(Settings.PREF_ENABLE_HARDWARE_KEYBOARD, Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
)

@Composable
fun KudEmojiScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_section_emoji),
    settings = listOf(
        Settings.PREF_SUGGEST_EMOJIS,
        Settings.PREF_SHOW_EMOJI_KEY,
        KudSettings.PREF_EMOJI_ON_ENTER,
        SettingsWithoutKey.CUSTOM_EMOJI_FONT,
    )
)

@Composable
fun KudRichInputScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_section_rich_input),
    settings = listOf(KudSettings.PREF_VOICE_TYPING, KudSettingKeys.VOICE)
)

@Composable
fun KudLayoutKeysScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_section_layout_keys),
    settings = listOf(
        KudSettingKeys.RESIZE,
        Settings.PREF_SHOW_NUMBER_ROW,
        KudSettingKeys.NUMBER_POSITION,
        Settings.PREF_SHOW_HINTS,
        KudSettingKeys.ACCENTED,
        Settings.PREF_SHOW_DPAD_KEY,
        Settings.PREF_POPUP_ON,
        KudSettingKeys.LARGE_KEY_TEXT,
        KudSettingKeys.LONG_PRESS_DURATION,
    )
)

@Composable
fun KudSoundScreen(onClickBack: () -> Unit) = SearchSettingsScreen(
    onClickBack = onClickBack,
    title = stringResource(R.string.kud_section_sound),
    settings = listOf(
        KudSettingKeys.SOUND_VOLUME,
        KudSettings.PREF_SOUND_PROFILE,
        KudSettingKeys.ANDROID_VIBRATION,
        KudSettingKeys.KEYPRESS_VIBRATION,
    )
)

// ---- the rows that are new or combine prefs ----

fun createKudSettings(context: Context) = listOf(
    Setting(context, KudSettings.PREF_QUICK_PREDICTION_INSERT, R.string.kud_quick_prediction, R.string.kud_quick_prediction_summary) {
        SwitchPreference(it, KudSettings.DEFAULT_QUICK_PREDICTION_INSERT)
    },
    Setting(context, KudSettingKeys.CURSOR_CONTROL, R.string.kud_cursor_control, R.string.kud_cursor_control_summary) { setting ->
        val prefs = rememberPrefChanges()
        KudSwitchRow(setting.title, setting.description, KudSettingsRules.isCursorControl(prefs)) {
            KudSettingsRules.setCursorControl(prefs, it)
        }
    },
    Setting(context, KudSettingKeys.PHYSICAL_KEYBOARD, R.string.kud_physical_keyboard, R.string.kud_physical_keyboard_summary) {
        Preference(it.title, { SettingsDestination.navigateTo(KudDestination.PhysicalKeyboard) }, description = it.description) {
            NextScreenIcon()
        }
    },
    Setting(context, KudSettingKeys.CLEAR_TYPING_DATA, R.string.kud_clear_typing_data, R.string.kud_clear_typing_data_summary) { setting ->
        val ctx = LocalContext.current
        var confirm by rememberSaveable { mutableStateOf(false) }
        Preference(setting.title, { confirm = true }, description = setting.description)
        if (confirm) ConfirmationDialog(
            onDismissRequest = { confirm = false },
            onConfirmed = {
                KudTypingData.clear(ctx)
                Toast.makeText(ctx, R.string.kud_clear_typing_data_done, Toast.LENGTH_SHORT).show()
            },
            title = { Text(setting.title) },
            content = { Text(stringResource(R.string.kud_clear_typing_data_confirm)) },
            confirmButtonText = stringResource(R.string.kud_clear_typing_data_button),
        )
    },
    Setting(context, KudSettings.PREF_EMOJI_ON_ENTER, R.string.kud_emoji_on_enter, R.string.kud_emoji_on_enter_summary) { setting ->
        val prefs = rememberPrefChanges()
        val emojiKey = prefs.getBoolean(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY)
        KudSwitchRow(
            setting.title, setting.description,
            prefs.getBoolean(setting.key, KudSettings.DEFAULT_EMOJI_ON_ENTER),
            enabled = !emojiKey,
        ) {
            prefs.edit { putBoolean(setting.key, it) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, KudSettings.PREF_VOICE_TYPING, R.string.kud_voice_typing, R.string.kud_voice_typing_summary) {
        SwitchPreference(it, KudSettings.DEFAULT_VOICE_TYPING) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, KudSettingKeys.VOICE, R.string.kud_settings_voice, R.string.kud_settings_voice_summary) {
        Preference(it.title, { SettingsDestination.navigateTo(SettingsDestination.Voice) }, description = it.description) {
            NextScreenIcon()
        }
    },
    Setting(context, KudSettingKeys.RESIZE, R.string.kud_resize, R.string.kud_resize_summary) {
        Preference(it.title, { SettingsDestination.navigateTo(KudDestination.Resize) }, description = it.description)
    },
    Setting(context, KudSettingKeys.NUMBER_POSITION, R.string.kud_number_position) { setting ->
        val prefs = rememberPrefChanges()
        var show by rememberSaveable { mutableStateOf(false) }
        val items = listOf(
            stringResource(R.string.kud_number_position_top) to true,
            stringResource(R.string.kud_number_position_numpad) to false,
        )
        val current = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS)
        val selected = items.first { it.second == current }
        Preference(setting.title, { show = true }, description = selected.first)
        if (show) ListPickerDialog(
            onDismissRequest = { show = false },
            items = items,
            onItemSelected = {
                prefs.edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, it.second) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            selectedItem = selected,
            title = { Text(setting.title) },
            getItemName = { it.first },
        )
    },
    Setting(context, KudSettingKeys.ACCENTED, R.string.kud_accented, R.string.kud_accented_summary) { setting ->
        val prefs = rememberPrefChanges()
        KudSwitchRow(setting.title, setting.description, KudSettingsRules.isAccented(prefs)) {
            KudSettingsRules.setAccented(prefs, it)
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, KudSettingKeys.LARGE_KEY_TEXT, R.string.kud_large_key_text, R.string.kud_large_key_text_summary) { setting ->
        val prefs = rememberPrefChanges()
        KudSwitchRow(setting.title, setting.description, KudSettingsRules.isLargeKeyText(prefs)) {
            KudSettingsRules.setLargeKeyText(prefs, it)
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, KudSettingKeys.LONG_PRESS_DURATION, R.string.kud_long_press_duration) { setting ->
        val prefs = rememberPrefChanges()
        var value by remember { mutableFloatStateOf(prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT).toFloat()) }
        KudSliderRow(
            name = setting.title,
            value = value,
            range = 100f..800f,
            valueText = stringResource(R.string.abbreviation_unit_milliseconds, value.roundToInt().toString()),
            onValueChange = { value = (it / 10f).roundToInt() * 10f },
            onValueChangeFinished = { prefs.edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, value.roundToInt()) } },
        )
    },
    Setting(context, KudSettingKeys.SOUND_VOLUME, R.string.kud_sound_volume) { setting ->
        val prefs = rememberPrefChanges()
        val ctx = LocalContext.current
        val soundOn = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
        val stored = prefs.getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
        var value by remember { mutableFloatStateOf(KudSettingsRules.volumeToSteps(stored).toFloat()) }
        KudSliderRow(
            name = setting.title,
            value = value,
            range = 0f..10f,
            valueText = if (stored < 0 && value == KudSettingsRules.volumeToSteps(stored).toFloat()) stringResource(R.string.kud_value_default)
                else value.roundToInt().toString(),
            enabled = soundOn,
            onValueChange = { value = it.roundToInt().toFloat() },
            onValueChangeFinished = {
                val volume = value.roundToInt() / 10f
                prefs.edit { putFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, volume) }
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, volume)
            },
        ) {
            Switch(checked = soundOn, onCheckedChange = { prefs.edit { putBoolean(Settings.PREF_SOUND_ON, it) } })
        }
    },
    Setting(context, KudSettings.PREF_SOUND_PROFILE, R.string.kud_sound_profile) { setting ->
        val prefs = rememberPrefChanges()
        val soundOn = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
        var show by rememberSaveable { mutableStateOf(false) }
        val items = listOf(
            stringResource(R.string.kud_sound_profile_modern) to KudSettings.SOUND_PROFILE_MODERN,
            stringResource(R.string.kud_sound_profile_classic) to KudSettings.SOUND_PROFILE_CLASSIC,
        )
        val current = prefs.getString(setting.key, KudSettings.DEFAULT_SOUND_PROFILE)
        val selected = items.firstOrNull { it.second == current } ?: items.first()
        KudRow(enabled = soundOn) {
            Preference(setting.title, { if (soundOn) show = true }, description = selected.first)
        }
        if (show) ListPickerDialog(
            onDismissRequest = { show = false },
            items = items,
            onItemSelected = { prefs.edit { putString(setting.key, it.second) } },
            selectedItem = selected,
            title = { Text(setting.title) },
            getItemName = { it.first },
        )
    },
    Setting(context, KudSettingKeys.ANDROID_VIBRATION, R.string.kud_android_vibration) { setting ->
        val prefs = rememberPrefChanges()
        KudSwitchRow(setting.title, null, KudSettingsRules.isAndroidVibration(prefs)) {
            KudSettingsRules.setAndroidVibration(prefs, it)
        }
    },
    Setting(context, KudSettingKeys.KEYPRESS_VIBRATION, R.string.kud_keypress_vibration) { setting ->
        val prefs = rememberPrefChanges()
        val androidDefault = KudSettingsRules.isAndroidVibration(prefs)
        val vibrateOn = prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)
        var value by remember { mutableFloatStateOf(prefs.getInt(KudSettings.PREF_VIBRATION_MS, KudSettings.DEFAULT_VIBRATION_MS).toFloat()) }
        KudSliderRow(
            name = setting.title,
            value = value,
            range = 5f..100f,
            valueText = stringResource(R.string.abbreviation_unit_milliseconds, value.roundToInt().toString()),
            enabled = !androidDefault && vibrateOn,
            titleEnabled = !androidDefault,
            onValueChange = { value = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = {
                KudSettingsRules.setVibrationMs(prefs, value.roundToInt())
                AudioAndHapticFeedbackManager.getInstance().vibrate(value.roundToInt().toLong())
            },
        ) {
            // the switch is whether keys vibrate at all, Android default length or not; only the slider is
            // disabled under the Android default
            Switch(
                checked = KudSettingsRules.isKeypressVibration(prefs),
                onCheckedChange = { KudSettingsRules.setKeypressVibration(prefs, it) },
            )
        }
    },
)

/** The pref translations behind the combined rows (tested in KudSettingsTest). */
object KudSettingsRules {
    fun isCursorControl(prefs: SharedPreferences) =
        Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.MOVE_CURSOR

    /** On: sliding on the space bar moves the cursor. Off: it changes the layout (SwiftKey's language swipe). */
    fun setCursorControl(prefs: SharedPreferences, on: Boolean) = prefs.edit {
        putString(Settings.PREF_SPACE_HORIZONTAL_SWIPE,
            (if (on) KeyboardActionListener.SwipeAction.MOVE_CURSOR else KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE).name)
    }

    fun isAccented(prefs: SharedPreferences) =
        prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS) == LocaleKeyboardInfos.POPUP_KEYS_ALL

    fun setAccented(prefs: SharedPreferences, on: Boolean) = prefs.edit {
        putString(Settings.PREF_MORE_POPUP_KEYS, if (on) LocaleKeyboardInfos.POPUP_KEYS_ALL else Defaults.PREF_MORE_POPUP_KEYS)
    }

    fun isLargeKeyText(prefs: SharedPreferences) =
        prefs.getFloat(Settings.PREF_FONT_SCALE, Defaults.PREF_FONT_SCALE) >= KudSettings.LARGE_KEY_TEXT_SCALE - 0.01f

    /** On remembers the font scale set in Appearance (e.g. 1.1), off puts it back instead of resetting to 1.0. */
    fun setLargeKeyText(prefs: SharedPreferences, on: Boolean) = prefs.edit {
        if (on) {
            if (!isLargeKeyText(prefs) && prefs.contains(Settings.PREF_FONT_SCALE))
                putFloat(KudSettings.PREF_FONT_SCALE_BEFORE_LARGE, prefs.getFloat(Settings.PREF_FONT_SCALE, Defaults.PREF_FONT_SCALE))
            putFloat(Settings.PREF_FONT_SCALE, KudSettings.LARGE_KEY_TEXT_SCALE)
        } else {
            if (prefs.contains(KudSettings.PREF_FONT_SCALE_BEFORE_LARGE))
                putFloat(Settings.PREF_FONT_SCALE, prefs.getFloat(KudSettings.PREF_FONT_SCALE_BEFORE_LARGE, Defaults.PREF_FONT_SCALE))
            else remove(Settings.PREF_FONT_SCALE)
            remove(KudSettings.PREF_FONT_SCALE_BEFORE_LARGE)
        }
    }

    /** Keypress vibration on/off, whether it uses the Android default length or the custom one. */
    fun isKeypressVibration(prefs: SharedPreferences) = prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)

    fun setKeypressVibration(prefs: SharedPreferences, on: Boolean) = prefs.edit { putBoolean(Settings.PREF_VIBRATE_ON, on) }

    /** "Use the Android default vibration" is HeliBoard's vibration length -1 (the system's key-press haptic). */
    fun isAndroidVibration(prefs: SharedPreferences) =
        prefs.getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, Defaults.PREF_VIBRATION_DURATION_SETTINGS) < 0

    fun setAndroidVibration(prefs: SharedPreferences, on: Boolean) = prefs.edit {
        if (on) {
            putInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, -1)
            putBoolean(Settings.PREF_VIBRATE_ON, true)
        } else {
            putInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, prefs.getInt(KudSettings.PREF_VIBRATION_MS, KudSettings.DEFAULT_VIBRATION_MS))
        }
    }

    fun setVibrationMs(prefs: SharedPreferences, ms: Int) = prefs.edit {
        putInt(KudSettings.PREF_VIBRATION_MS, ms)
        putInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, ms)
    }

    /** HeliBoard's volume (-0.01 = system default, else 0..1) as SwiftKey's 0..10 steps; the default shows as 2. */
    fun volumeToSteps(volume: Float) = if (volume < 0) 2 else (volume * 10).roundToInt().coerceIn(0, 10)
}

object KudDestination {
    const val Languages = "kud_languages"
    const val Typing = "kud_typing"
    const val Emoji = "kud_emoji"
    const val RichInput = "kud_rich_input"
    const val LayoutKeys = "kud_layout_keys"
    const val Sound = "kud_sound"
    const val Resize = "kud_resize"
    const val PhysicalKeyboard = "kud_physical_keyboard"
    const val More = "kud_advanced"
    const val Account = "kud_account"
    const val Backups = "kud_backups"
}

// ---- rows ----

/** Re-reads prefs when any pref changes (same trick as HeliBoard's screens). */
@Composable
fun rememberPrefChanges(): SharedPreferences {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "trigger recomposition on preference change")
    return ctx.prefs()
}

/** Greys out (and blocks) a row. */
@Composable
fun KudRow(enabled: Boolean, content: @Composable () -> Unit) {
    Box(if (enabled) Modifier else Modifier.alpha(0.38f)) { content() }
}

@Composable
fun KudSwitchRow(name: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var value by remember(checked) { mutableStateOf(checked) }
    KudRow(enabled) {
        Preference(name = name, onClick = { if (enabled) { value = !value; onChange(value) } }, description = description) {
            Switch(checked = value, enabled = enabled, onCheckedChange = { value = it; onChange(it) })
        }
    }
}

/** SwiftKey's inline slider row: title, slider and value below it, optional switch on the right. */
@Composable
fun KudSliderRow(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean = true,
    titleEnabled: Boolean = true,
    steps: Int = 0,
    switch: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = if (titleEnabled) Modifier else Modifier.alpha(0.38f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = if (enabled) Modifier else Modifier.alpha(0.6f)) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = range,
                    steps = steps,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        switch?.invoke()
    }
}

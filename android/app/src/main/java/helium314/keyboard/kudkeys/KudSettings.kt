// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo

/**
 * Kud Keys' own settings (the SwiftKey ones HeliBoard has no pref for), and the small rules behind them.
 * Settings UI: `settings/screens/KudSettingsScreens.kt`.
 */
object KudSettings {
    /** Placeholder for the Account entry at the top of settings (added by another change; hidden until then). */
    const val PREF_ACCOUNT = "kudkeys_account"

    /** Typing → Quick prediction insert: space inserts the middle prediction before the next word is started. */
    const val PREF_QUICK_PREDICTION_INSERT = "kudkeys_quick_prediction_insert"
    const val DEFAULT_QUICK_PREDICTION_INSERT = false

    /** Emoji → Emoji access on enter key: long-press enter opens emoji, only while the emoji key is hidden. */
    const val PREF_EMOJI_ON_ENTER = "kudkeys_emoji_on_enter"
    const val DEFAULT_EMOJI_ON_ENTER = false

    /** Rich input → Voice typing: off hides the Mic, the mic hint above the comma and comma long-press voice. */
    const val PREF_VOICE_TYPING = "kudkeys_voice_typing"
    const val DEFAULT_VOICE_TYPING = true

    /** Sound & vibration → Keypress sound profile: "modern" (a sound per key type) or "classic" (one click). */
    const val PREF_SOUND_PROFILE = "kudkeys_sound_profile"
    const val SOUND_PROFILE_MODERN = "modern"
    const val SOUND_PROFILE_CLASSIC = "classic"
    const val DEFAULT_SOUND_PROFILE = SOUND_PROFILE_MODERN

    /** The custom vibration length, kept while "Use the Android default vibration" is on (which stores -1). */
    const val PREF_VIBRATION_MS = "kudkeys_vibration_ms"
    const val DEFAULT_VIBRATION_MS = 65

    /** Layout & keys → Large key text sets HeliBoard's font scale to this. */
    const val LARGE_KEY_TEXT_SCALE = 1.2f

    /**
     * The word that space should insert for Quick prediction insert, or null when space is just a space.
     * Only while nothing is being composed and the strip shows next-word predictions, and not as the second
     * space of a double-space period.
     */
    fun quickPredictionToInsert(
        enabled: Boolean,
        isComposingWord: Boolean,
        doubleSpacePeriodPending: Boolean,
        suggestedWords: SuggestedWords,
    ): SuggestedWordInfo? {
        if (!enabled || isComposingWord || doubleSpacePeriodPending) return null
        if (!suggestedWords.isPrediction || suggestedWords.isEmpty) return null
        // predictions have no typed word, so the best one (index 0) is the one in the middle of the strip
        val best = suggestedWords.getInfo(0)
        return best.takeIf { it.mWord.isNotBlank() && !it.isEmoji() }
    }

    /** Whether the enter key keeps its long-press emoji. */
    fun emojiOnEnter(emojiKeyShown: Boolean, emojiOnEnterEnabled: Boolean) = !emojiKeyShown && emojiOnEnterEnabled

    /** Popup entries for the voice key, dropped from long-press popups when voice typing is off. */
    fun isVoicePopup(popupSpec: String) = popupSpec.contains("!code/key_voice_input")
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import java.util.Locale

/**
 * A few everyday words per language, shown as predictions when the dictionaries and history have nothing (an empty
 * field, a sentence start, after a word with no known next word), so the strip is never blank. Most likely first.
 * They are capitalised at a sentence start like every other prediction.
 */
object KudStarterWords {
    private val INDONESIAN = listOf("aku", "ya", "udah", "gak", "mau", "ini", "yang", "oke")
    private val ENGLISH = listOf("I", "the", "ok", "thanks", "you")

    fun forLocale(locale: Locale): List<String> = when (locale.language) {
        "id", "in" -> INDONESIAN // Java may still report Indonesian as the legacy "in"
        "en" -> ENGLISH
        else -> emptyList()
    }
}

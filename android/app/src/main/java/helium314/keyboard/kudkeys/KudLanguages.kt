package helium314.keyboard.kudkeys

import android.content.Context
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import java.util.Locale

/**
 * The keyboard's active languages as base codes ("en", "id"): the current layout's language, its secondary
 * languages (multilingual typing), then the other enabled layouts. Voice sends exactly these, so the server
 * never transcribes into a language Hammas hasn't turned on.
 */
object KudLanguages {
    fun active(context: Context): List<String> {
        val selected = runCatching { SubtypeSettings.getSelectedSubtype(context.prefs()) }.getOrNull()
        val enabled = runCatching { SubtypeSettings.getEnabledSubtypes() }.getOrDefault(emptyList())
        val locales = (listOfNotNull(selected) + enabled).flatMap { listOf(it.locale()) + getSecondaryLocales(it.extraValue) }
        return codes(locales)
    }

    /** "in" is Java's legacy code for Indonesian; "zz" and "und" are HeliBoard's and Java's no-language locales. */
    fun codes(locales: List<Locale>): List<String> =
        locales.map { it.toLanguageTag().substringBefore('-').lowercase() }
            .map { if (it == "in") "id" else it }
            .filter { it.isNotBlank() && it != "zz" && it != "und" }
            .distinct()
}

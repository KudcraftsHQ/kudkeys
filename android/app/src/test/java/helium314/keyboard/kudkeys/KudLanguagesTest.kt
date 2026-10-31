package helium314.keyboard.kudkeys

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class KudLanguagesTest {
    @Test fun activeLayoutsBecomeBaseCodesWithoutDuplicates() {
        val locales = listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("id"), Locale("in"), Locale.forLanguageTag("en-GB"))
        assertEquals(listOf("en", "id"), KudLanguages.codes(locales))
    }

    @Test fun noLanguageLocalesAreDropped() {
        assertEquals(listOf("id"), KudLanguages.codes(listOf(Locale("zz"), Locale.ROOT, Locale.forLanguageTag("id-ID"))))
    }
}

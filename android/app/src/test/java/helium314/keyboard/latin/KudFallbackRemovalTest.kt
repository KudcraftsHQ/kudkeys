// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.latin.dictionary.Dictionary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing a filler from the strip (the real DictionaryFacilitatorImpl, no dictionaries loaded): a most-used history
 * word leaves the filler list at once, and a starter word stays removed. Made-up words, except the starter list.
 */
@RunWith(RobolectricTestRunner::class)
class KudFallbackRemovalTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val facilitator = DictionaryFacilitatorImpl()

    private fun field(name: String) = DictionaryFacilitatorImpl::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** One Indonesian dictionary group, and [history] as the most-used history words already read. */
    private fun setUp(history: List<String>) {
        val group = Class.forName("helium314.keyboard.latin.DictionaryGroup")
            .getDeclaredConstructor(Locale::class.java, Dictionary::class.java, Map::class.java, Context::class.java)
            .apply { isAccessible = true }
            .newInstance(Locale.forLanguageTag("id"), null, emptyMap<String, Any>(), context)
        field("dictionaryGroups").set(facilitator, listOf(group))
        field("frequentHistoryWords").set(facilitator, mapOf(Locale.forLanguageTag("id") to history))
        field("frequentHistoryWordsStale").set(facilitator, false)
        field("frequentHistoryWordsReadAt").set(facilitator, Long.MAX_VALUE / 2) // no rescan during the test
    }

    @Test fun aRemovedHistoryWordLeavesTheFillersAtOnce() {
        setUp(listOf("zorbel", "plimko"))
        assertTrue("zorbel" in facilitator.getFallbackPredictions(true))
        facilitator.removeWord("zorbel")
        val after = facilitator.getFallbackPredictions(true)
        assertFalse("zorbel" in after, "$after")
        assertTrue("plimko" in after)
    }

    @Test fun aRemovedStarterWordStaysRemoved() {
        setUp(emptyList())
        val starter = facilitator.getFallbackPredictions(false).first()
        facilitator.removeWord(starter)
        assertFalse(starter in facilitator.getFallbackPredictions(false))
    }
}

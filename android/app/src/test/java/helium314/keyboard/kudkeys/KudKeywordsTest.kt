// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import kotlin.test.Test
import kotlin.test.assertEquals

class KudKeywordsTest {
    @Test fun addsCommaSeparated_dropsCaseDuplicates_andMarkup() {
        val (next, hint) = KudKeywords.add(listOf("Fenno"), " TypeMap, fenno ,<FaceMap>,, ")
        assertEquals(listOf("Fenno", "TypeMap", "FaceMap"), next)
        assertEquals(null, hint)
    }

    @Test fun limits() {
        assertEquals(KudKeywords.Hint.TooLong, KudKeywords.add(emptyList(), "x".repeat(101)).second)
        val full = (1..100).map { "k$it" }
        val (next, hint) = KudKeywords.add(full, "one more")
        assertEquals(full, next)
        assertEquals(KudKeywords.Hint.TooMany, hint)
    }
}

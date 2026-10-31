package com.kudcrafts.kudkeys.voice

import java.util.TreeMap

/**
 * Builds the text from `final` and `partial` messages. Finals are placed by segment (they can
 * arrive out of order, and numbers can be skipped); a partial replaces the previous partial and is
 * dropped once its segment is final. Not thread-safe; [VoiceClient] guards it.
 */
internal class Transcript {
    private val finals = TreeMap<Int, String>()
    private var partialSegment = -1
    private var partialText = ""

    fun partial(segment: Int, text: String) {
        if (finals.containsKey(segment)) return
        partialSegment = segment
        partialText = text
    }

    fun final(segment: Int, text: String) {
        finals[segment] = text
        if (segment == partialSegment) clearPartial()
    }

    private fun clearPartial() {
        partialSegment = -1
        partialText = ""
    }

    val text: String
        get() {
            val parts = TreeMap(finals)
            if (partialSegment >= 0) parts[partialSegment] = partialText
            return parts.values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
}

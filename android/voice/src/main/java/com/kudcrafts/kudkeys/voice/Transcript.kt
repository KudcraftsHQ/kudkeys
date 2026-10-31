package com.kudcrafts.kudkeys.voice

import java.util.TreeMap

/**
 * Builds the text from `final` and `partial` messages. Finals are placed by segment (they can
 * arrive out of order, and numbers can be skipped); a partial replaces the previous partial and is
 * dropped once its segment is final. Not thread-safe; [VoiceClient] guards it.
 */
internal class Transcript {
    private val finals = TreeMap<Int, String>()
    private val confidence = HashMap<Int, Float>()
    private var partialSegment = -1
    private var partialText = ""

    fun partial(segment: Int, text: String) {
        if (finals.containsKey(segment)) return
        partialSegment = segment
        partialText = text
    }

    fun final(segment: Int, text: String, confidence: Float? = null) {
        finals[segment] = text
        if (confidence != null) this.confidence[segment] = confidence else this.confidence.remove(segment)
        if (segment == partialSegment) clearPartial()
    }

    private fun clearPartial() {
        partialSegment = -1
        partialText = ""
    }

    /** The phrase in progress when it is the last one (the end of [text]), else "". Shown dimmed. */
    val partialTail: String
        get() {
            if (partialSegment < 0) return ""
            val lastFinal = if (finals.isEmpty()) -1 else finals.lastKey()
            return if (partialSegment > lastFinal) partialText.trim() else ""
        }

    val text: String
        get() {
            val parts = TreeMap(finals)
            if (partialSegment >= 0) parts[partialSegment] = partialText
            return parts.values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }

    /** The settled phrases in order, with the recogniser's confidence when the server sent one (for tidy). */
    val segments: List<Segment>
        get() = finals.mapNotNull { (n, t) -> t.trim().takeIf { it.isNotEmpty() }?.let { Segment(it, confidence[n]) } }
}

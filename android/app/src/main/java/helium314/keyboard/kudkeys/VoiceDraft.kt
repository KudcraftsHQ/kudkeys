// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.content.SharedPreferences
import com.kudcrafts.kudkeys.voice.TidyQuestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The full-voice draft: [text] is what the editor shows and inserts (tidied, answered, instructed), [raw] the words
 * as dictated. [questions] are open questions about [text] (offsets into it), in order; the first is on the card.
 */
data class VoiceDraft(val text: String, val raw: String, val questions: List<TidyQuestion> = emptyList()) {
    val isEmpty get() = text.isBlank() && raw.isBlank()

    /** Answers [q] with [option] (null = keep as is): the word is replaced and later offsets shift. */
    fun answer(q: TidyQuestion, option: String?): VoiceDraft {
        val rest = questions.filter { it.id != q.id }
        if (option == null || q.end > text.length || text.substring(q.start, q.end) != q.original)
            return copy(questions = rest)
        val delta = option.length - (q.end - q.start)
        val newText = text.replaceRange(q.start, q.end, option)
        val shifted = rest.mapNotNull {
            when {
                it.end <= q.start -> it
                it.start >= q.end -> it.copy(start = it.start + delta, end = it.end + delta)
                else -> null // overlapped the answered word
            }
        }
        return copy(text = newText, questions = shifted)
    }

    /** This draft with [chunk] (new dictation, [chunkRaw] untidied) after it. */
    fun append(chunk: String, chunkRaw: String, chunkQuestions: List<TidyQuestion>): VoiceDraft {
        if (isEmpty) return VoiceDraft(chunk, chunkRaw, chunkQuestions)
        val sep = separator(text, chunk)
        val base = text.trimEnd()
        val offset = base.length + sep.length
        return VoiceDraft(
            base + sep + chunk,
            raw.trimEnd() + separator(raw, chunkRaw) + chunkRaw,
            questions + chunkQuestions.map { it.copy(start = it.start + offset, end = it.end + offset) },
        )
    }

    fun toJson(): String = buildJsonObject {
        put("text", text)
        put("raw", raw)
        putJsonArray("questions") {
            for (q in questions) add(buildJsonObject {
                put("id", q.id); put("original", q.original); put("start", q.start); put("end", q.end)
                putJsonArray("options") { q.options.forEach { add(JsonPrimitive(it)) } }
            })
        }
    }.toString()

    companion object {
        /** Paragraph break when either side has several lines (a list, paragraphs), else a space. */
        fun separator(before: String, after: String): String =
            if (before.isBlank() || after.isBlank()) "" else if ('\n' in before.trim() || '\n' in after.trim()) "\n\n" else " "

        fun fromJson(s: String): VoiceDraft? = runCatching {
            val o = Json.parseToJsonElement(s) as JsonObject
            fun str(e: Any?) = (e as? JsonPrimitive)?.contentOrNull
            val text = str(o["text"]) ?: return null
            val questions = (o["questions"] as? JsonArray).orEmpty().mapNotNull { e ->
                val q = e as? JsonObject ?: return@mapNotNull null
                val start = q["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val end = q["end"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (start < 0 || end > text.length || start >= end) return@mapNotNull null
                TidyQuestion(str(q["id"]) ?: "", str(q["original"]) ?: text.substring(start, end), start, end,
                    (q["options"] as? JsonArray).orEmpty().mapNotNull { str(it) })
            }
            VoiceDraft(text, str(o["raw"]) ?: text, questions)
        }.getOrNull()
    }
}

/** Where the draft and the tidy toggle live between keyboard sessions. */
interface DraftStore {
    fun load(): VoiceDraft?
    /** null or an empty draft clears it. */
    fun save(draft: VoiceDraft?)
    var tidyOn: Boolean
}

/**
 * App-private SharedPreferences `kudkeys_draft`, excluded from backup and device transfer like the token
 * (`res/xml/kud_backup_rules.xml`). Holds the dictated text, so it is never logged.
 */
class PrefsDraftStore(private val prefs: SharedPreferences) : DraftStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun load(): VoiceDraft? = prefs.getString(KEY_DRAFT, null)?.let(VoiceDraft::fromJson)?.takeIf { !it.isEmpty }

    override fun save(draft: VoiceDraft?) {
        if (draft == null || draft.isEmpty) prefs.edit().remove(KEY_DRAFT).apply()
        else prefs.edit().putString(KEY_DRAFT, draft.toJson()).apply()
    }

    override var tidyOn: Boolean
        get() = prefs.getBoolean(KEY_TIDY, true)
        set(value) { prefs.edit().putBoolean(KEY_TIDY, value).apply() }

    companion object {
        const val PREFS = "kudkeys_draft"
        const val KEY_DRAFT = "draft"
        const val KEY_TIDY = "tidy_on"
    }
}

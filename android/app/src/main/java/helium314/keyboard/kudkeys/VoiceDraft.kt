// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.util.AtomicFile
import java.io.File
import java.io.IOException
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

/** A range of a draft's text: [start] inclusive, [end] exclusive. */
data class TextSpan(val start: Int, val end: Int)

/**
 * The full-voice draft: [text] is what the editor shows and inserts (tidied, answered, instructed), [raw] the words
 * as dictated. [questions] are open questions about [text] (offsets into it), in order; the first is on the card.
 * [untidied] are the parts of [text] still as dictated (tidy was off, failed, or hasn't answered yet): the editor
 * offers "Tidy" for them.
 */
data class VoiceDraft(
    val text: String,
    val raw: String,
    val questions: List<TidyQuestion> = emptyList(),
    val untidied: List<TextSpan> = emptyList(),
) {
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
        return copy(text = newText, questions = shifted, untidied = shiftSpans(untidied, q.start, q.end, delta))
    }

    /**
     * This draft with [chunk] (new dictation, [chunkRaw] untidied) after it. [untidiedChunk]: [chunk] is still as
     * dictated, so its range joins [untidied].
     */
    fun append(chunk: String, chunkRaw: String, chunkQuestions: List<TidyQuestion>, untidiedChunk: Boolean = false): VoiceDraft {
        val sep = if (isEmpty) "" else separator(text, chunk)
        val base = if (isEmpty) "" else text.trimEnd()
        val offset = base.length + sep.length
        val span = TextSpan(offset, offset + chunk.length).takeIf { untidiedChunk && chunk.isNotEmpty() }
        return VoiceDraft(
            base + sep + chunk,
            if (isEmpty) chunkRaw else raw.trimEnd() + separator(raw, chunkRaw) + chunkRaw,
            (if (isEmpty) emptyList() else questions) + chunkQuestions.map { it.copy(start = it.start + offset, end = it.end + offset) },
            (if (isEmpty) emptyList() else untidied.filter { it.end <= base.length }) + listOfNotNull(span),
        )
    }

    /**
     * [span] (one of [untidied]) replaced by its tidied version [tidied], with [tidiedQuestions] (offsets into
     * [tidied]). At the end of the text the separator before it is chosen again (a list gets a blank line before it).
     * Null when [span] no longer holds untidied text (the draft changed meanwhile).
     */
    fun tidySpan(span: TextSpan, expected: String, tidied: String, tidiedQuestions: List<TidyQuestion>): VoiceDraft? {
        if (span !in untidied || span.end > text.length || text.substring(span.start, span.end) != expected) return null
        val t = tidied.trim()
        if (t.isEmpty()) return null
        var start = span.start
        var prefix = text.substring(0, span.start)
        if (span.end == text.length && prefix.isNotBlank()) {
            prefix = prefix.trimEnd() + separator(prefix, t)
            start = prefix.length
        }
        val newText = prefix + t + text.substring(span.end)
        val delta = newText.length - text.length
        val kept = questions.mapNotNull {
            when {
                it.end <= span.start -> it
                it.start >= span.end -> it.copy(start = it.start + delta, end = it.end + delta)
                else -> null
            }
        }
        val added = tidiedQuestions.map { it.copy(start = it.start + start, end = it.end + start) }
            .filter { it.end <= newText.length && newText.substring(it.start, it.end) == it.original }
        val spans = untidied.filter { it != span }.map { if (it.start >= span.end) TextSpan(it.start + delta, it.end + delta) else it }
        return VoiceDraft(newText, raw, (kept + added).sortedBy { it.start }, spans)
    }

    /** The text in [span], or null when it is out of range. */
    fun textOf(span: TextSpan): String? = if (span.end <= text.length && span.start in 0..span.end) text.substring(span.start, span.end) else null

    fun toJson(): String = buildJsonObject {
        put("text", text)
        put("raw", raw)
        putJsonArray("untidied") { for (u in untidied) add(buildJsonObject { put("start", u.start); put("end", u.end) }) }
        putJsonArray("questions") {
            for (q in questions) add(buildJsonObject {
                put("id", q.id); put("original", q.original); put("start", q.start); put("end", q.end)
                putJsonArray("options") { q.options.forEach { add(JsonPrimitive(it)) } }
            })
        }
    }.toString()

    companion object {
        /** Paragraph break when either side has several lines (a list, paragraphs), else a space. */
        /** [spans] after [start]..[end] became [delta] chars longer: later ones shift, one around it stretches. */
        private fun shiftSpans(spans: List<TextSpan>, start: Int, end: Int, delta: Int) = spans.mapNotNull {
            when {
                it.end <= start -> it
                it.start >= end -> TextSpan(it.start + delta, it.end + delta)
                it.start <= start && it.end >= end -> TextSpan(it.start, it.end + delta)
                else -> null
            }
        }

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
            val untidied = (o["untidied"] as? JsonArray).orEmpty().mapNotNull { e ->
                val u = e as? JsonObject ?: return@mapNotNull null
                val start = u["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val end = u["end"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                TextSpan(start, end).takeIf { start in 0 until end && end <= text.length }
            }
            VoiceDraft(text, str(o["raw"]) ?: text, questions, untidied)
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
 * The draft is a file, `files/kudkeys_draft.json`, in the app's **credential-encrypted** data directory; the tidy
 * toggle is in the app-private SharedPreferences `kudkeys_draft`. Both are excluded from backup and device transfer
 * like the token (`res/xml/kud_backup_rules.xml`). Holds the dictated text, so it is never logged.
 *
 * Why a file: the app defaults to device-protected storage (`defaultToDeviceProtectedStorage`, so the keyboard types
 * before the first unlock), and device-protected files are readable before the first unlock after a reboot. Android
 * has no public API for a credential-protected context, so the directory is the device-protected one's credential
 * twin (`/data/user_de/N/pkg` → `/data/user/N/pkg`). While the user is still locked the draft lives in memory only and
 * is written once the directory can be used.
 */
class PrefsDraftStore(
    private val prefs: SharedPreferences,
    /** The draft file, or null while it can't be used (user locked). */
    private val draftFile: () -> File?,
) : DraftStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), credentialFile(context))

    private var memory: VoiceDraft? = null
    /** [memory] still has to be written (it changed while the file couldn't be used). */
    private var pending = false

    private fun file(): File? {
        val f = draftFile() ?: return null
        // A draft an earlier build kept in the device-protected prefs moves to the file (and leaves those prefs).
        prefs.getString(KEY_DRAFT, null)?.let { old ->
            if (!f.exists() && !pending) VoiceDraft.fromJson(old)?.let { write(f, it) }
            prefs.edit().remove(KEY_DRAFT).apply()
        }
        if (pending) { pending = false; write(f, memory) }
        return f
    }

    override fun load(): VoiceDraft? {
        val f = file() ?: return memory?.takeIf { !it.isEmpty }
        return runCatching { AtomicFile(f).readFully().toString(Charsets.UTF_8) }.getOrNull()
            ?.let(VoiceDraft::fromJson)?.takeIf { !it.isEmpty }
    }

    override fun save(draft: VoiceDraft?) {
        memory = draft
        val f = file()
        if (f == null) { pending = true; return }
        write(f, draft)
    }

    private fun write(f: File, draft: VoiceDraft?) {
        val af = AtomicFile(f)
        if (draft == null || draft.isEmpty) { af.delete(); return }
        f.parentFile?.mkdirs()
        val out = runCatching { af.startWrite() }.getOrNull() ?: return
        try {
            out.write(draft.toJson().toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (_: IOException) {
            af.failWrite(out)
        }
    }

    override var tidyOn: Boolean
        get() = prefs.getBoolean(KEY_TIDY, true)
        set(value) { prefs.edit().putBoolean(KEY_TIDY, value).apply() }

    companion object {
        const val PREFS = "kudkeys_draft"
        const val KEY_DRAFT = "draft"
        const val KEY_TIDY = "tidy_on"
        const val DRAFT_FILE = "kudkeys_draft.json"

        /**
         * The credential-encrypted data directory: the app's data dir when that isn't the device-protected one, else
         * its `/user_de/` → `/user/` twin. Null when it can't be told (then the draft stays in memory).
         */
        fun credentialDataDir(info: ApplicationInfo): File? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return info.dataDir?.let(::File)
            val de = info.deviceProtectedDataDir ?: return info.dataDir?.let(::File)
            val data = info.dataDir
            if (data != null && File(data) != File(de)) return File(data)
            val ce = de.replaceFirst("/user_de/", "/user/")
            return if (ce != de) File(ce) else null
        }

        private fun credentialFile(context: Context): () -> File? {
            val dir = credentialDataDir(context.applicationInfo)?.let { File(it, "files") }
            val users = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.getSystemService(UserManager::class.java) else null
            return {
                if (dir == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && users?.isUserUnlocked == false)) null
                else File(dir, DRAFT_FILE)
            }
        }
    }
}

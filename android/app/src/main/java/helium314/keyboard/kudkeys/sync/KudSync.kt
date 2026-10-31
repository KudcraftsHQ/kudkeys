// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.kudcrafts.kudkeys.voice.BackupInfo
import com.kudcrafts.kudkeys.voice.SyncClient
import com.kudcrafts.kudkeys.voice.SyncResult
import com.kudcrafts.kudkeys.voice.SyncWord
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.applySettingsLikeRestore
import helium314.keyboard.settings.preferences.reloadAfterPrefsChanged
import helium314.keyboard.settings.preferences.reloadAfterRestore
import helium314.keyboard.settings.preferences.restoreBackupZip
import helium314.keyboard.settings.preferences.writeBackupZip
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How a sync, backup or restore went, as the Account screen shows it. */
sealed interface KudSyncOutcome {
    data object Done : KudSyncOutcome
    /** No session on this phone: nothing was sent. */
    data object NotSignedIn : KudSyncOutcome
    /** 401: the account is now marked signed out and the background work is cancelled. */
    data object SignedOut : KudSyncOutcome
    /** No network, 5xx, timeout: WorkManager tries again. */
    data object Retry : KudSyncOutcome
    data class Failed(val message: String) : KudSyncOutcome
}

/** A word learned from typing: its use count and when it was last used (epoch ms; 0 = unknown). */
data class LearnedWord(val count: Int, val lastUsed: Long)

/** The words Kud Keys learned from typing (the user-history dictionaries). */
fun interface KudLearnedWords {
    /** By lang (`en`, `id`) then word. Null: couldn't read them. */
    fun read(): Map<String, Map<String, LearnedWord>>?

    /** Takes [words] out of the user history of every enabled locale of [lang], so they stop being suggested. */
    fun remove(lang: String, words: Collection<String>) {}
}

/** Where the synced add-on dictionaries go: one per enabled locale of each lang. */
fun interface KudSyncedDicts {
    fun rebuild(langs: Set<String>, mirror: Map<String, Map<String, Int>>)
}

object KudSyncLocales {
    /** Every locale the keyboard types in: each enabled subtype's main locale and its secondary locales. */
    fun enabled(): List<Locale> = SubtypeSettings.getEnabledSubtypes(true)
        .flatMap { listOf(it.locale()) + getSecondaryLocales(it.extraValue) }
        .distinctBy { it.toLanguageTag() }

    /** The language the server keys words by: `en-US` → `en`, `in`/`id` → `id`. */
    fun lang(locale: Locale): String = lang(locale.toLanguageTag())
    fun lang(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase().let { if (it == "in") "id" else it }
}

/** The user-history dictionaries of the enabled locales, read word by word (`getNextWordProperty`). */
class UserHistoryWords(private val context: Context) : KudLearnedWords {
    override fun read(): Map<String, Map<String, LearnedWord>>? = try {
        val out = mutableMapOf<String, MutableMap<String, LearnedWord>>()
        for (locale in KudSyncLocales.enabled()) {
            val props = PersonalizationHelper.getUserHistoryDictionary(context, locale).readAllWordProperties(10_000)
                ?: return null
            val words = out.getOrPut(KudSyncLocales.lang(locale)) { mutableMapOf() }
            for (p in props) {
                // the server keeps single words (and so does the synced dictionary)
                if (p.mIsNotAWord || p.mIsBeginningOfSentence || p.mWord.isNullOrBlank() || p.mWord.any(Char::isWhitespace)) continue
                val info = p.mProbabilityInfo
                val count = info?.mCount?.coerceAtLeast(1) ?: 1
                // the historical timestamp is the word's last use, in seconds
                val lastUsed = if (info != null && info.hasHistoricalInfo() && info.mTimestamp > 0) info.mTimestamp * 1000L else 0L
                val prev = words[p.mWord]
                words[p.mWord] = LearnedWord(maxOf(prev?.count ?: 0, count), maxOf(prev?.lastUsed ?: 0, lastUsed))
            }
        }
        out
    } catch (t: Throwable) { // also UnsatisfiedLinkError: no native dictionary code
        Log.w(TAG, "couldn't read the typed words: ${t.javaClass.simpleName}")
        null
    }

    override fun remove(lang: String, words: Collection<String>) {
        if (words.isEmpty()) return
        try {
            for (locale in KudSyncLocales.enabled()) {
                if (KudSyncLocales.lang(locale) != lang) continue
                val dict = PersonalizationHelper.getUserHistoryDictionary(context, locale)
                words.forEach(dict::removeUnigramEntryDynamically)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't remove from the typed words: ${t.javaClass.simpleName}")
        }
    }

    private companion object { const val TAG = "KudSync" }
}

/** Writes `synced_user.dict` for every enabled locale whose lang changed, then reloads the keyboard's dictionaries. */
class SyncedDictFiles(private val context: Context) : KudSyncedDicts {
    override fun rebuild(langs: Set<String>, mirror: Map<String, Map<String, Int>>) {
        if (langs.isEmpty()) return
        var any = false
        for (locale in KudSyncLocales.enabled()) {
            val lang = KudSyncLocales.lang(locale)
            if (lang !in langs) continue
            val file = KudSyncedDictionary.file(context, locale) ?: continue
            KudSyncedDictionary.write(file, locale, mirror[lang].orEmpty())
            any = true
        }
        if (any) KudSyncedDictionary.notifyChanged(context)
    }
}

/**
 * Settings and words sync, backups and restore (docs/v0.4-spec.md "Phone"). One run at a time. Every call needs
 * the session token; a 401 marks the account signed out and cancels the background work. Never logs words or
 * settings values.
 */
class KudSync(
    private val context: Context,
    private val settings: VoiceSettings = VoiceSettings(context.applicationContext),
    private val client: SyncClient = SyncClient({ settings.apiBase }),
    val store: KudSyncStore = KudSyncStore(context),
    private val learned: KudLearnedWords = UserHistoryWords(context.applicationContext),
    private val dicts: KudSyncedDicts = SyncedDictFiles(context.applicationContext),
    private val prefs: SharedPreferences = context.prefs(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSignedOut: () -> Unit = { KudSyncWork.cancelAll(context) },
) {
    /** Pull, then push: settings (merged by the server, last writer wins per key) and words. */
    suspend fun sync(): KudSyncOutcome = run(SYNC_ERROR) { token -> syncLocked(token) }

    /** Builds the backup zip (minus secrets) and uploads it. [kind]: `auto` or `manual`. */
    suspend fun backup(kind: String): KudSyncOutcome = run(BACKUP_ERROR) { token ->
        val zip = withContext(Dispatchers.IO) { buildBackup() }
        if (zip.size > MAX_BACKUP_BYTES) return@run KudSyncOutcome.Failed("Backup too big (${zip.size / 1_000_000} MB)")
        when (val r = client.uploadBackup(token, zip, store.state.deviceId, deviceName(), kind)) { // with its sha256
            is SyncResult.Ok -> {
                store.update { it.copy(lastBackupAt = clock(), lastError = null) }
                KudSyncOutcome.Done
            }
            else -> r.fail(BACKUP_ERROR)
        }
    }

    suspend fun listBackups(): SyncResult<List<BackupInfo>> {
        val token = settings.sessionToken
        if (token.isEmpty()) return SyncResult.SignedOut
        return client.listBackups(token).also { if (it is SyncResult.SignedOut) signedOut() }
    }

    suspend fun deleteBackup(id: String): KudSyncOutcome = run(DELETE_ERROR) { token ->
        when (val r = client.deleteBackup(token, id)) {
            is SyncResult.Ok, SyncResult.NotFound -> KudSyncOutcome.Done
            else -> r.fail(DELETE_ERROR)
        }
    }

    /**
     * Downloads backup [id] and applies it exactly like HeliBoard's restore (files, prefs, then the reload), then
     * syncs: the restored state is now the newest.
     */
    suspend fun restore(id: String): KudSyncOutcome = withContext(NonCancellable) {
        // Not cancelled when the Backups screen closes: once files are being replaced the restore, its reload and
        // the sync after it always finish, and the outcome is recorded (store.state) for the Account screen.
        run(RESTORE_ERROR) { token ->
            val zip = when (val r = client.downloadBackup(token, id)) {
                is SyncResult.Ok -> r.value
                else -> return@run r.fail(RESTORE_ERROR)
            }
            try {
                restoreBackupZip(context, ByteArrayInputStream(zip))
            } catch (t: Throwable) {
                Log.w(TAG, "restore failed: ${t.javaClass.simpleName}")
                return@run KudSyncOutcome.Failed(RESTORE_ERROR)
            } finally {
                onMain { reloadAfterRestore(context) }
            }
            // The synced dictionaries come back from the mirror. Words already sent aren't sent again (each typed
            // word goes with its own last-use time, so an old one never revives a removal).
            dicts.rebuild(store.mirror.keys, store.mirror)
            // exact: the server ends up holding the restored settings, keys added since that backup removed
            syncLocked(token, exact = true)
        }
    }

    /**
     * A suggestion removed from the strip: a tombstone for each enabled lang, and the word leaves the synced
     * dictionaries now. Only while signed in. Blocking file work: call off the main thread.
     */
    fun recordRemoved(word: String) {
        // signed in, or signed out from an account that may sign back in (the removal then goes first)
        if (word.isBlank() || (!settings.isSignedIn && store.state.account.isEmpty())) return
        val langs = KudSyncLocales.enabled().map(KudSyncLocales::lang).distinct()
        val at = clock()
        store.addTombstones(langs.map { KudTombstone(word, it, at) })
        // also out of the other subtypes' user history of the same language, so it isn't read back as typed
        langs.forEach { learned.remove(it, listOf(word)) }
        store.pushed = store.pushed.mapValues { (_, w) -> w - word }
        val mirror = store.mirror
        val hit = mirror.filterValues { word in it }.keys
        if (hit.isNotEmpty()) {
            val next = mirror.mapValues { (lang, w) -> if (lang in hit) w - word else w }
            store.mirror = next
            dicts.rebuild(hit, next)
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private suspend fun run(errorText: String, block: suspend (String) -> KudSyncOutcome): KudSyncOutcome = lock.withLock {
        val token = settings.sessionToken
        if (token.isEmpty()) return KudSyncOutcome.NotSignedIn
        // the bookkeeping files (the mirror alone is ~0.5 MB of JSON) are never read or written on the main thread
        val outcome = withContext(Dispatchers.IO) { block(token) }
        when (outcome) {
            KudSyncOutcome.SignedOut -> signedOut()
            KudSyncOutcome.Retry -> store.update { it.copy(lastError = errorText) }
            is KudSyncOutcome.Failed -> store.update { it.copy(lastError = outcome.message) }
            else -> {}
        }
        outcome
    }

    /**
     * One sync. Settings first: only what changed since the last sync goes up (and `null` for what is gone); the
     * merged answer comes back down. Then words: pending removals go up first, everything changed is pulled, and
     * the typed words go up with their own last-use time. [exact] (after a restore): the server is made to hold
     * exactly this phone's settings.
     */
    private suspend fun syncLocked(token: String, exact: Boolean = false): KudSyncOutcome {
        val start = store.state
        val initial = !start.initialDone

        // --- settings ---
        var base = store.syncedSettings
        if (initial || exact) when (val r = client.getSettings(token)) {
            is SyncResult.Ok -> {
                val server = KudSettingsSync.syncedOnly(r.value.prefs)
                when {
                    exact -> base = server // whatever the server has beyond the restored prefs goes as null
                    base == null -> { // first sync of this phone on this account: the server's copy comes first
                        applyPrefs(KudSettingsSync.changes(prefs, KudSettingsSync.fromJson(server, prefs.all)), emptySet())
                        base = server
                    }
                    // else: the same account signed back in. Changes made here while signed out are newer than
                    // what the server had at the last sync, so they go up instead of being overwritten.
                }
            }
            SyncResult.NotFound -> if (exact) base = JsonObject(emptyMap())
            else -> return r.fail(SYNC_ERROR)
        }
        val sent = KudSettingsSync.diff(base ?: JsonObject(emptyMap()), KudSettingsSync.toJson(KudSettingsSync.export(prefs)))
        when (val r = client.putSettings(token, sent, iso(clock()), start.deviceId)) {
            is SyncResult.Ok -> {
                val merged = KudSettingsSync.syncedOnly(r.value.prefs)
                val incoming = KudSettingsSync.fromJson(merged, prefs.all)
                // a synced key the server no longer has was reset (or removed) on another phone, or by a restore
                val gone = KudSettingsSync.export(prefs).keys - incoming.keys
                applyPrefs(KudSettingsSync.changes(prefs, incoming), gone)
                store.syncedSettings = merged
                store.update { it.copy(settingsHash = KudSettingsSync.hash(KudSettingsSync.export(prefs)), settingsUpdatedAt = clock()) }
            }
            else -> return r.fail(SYNC_ERROR)
        }

        // --- words ---
        val typed = learned.read()

        // removals made here go first, so the pull below already reflects them
        if (!postWords(token, store.tombstones.map { SyncWord(it.word, it.lang, 0, SyncWord.SOURCE_TYPED, true, iso(it.at)) })) {
            return lastFail ?: KudSyncOutcome.Retry
        }

        // pull: everything on the first sync, then what changed since the last pull
        when (val r = client.getWords(token, if (initial) null else start.wordsSince)) {
            is SyncResult.Ok -> {
                val mirror = (if (initial) emptyMap() else store.mirror).mapValues { it.value.toMutableMap() }.toMutableMap()
                val removed = store.removed.mapValues { it.value.toMutableMap() }.toMutableMap()
                val changed = mutableSetOf<String>()
                val forget = mutableMapOf<String, MutableSet<String>>() // removed elsewhere: out of the user history too
                if (initial) changed += store.mirror.keys // drop what a previous account left
                for (w in r.value.words) {
                    val lang = KudSyncLocales.lang(w.locale)
                    val words = mirror.getOrPut(lang) { mutableMapOf() }
                    val before = words[w.word]
                    if (w.deleted) {
                        words.remove(w.word)
                        val at = parseIso(w.updatedAt) ?: clock()
                        removed.getOrPut(lang) { mutableMapOf() }.merge(w.word, at) { a, b -> maxOf(a, b) }
                        // unless it was typed again here after that removal
                        val used = typed?.get(lang)?.get(w.word)
                        if (used != null && used.lastUsed <= at) forget.getOrPut(lang) { mutableSetOf() } += w.word
                    } else {
                        words[w.word] = maxOf(1, w.count)
                        removed[lang]?.remove(w.word)
                    }
                    if (words[w.word] != before) changed += lang
                }
                store.mirror = mirror
                store.removed = removed.filterValues { it.isNotEmpty() }
                dicts.rebuild(changed, mirror)
                forget.forEach { (lang, words) -> learned.remove(lang, words) }
                store.update { it.copy(wordsSince = r.value.now.ifEmpty { null } ?: it.wordsSince) }
            }
            else -> return r.fail(SYNC_ERROR)
        }

        // push: typed words whose count changed since they were last pushed, each with its own last-use time;
        // never one last used before it was removed (here or elsewhere)
        val removed = store.removed
        val pending = store.tombstones.associate { (it.lang to it.word) to it.at }
        val pushed = store.pushed
        val rows = mutableListOf<SyncWord>()
        typed?.forEach { (lang, words) ->
            words.forEach { (word, w) ->
                val gone = maxOf(removed[lang]?.get(word) ?: -1, pending[lang to word] ?: -1)
                if (gone >= 0 && w.lastUsed <= gone) return@forEach
                if (pushed[lang]?.get(word) != w.count) rows += SyncWord(word, lang, w.count, SyncWord.SOURCE_TYPED, false, iso(w.lastUsed))
            }
        }
        if (!postWords(token, rows)) return lastFail ?: KudSyncOutcome.Retry

        store.update { it.copy(lastSyncAt = clock(), lastError = null, initialDone = true) }
        Log.i(TAG, "synced: ${rows.size} words sent") // counts only
        return KudSyncOutcome.Done
    }

    private var lastFail: KudSyncOutcome? = null

    /** Posts [rows] in batches; after each, records what went (counts pushed, tombstones sent and now known). */
    private suspend fun postWords(token: String, rows: List<SyncWord>): Boolean {
        for (batch in rows.chunked(SyncClient.MAX_WORDS_PER_CALL)) {
            when (val r = client.postWords(token, batch)) {
                is SyncResult.Ok -> {
                    val pushed = store.pushed.mapValues { it.value.toMutableMap() }.toMutableMap()
                    val removed = store.removed.mapValues { it.value.toMutableMap() }.toMutableMap()
                    for (w in batch) {
                        if (w.deleted) {
                            pushed[w.locale]?.remove(w.word)
                            removed.getOrPut(w.locale) { mutableMapOf() }.merge(w.word, parseIso(w.updatedAt) ?: clock()) { a, b -> maxOf(a, b) }
                        } else {
                            pushed.getOrPut(w.locale) { mutableMapOf() }[w.word] = w.count
                            removed[w.locale]?.remove(w.word) // typed again after its removal: it's back
                        }
                    }
                    store.pushed = pushed
                    store.removed = removed.filterValues { it.isNotEmpty() }
                    val sent = batch.filter { it.deleted }.map { it.locale to it.word }.toSet()
                    if (sent.isNotEmpty()) store.tombstones = store.tombstones.filterNot { (it.lang to it.word) in sent }
                }
                else -> {
                    lastFail = r.fail(SYNC_ERROR)
                    return false
                }
            }
        }
        return true
    }

    private fun buildBackup(): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupZip(context, out) { !KudSettingsSync.isSecret(it) }
        return out.toByteArray()
    }

    /** Writes prefs the way HeliBoard's restore does, removes [removed] (back to their defaults), then reloads the keyboard. */
    private fun applyPrefs(changes: Map<String, Any>, removed: Set<String>) {
        if (changes.isEmpty() && removed.isEmpty()) return
        if (removed.isNotEmpty()) prefs.edit().apply { removed.forEach(::remove) }.commit()
        if (changes.isNotEmpty()) applySettingsLikeRestore(changes, prefs)
        onMain { reloadAfterPrefsChanged(context) }
    }

    private fun signedOut() {
        settings.markSignedOut()
        onSignedOut()
    }

    private fun SyncResult<*>.fail(errorText: String): KudSyncOutcome = when (this) {
        SyncResult.SignedOut -> KudSyncOutcome.SignedOut
        SyncResult.NoNetwork, SyncResult.Unavailable -> KudSyncOutcome.Retry
        is SyncResult.Failed -> KudSyncOutcome.Failed("$errorText ($message)")
        SyncResult.NotFound -> KudSyncOutcome.Failed("$errorText (not found)")
        is SyncResult.Ok -> KudSyncOutcome.Done
    }

    companion object {
        private const val TAG = "KudSync"
        const val SYNC_ERROR = "Couldn't sync, will retry"
        const val BACKUP_ERROR = "Couldn't back up, will retry"
        const val RESTORE_ERROR = "Couldn't restore this backup"
        const val DELETE_ERROR = "Couldn't delete this backup"
        const val MAX_BACKUP_BYTES = 20_000_000
        private val lock = Mutex()

        /** For work a screen starts that must outlive it (a restore): main thread, never cancelled by a screen. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** For LatinIME (Java): [recordRemoved] with the default wiring. Blocking: call off the main thread. */
        @JvmStatic
        fun suggestionRemoved(context: Context, word: String) = KudSync(context.applicationContext).recordRemoved(word)

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
        fun parseIso(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() && it != "unknown" }
            .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], true)) listOf(parts[1]) else parts }
            .joinToString(" ").ifBlank { "Android" }

        /** Runs [f] on the main thread and waits (up to 5 s) for it. */
        internal fun onMain(f: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) return f()
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { try { f() } finally { done.countDown() } }
            done.await(5, TimeUnit.SECONDS)
        }
    }
}

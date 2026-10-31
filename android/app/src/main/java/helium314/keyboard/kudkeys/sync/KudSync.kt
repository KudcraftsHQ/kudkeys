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
import kotlinx.coroutines.Dispatchers
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

/** The words Kud Keys learned from typing, by lang (`en`, `id`) then word → use count. Null: couldn't read them. */
fun interface KudLearnedWords {
    fun read(): Map<String, Map<String, Int>>?
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
    override fun read(): Map<String, Map<String, Int>>? = try {
        val out = mutableMapOf<String, MutableMap<String, Int>>()
        for (locale in KudSyncLocales.enabled()) {
            val props = PersonalizationHelper.getUserHistoryDictionary(context, locale).readAllWordProperties(10_000)
                ?: return null
            val words = out.getOrPut(KudSyncLocales.lang(locale)) { mutableMapOf() }
            for (p in props) {
                if (p.mIsNotAWord || p.mIsBeginningOfSentence || p.mWord.isNullOrBlank()) continue
                val count = p.mProbabilityInfo?.mCount?.coerceAtLeast(1) ?: 1
                words[p.mWord] = maxOf(words[p.mWord] ?: 0, count)
            }
        }
        out
    } catch (t: Throwable) { // also UnsatisfiedLinkError: no native dictionary code
        Log.w(TAG, "couldn't read the typed words: ${t.javaClass.simpleName}")
        null
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
        when (val r = client.uploadBackup(token, zip, store.state.deviceId, deviceName(), kind)) {
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
    suspend fun restore(id: String): KudSyncOutcome = run(RESTORE_ERROR) { token ->
        val zip = when (val r = client.downloadBackup(token, id)) {
            is SyncResult.Ok -> r.value
            else -> return@run r.fail(RESTORE_ERROR)
        }
        try {
            withContext(Dispatchers.IO) { restoreBackupZip(context, ByteArrayInputStream(zip)) }
        } catch (t: Throwable) {
            Log.w(TAG, "restore failed: ${t.javaClass.simpleName}")
            return@run KudSyncOutcome.Failed(RESTORE_ERROR)
        } finally {
            onMain { reloadAfterRestore(context) }
        }
        // the restored prefs and typed words go up as new; the synced dictionaries come back from the mirror
        store.update { it.copy(settingsHash = null, settingsUpdatedAt = clock()) }
        store.pushed = emptyMap()
        withContext(Dispatchers.IO) { dicts.rebuild(store.mirror.keys, store.mirror) }
        syncLocked(token)
    }

    /**
     * A suggestion removed from the strip: a tombstone for each enabled lang, and the word leaves the synced
     * dictionaries now. Only while signed in. Blocking file work: call off the main thread.
     */
    fun recordRemoved(word: String) {
        if (!settings.isSignedIn || word.isBlank()) return
        val langs = KudSyncLocales.enabled().map(KudSyncLocales::lang).distinct()
        store.addTombstones(langs.map { KudTombstone(word, it, clock()) })
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
        val outcome = block(token)
        when (outcome) {
            KudSyncOutcome.SignedOut -> signedOut()
            KudSyncOutcome.Retry -> store.update { it.copy(lastError = errorText) }
            is KudSyncOutcome.Failed -> store.update { it.copy(lastError = outcome.message) }
            else -> {}
        }
        outcome
    }

    private suspend fun syncLocked(token: String): KudSyncOutcome {
        val start = store.state
        val initial = !start.initialDone

        // settings: on the first sync after sign-in the server's copy comes first
        var local = KudSettingsSync.export(prefs)
        var updatedAt = if (KudSettingsSync.hash(local) != start.settingsHash) clock() else start.settingsUpdatedAt
        if (initial) when (val r = client.getSettings(token)) {
            is SyncResult.Ok -> {
                applyPrefs(KudSettingsSync.changes(prefs, KudSettingsSync.fromJson(r.value.prefs, prefs.all)))
                local = KudSettingsSync.export(prefs)
                updatedAt = parseIso(r.value.updatedAt) ?: updatedAt
            }
            SyncResult.NotFound -> {}
            else -> return r.fail(SYNC_ERROR)
        }
        when (val r = client.putSettings(token, KudSettingsSync.toJson(local), iso(updatedAt), start.deviceId)) {
            is SyncResult.Ok -> {
                val changes = KudSettingsSync.changes(prefs, KudSettingsSync.fromJson(r.value.prefs, prefs.all))
                if (changes.isNotEmpty()) {
                    applyPrefs(changes)
                    updatedAt = parseIso(r.value.updatedAt) ?: updatedAt
                }
                store.update { it.copy(settingsHash = KudSettingsSync.hash(KudSettingsSync.export(prefs)), settingsUpdatedAt = updatedAt) }
            }
            else -> return r.fail(SYNC_ERROR)
        }

        // words, pull: everything on the first sync, then what changed since the last pull
        when (val r = client.getWords(token, if (initial) null else start.wordsSince)) {
            is SyncResult.Ok -> {
                val mirror = (if (initial) emptyMap() else store.mirror).mapValues { it.value.toMutableMap() }.toMutableMap()
                val changed = mutableSetOf<String>()
                if (initial) changed += store.mirror.keys // drop what a previous account left
                for (w in r.value.words) {
                    val lang = KudSyncLocales.lang(w.locale)
                    val words = mirror.getOrPut(lang) { mutableMapOf() }
                    val before = words[w.word]
                    if (w.deleted) words.remove(w.word) else words[w.word] = maxOf(1, w.count)
                    if (words[w.word] != before) changed += lang
                }
                store.mirror = mirror
                withContext(Dispatchers.IO) { dicts.rebuild(changed, mirror) }
                store.update { it.copy(wordsSince = r.value.now.ifEmpty { null } ?: it.wordsSince) }
            }
            else -> return r.fail(SYNC_ERROR)
        }

        // words, push: typed words whose count changed since they were last pushed, and the tombstones
        val typed = withContext(Dispatchers.IO) { learned.read() }
        val pushed = store.pushed.mapValues { it.value.toMutableMap() }.toMutableMap()
        val nowIso = iso(clock())
        val rows = mutableListOf<SyncWord>()
        typed?.forEach { (lang, words) ->
            words.forEach { (word, count) ->
                if (pushed[lang]?.get(word) != count) rows += SyncWord(word, lang, count, SyncWord.SOURCE_TYPED, false, nowIso)
            }
        }
        val tombstones = store.tombstones
        rows += tombstones.map { SyncWord(it.word, it.lang, 0, SyncWord.SOURCE_TYPED, true, iso(it.at)) }
        for (batch in rows.chunked(SyncClient.MAX_WORDS_PER_CALL)) {
            when (val r = client.postWords(token, batch)) {
                is SyncResult.Ok -> {
                    for (w in batch) {
                        if (w.deleted) pushed[w.locale]?.remove(w.word)
                        else pushed.getOrPut(w.locale) { mutableMapOf() }[w.word] = w.count
                    }
                    store.pushed = pushed
                    val sent = batch.filter { it.deleted }.map { it.locale to it.word }.toSet()
                    if (sent.isNotEmpty()) store.tombstones = store.tombstones.filterNot { (it.lang to it.word) in sent }
                }
                else -> return r.fail(SYNC_ERROR)
            }
        }

        store.update { it.copy(lastSyncAt = clock(), lastError = null, initialDone = true) }
        Log.i(TAG, "synced: ${rows.size} words sent") // counts only
        return KudSyncOutcome.Done
    }

    private fun buildBackup(): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupZip(context, out) { !KudSettingsSync.isSecret(it) }
        return out.toByteArray()
    }

    /** Writes prefs the way HeliBoard's restore does, then reloads the keyboard. */
    private fun applyPrefs(changes: Map<String, Any>) {
        if (changes.isEmpty()) return
        applySettingsLikeRestore(changes, prefs)
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

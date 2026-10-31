// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** Sync bookkeeping for this device. Never synced, never in a backup. */
@Serializable
data class KudSyncState(
    val deviceId: String = "",
    /** Last successful sync / backup, epoch ms (0 = never). */
    val lastSyncAt: Long = 0,
    val lastBackupAt: Long = 0,
    /** What the last sync or backup ran into, as the Account screen shows it; null when it went fine. */
    val lastError: String? = null,
    /** Account → Sync → Automatic backup. */
    val autoBackup: Boolean = true,
    /** The server's `now` of the last words pull: the next pull asks for words changed since then. */
    val wordsSince: String? = null,
    /** Hash of the synced prefs as they were after the last sync, and when they last changed on this device. */
    val settingsHash: String? = null,
    val settingsUpdatedAt: Long = 0,
    /** The first sync after a sign-in (pull first, then push) has run. */
    val initialDone: Boolean = false,
    /** The account (email) this bookkeeping belongs to; "" before the first sign-in. */
    val account: String = "",
)

/** A suggestion removed from the strip, waiting to go to the server as a tombstone. */
@Serializable
data class KudTombstone(val word: String, val lang: String, val at: Long)

/**
 * Where sync keeps its state: `no_backup/kudkeys_sync/` in the app's normal (credential-encrypted) storage, so
 * neither HeliBoard's backup zip nor Android backup picks it up. Words are files here, never prefs or logs.
 */
class KudSyncStore(private val dir: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "kudkeys_sync"))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    var state: KudSyncState
        get() = synchronized(lock) {
            val s = read("state.json") { json.decodeFromString<KudSyncState>(it) } ?: KudSyncState()
            if (s.deviceId.isNotEmpty()) s else s.copy(deviceId = UUID.randomUUID().toString()).also { write("state.json", json.encodeToString(it)) }
        }
        set(value) = synchronized(lock) { write("state.json", json.encodeToString(value)) }

    fun update(f: (KudSyncState) -> KudSyncState) = synchronized(lock) { state = f(state) }

    /**
     * Words known to be removed, by lang then word → removal time (epoch ms): removed here (strip) or pulled as a
     * tombstone. A typed word last used before its removal is never sent again, so it can't revive the tombstone.
     */
    var removed: Map<String, Map<String, Long>>
        get() = synchronized(lock) { read("removed.json") { json.decodeFromString<Map<String, Map<String, Long>>>(it) } ?: emptyMap() }
        set(value) = synchronized(lock) { write("removed.json", json.encodeToString(value)) }

    /**
     * The synced settings as the server held them after the last sync (grouped JSON, synced keys only). The next
     * sync sends only what differs from it: changed keys, and `null` for keys that are gone (reset to default).
     */
    var syncedSettings: JsonObject?
        get() = synchronized(lock) { read("settings.json") { json.parseToJsonElement(it) as? JsonObject } }
        set(value) = synchronized(lock) { if (value == null) File(dir, "settings.json").delete() else write("settings.json", value.toString()) }

    /** Counts last pushed, by lang then word. */
    var pushed: Map<String, Map<String, Int>>
        get() = synchronized(lock) { read("pushed.json") { json.decodeFromString<Map<String, Map<String, Int>>>(it) } ?: emptyMap() }
        set(value) = synchronized(lock) { write("pushed.json", json.encodeToString(value)) }

    /** The server's words (not deleted), by lang then word: what the synced dictionaries are built from. */
    var mirror: Map<String, Map<String, Int>>
        get() = synchronized(lock) { read("mirror.json") { json.decodeFromString<Map<String, Map<String, Int>>>(it) } ?: emptyMap() }
        set(value) = synchronized(lock) { write("mirror.json", json.encodeToString(value)) }

    var tombstones: List<KudTombstone>
        get() = synchronized(lock) { read("tombstones.json") { json.decodeFromString<List<KudTombstone>>(it) } ?: emptyList() }
        set(value) = synchronized(lock) { write("tombstones.json", json.encodeToString(value)) }

    fun addTombstones(t: List<KudTombstone>) = synchronized(lock) {
        val keys = t.map { it.lang to it.word }.toSet()
        tombstones = tombstones.filterNot { (it.lang to it.word) in keys } + t
    }

    /**
     * Signed out (on purpose or by a 401): the next sign-in starts with a full pull. What belongs to the account
     * is kept, above all the removals not sent yet, in case the same account signs back in ([onSignedIn]).
     */
    fun resetForSignOut() = synchronized(lock) {
        update { it.copy(initialDone = false, wordsSince = null, lastError = null, lastSyncAt = 0, lastBackupAt = 0) }
    }

    /**
     * Just signed in as [account]. The same account carries on where it left off (pending removals go first, words
     * already sent aren't sent again, settings changed while signed out stay); another account starts clean.
     */
    fun onSignedIn(account: String) = synchronized(lock) {
        val s = state
        if (s.account.isNotEmpty() && s.account.equals(account, ignoreCase = true)) {
            resetForSignOut()
            return@synchronized
        }
        listOf("pushed.json", "mirror.json", "tombstones.json", "removed.json", "settings.json").forEach { File(dir, it).delete() }
        state = KudSyncState(deviceId = s.deviceId, autoBackup = s.autoBackup, account = account)
    }

    private fun <T> read(name: String, parse: (String) -> T): T? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return runCatching { parse(AtomicFile(f).readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun write(name: String, text: String) {
        runCatching {
            dir.mkdirs()
            val af = AtomicFile(File(dir, name))
            val out = af.startWrite()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                af.finishWrite(out)
            } catch (t: Throwable) {
                af.failWrite(out)
                throw t
            }
        }
    }
}

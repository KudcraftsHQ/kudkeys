// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** Signed out: forget what was synced (the next sign-in starts with a full pull). Keeps device id and switch. */
    fun resetForSignOut() = synchronized(lock) {
        val s = state
        listOf("pushed.json", "mirror.json", "tombstones.json").forEach { File(dir, it).delete() }
        state = KudSyncState(deviceId = s.deviceId, autoBackup = s.autoBackup)
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * Which prefs sync, and the JSON they travel as. The JSON mirrors HeliBoard's backup prefs file
 * (`settingsToJsonStream`), grouped by type so every value comes back as the type it was:
 * `{"boolean":{…},"int":{…},"long":{…},"float":{…},"string":{…},"stringSet":{"k":["a","b"]}}`.
 */
object KudSettingsSync {
    /** Per-device sizes and positions, device state and bookkeeping: these never leave the phone. */
    private val EXCLUDED_PREFIXES = listOf(
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
        Settings.PREF_SIDE_PADDING_SCALE_PREFIX, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX,
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_ONE_HANDED_MODE_PREFIX,
        Settings.PREF_ONE_HANDED_GRAVITY_PREFIX, Settings.PREF_ONE_HANDED_SCALE_PREFIX,
        Settings.PREF_FLOATING_HEIGHT_PREFIX, Settings.PREF_FLOATING_WIDTH_PREFIX, Settings.PREF_FLOATING_ENABLED_PREFIX,
        Settings.PREF_FLOATING_POS_X_PREFIX, Settings.PREF_FLOATING_POS_Y_PREFIX,
        Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "last_shown_emoji_category", "kudkeys_sync",
    )
    private val EXCLUDED_KEYS = setOf(
        Settings.PREF_VERSION_CODE, Settings.PREF_RECENT_EMOJIS, Settings.PREF_EMOJI_MAX_SDK,
    )

    /** Secrets and account state, by name, wherever they are stored: never synced, never in a backup. */
    private val SECRET = Regex("token|secret|password|session|account|draft|server_url|api_key", RegexOption.IGNORE_CASE)

    fun isSecret(key: String) = SECRET.containsMatchIn(key)

    fun isSynced(key: String): Boolean =
        !isSecret(key) && key !in EXCLUDED_KEYS && EXCLUDED_PREFIXES.none { key.startsWith(it) }

    /** The prefs that sync, typed as SharedPreferences returns them. */
    fun export(prefs: SharedPreferences): Map<String, Any> =
        prefs.all.filter { (k, v) -> k != null && v != null && isSynced(k) }.mapValues { it.value!! }.toSortedMap()

    fun toJson(map: Map<String, Any>): JsonObject = buildJsonObject {
        fun group(name: String, pick: (Any) -> Boolean, put: kotlinx.serialization.json.JsonObjectBuilder.(String, Any) -> Unit) =
            putJsonObject(name) { map.filter { pick(it.value) }.forEach { (k, v) -> put(k, v) } }
        group("boolean", { it is Boolean }) { k, v -> put(k, v as Boolean) }
        group("int", { it is Int }) { k, v -> put(k, v as Int) }
        group("long", { it is Long }) { k, v -> put(k, v as Long) }
        group("float", { it is Float }) { k, v -> put(k, v as Float) }
        group("string", { it is String }) { k, v -> put(k, v as String) }
        putJsonObject("stringSet") {
            map.filter { it.value is Set<*> }.forEach { (k, v) ->
                putJsonArray(k) { (v as Set<*>).filterIsInstance<String>().sorted().forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private val GROUPS = setOf("boolean", "int", "long", "float", "string", "stringSet")

    /**
     * Back from JSON. Reads the grouped shape above; a flat `{key: value}` is read too, typed like the pref it
     * would replace in [current] (else from the JSON value). Keys that don't sync are dropped.
     */
    fun fromJson(o: JsonObject, current: Map<String, Any?> = emptyMap()): Map<String, Any> {
        val out = sortedMapOf<String, Any>()
        val grouped = o.isNotEmpty() && o.keys.all { it in GROUPS } && o.values.all { it is JsonObject }
        if (grouped) {
            // A key in two groups (its type changed in an app update, and the old copy is still on the server):
            // the group that matches the pref's type here wins, so a String never lands in an int pref.
            val typeOf = { v: Any? -> when (v) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"; is Set<*> -> "stringSet"; else -> null } }
            val seen = mutableMapOf<String, String>()
            for ((group, values) in o) for ((k, e) in values as JsonObject) {
                val p = e as? JsonPrimitive
                val v: Any? = when (group) {
                    "boolean" -> p?.booleanOrNull
                    "int" -> p?.intOrNull
                    "long" -> p?.longOrNull
                    "float" -> p?.doubleOrNull?.toFloat()
                    "string" -> p?.takeIf { it.isString }?.contentOrNull
                    else -> (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                }
                if (v == null) continue
                val prev = seen[k]
                if (prev != null && prev == typeOf(current[k])) continue // already the right type
                seen[k] = group
                out[k] = v
            }
        } else {
            for ((k, e) in o) inferred(e, current[k])?.let { out[k] = it }
        }
        return out.filterKeys(::isSynced)
    }

    private fun inferred(e: JsonElement, like: Any?): Any? {
        if (e is JsonArray) return e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val p = e as? JsonPrimitive ?: return null
        return when (like) {
            is Boolean -> p.booleanOrNull
            is Int -> p.intOrNull
            is Long -> p.longOrNull
            is Float -> p.doubleOrNull?.toFloat()
            is String -> p.contentOrNull
            else -> when {
                p.isString -> p.contentOrNull
                p.booleanOrNull != null -> p.booleanOrNull
                p.intOrNull != null -> p.intOrNull
                p.longOrNull != null -> p.longOrNull
                else -> p.doubleOrNull?.toFloat()
            }
        }
    }

    /** [o] with only synced keys and no empty groups (grouped like [toJson]; a flat legacy key is kept as is). */
    fun syncedOnly(o: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in o) {
            if (v is JsonObject && k in GROUPS) {
                val kept = v.filterKeys(::isSynced)
                if (kept.isNotEmpty()) put(k, JsonObject(kept))
            } else if (isSynced(k)) put(k, v)
        }
    }

    /**
     * What to send so the server ends up holding [local]: every key whose value differs from [base] (what the
     * server held after the last sync), and `null` for every key of [base] that [local] no longer has (a setting
     * reset to default, a deleted layout or colour, a key whose type changed). Grouped like [toJson].
     */
    fun diff(base: JsonObject, local: JsonObject): JsonObject {
        val b = paths(base)
        val l = paths(local)
        val out = mutableMapOf<String, MutableMap<String, JsonElement>>()
        for ((p, v) in l) if (!same(b[p], v)) out.getOrPut(p.first) { mutableMapOf() }[p.second] = v
        for (p in b.keys - l.keys) out.getOrPut(p.first) { mutableMapOf() }[p.second] = JsonNull
        return buildJsonObject {
            out[""]?.forEach { (k, v) -> put(k, v) }
            for ((group, values) in out) if (group != "") put(group, JsonObject(values))
        }
    }

    /** Equal as settings: numbers by value (the server writes 1.0 back as 1). */
    private fun same(a: JsonElement?, b: JsonElement): Boolean {
        if (a == b) return true
        if (a !is JsonPrimitive || b !is JsonPrimitive || a.isString || b.isString) return false
        val x = a.doubleOrNull ?: return false
        val y = b.doubleOrNull ?: return false
        return x == y
    }

    /** (group, key) → value; a flat legacy document's keys sit in group "". */
    private fun paths(o: JsonObject): Map<Pair<String, String>, JsonElement> = buildMap {
        for ((k, v) in o) {
            if (v is JsonObject && k in GROUPS) v.forEach { (key, value) -> put(k to key, value) }
            else put("" to k, v)
        }
    }

    /** The values in [incoming] that differ from [prefs] (only those get written). */
    fun changes(prefs: SharedPreferences, incoming: Map<String, Any>): Map<String, Any> {
        val now = prefs.all
        return incoming.filter { (k, v) -> isSynced(k) && now[k] != v }
    }

    fun hash(map: Map<String, Any>): String {
        val text = toJson(map).toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

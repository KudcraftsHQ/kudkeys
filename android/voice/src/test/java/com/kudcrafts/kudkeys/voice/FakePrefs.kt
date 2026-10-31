package com.kudcrafts.kudkeys.voice

import android.content.SharedPreferences

/** In-memory SharedPreferences for plain JVM tests. */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val ops = ArrayList<(MutableMap<String, Any?>) -> Unit>()
        override fun putString(k: String, v: String?) = apply { ops += { it[k] = v } }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { ops += { it[k] = v } }
        override fun putInt(k: String, v: Int) = apply { ops += { it[k] = v } }
        override fun putLong(k: String, v: Long) = apply { ops += { it[k] = v } }
        override fun putFloat(k: String, v: Float) = apply { ops += { it[k] = v } }
        override fun putBoolean(k: String, v: Boolean) = apply { ops += { it[k] = v } }
        override fun remove(k: String) = apply { ops += { it.remove(k) } }
        override fun clear() = apply { ops += { it.clear() } }
        override fun commit(): Boolean { ops.forEach { it(map) }; return true }
        override fun apply() { commit() }
    }
}

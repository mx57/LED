package com.glowled.app.util

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            temp[key] = value; removed.remove(key); return this
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            temp[key] = values; removed.remove(key); return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            temp[key] = value; removed.remove(key); return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            temp[key] = value; removed.remove(key); return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            temp[key] = value; removed.remove(key); return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            temp[key] = value; removed.remove(key); return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            removed.add(key); temp.remove(key); return this
        }
        override fun clear(): SharedPreferences.Editor {
            removed.addAll(map.keys); temp.clear(); return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            removed.forEach { map.remove(it) }
            temp.forEach { (k, v) -> map[k] = v }
            temp.clear()
            removed.clear()
        }
    }

    private class FakeSharedPreferences(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun serializesPreferencesExcludingSkipKeys() {
        val sp = FakeSharedPreferences(mutableMapOf(
            "enabled" to true,
            "speed" to 5,
            "thickness" to 4,
            "app_theme" to "dark",
            "aod_window_blocked" to true, // should be skipped
            "transfer_apps" to "org.telegram.messenger", // should be skipped
            "update_latest" to "2.12.0", // should be skipped
        ))

        val json = SettingsBackup.serialize(sp, "2.11.0")
        assertEquals("glowled", json.getString("_app"))
        assertEquals(2, json.getInt("_version"))
        assertEquals("2.11.0", json.getString("appVersion"))
        assertTrue(json.has("exportedAt"))

        val prefs = json.getJSONObject("prefs")
        assertTrue(prefs.has("enabled"))
        assertTrue(prefs.has("speed"))
        assertTrue(prefs.has("thickness"))
        assertTrue(prefs.has("app_theme"))

        assertFalse(prefs.has("aod_window_blocked"))
        assertFalse(prefs.has("transfer_apps"))
        assertFalse(prefs.has("update_latest"))
    }

    @Test
    fun restoresValidPreferences() {
        val sp = FakeSharedPreferences()
        val jsonText = """
            {
                "_app": "glowled",
                "_version": 2,
                "prefs": {
                    "enabled": { "t": "b", "v": true },
                    "speed": { "t": "i", "v": 7 },
                    "app_theme": { "t": "s", "v": "light" }
                }
            }
        """.trimIndent()

        val count = SettingsBackup.restoreFromJson(sp, jsonText)
        assertEquals(3, count)
        assertEquals(true, sp.getBoolean("enabled", false))
        assertEquals(7, sp.getInt("speed", 0))
        assertEquals("light", sp.getString("app_theme", ""))
    }

    @Test
    fun ignoresSkipKeysDuringRestore() {
        val sp = FakeSharedPreferences()
        val jsonText = """
            {
                "_app": "glowled",
                "_version": 2,
                "prefs": {
                    "enabled": { "t": "b", "v": true },
                    "aod_window_blocked": { "t": "b", "v": true },
                    "update_latest": { "t": "s", "v": "9.9.9" }
                }
            }
        """.trimIndent()

        val count = SettingsBackup.restoreFromJson(sp, jsonText)
        assertEquals(1, count)
        assertEquals(true, sp.getBoolean("enabled", false))
        assertFalse(sp.contains("aod_window_blocked"))
        assertFalse(sp.contains("update_latest"))
    }

    @Test
    fun handlesCorruptedAndUnknownEntriesGracefully() {
        val sp = FakeSharedPreferences()
        val jsonText = """
            {
                "_app": "glowled",
                "_version": 2,
                "prefs": {
                    "valid_key": { "t": "i", "v": 42 },
                    "unknown_type": { "t": "unknown", "v": 100 },
                    "missing_value": { "t": "s" },
                    "corrupted_object": "not a json object"
                }
            }
        """.trimIndent()

        val count = SettingsBackup.restoreFromJson(sp, jsonText)
        assertEquals(1, count)
        assertEquals(42, sp.getInt("valid_key", 0))
    }

    @Test
    fun rejectsInvalidOrForeignJson() {
        val sp = FakeSharedPreferences()
        // Non-glowled json
        assertEquals(-1, SettingsBackup.restoreFromJson(sp, """{"_app": "otherapp"}"""))
        // Empty text
        assertEquals(-1, SettingsBackup.restoreFromJson(sp, ""))
        // Broken JSON
        assertEquals(-1, SettingsBackup.restoreFromJson(sp, "{ broken json"))
    }
}

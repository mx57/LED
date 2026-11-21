package com.glowled.app.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/**
 * Saves/restores every Glow LED setting (including per-app colours and the camera position) to a
 * JSON file the user picks, so an uninstall/reinstall never means setting everything up again.
 */
object SettingsBackup {

    // Device-specific runtime state that shouldn't travel to a new install.
    val skip = setOf(
        "aod_window_blocked", "transfer_apps",
        "update_latest", "update_notes", "update_checked", "update_notified",
    )

    fun serialize(sp: SharedPreferences, appVersion: String = "2.11.0"): JSONObject {
        val json = JSONObject()
        json.put("_app", "glowled")
        json.put("_version", 2)
        json.put("schemaVersion", 2)
        json.put("appVersion", appVersion)
        json.put("exportedAt", System.currentTimeMillis())
        val values = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in skip || v == null) return@forEach
            val t = when (v) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is String -> "s"
                else -> return@forEach
            }
            values.put(k, JSONObject().put("t", t).put("v", v))
        }
        json.put("prefs", values)
        return json
    }

    fun write(context: Context, sp: SharedPreferences, uri: Uri): Boolean = try {
        val json = serialize(sp)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
        true
    } catch (_: Exception) {
        false
    }

    /** Returns the number of settings restored, or -1 if the file isn't a Glow LED backup. */
    fun read(context: Context, sp: SharedPreferences, uri: Uri): Int = try {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
        restoreFromJson(sp, text)
    } catch (_: Exception) {
        -1
    }

    fun restoreFromJson(sp: SharedPreferences, text: String): Int = try {
        if (text.isBlank()) -1
        else {
            val json = JSONObject(text)
            if (json.optString("_app") == "glowled") {
                val prefsObj = json.optJSONObject("prefs") ?: JSONObject()
                restore(sp, prefsObj)
            } else -1
        }
    } catch (_: Exception) {
        -1
    }

    fun restore(sp: SharedPreferences, values: JSONObject): Int {
        val e = sp.edit()
        var n = 0
        values.keys().forEach { k ->
            if (k in skip) return@forEach
            val o = values.optJSONObject(k) ?: return@forEach
            val t = o.optString("t")
            if (!o.has("v")) return@forEach
            try {
                when (t) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    else -> return@forEach
                }
                n++
            } catch (_: Exception) {
                // Ignore individual malformed entry without failing the whole restore
            }
        }
        e.apply()
        return n
    }
}

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

    data class BackupSummary(
        val schemaVersion: Int,
        val appVersion: String,
        val exportedAt: Long,
        val validSettingsCount: Int,
        val appRulesCount: Int,
        val skippedKeysCount: Int,
        val isValid: Boolean,
    )

    fun inspect(text: String): BackupSummary {
        if (text.isBlank()) return BackupSummary(0, "", 0L, 0, 0, 0, false)
        return try {
            val json = JSONObject(text)
            inspect(json)
        } catch (_: Exception) {
            BackupSummary(0, "", 0L, 0, 0, 0, false)
        }
    }

    fun inspect(json: JSONObject): BackupSummary {
        if (json.optString("_app") != "glowled") {
            return BackupSummary(0, "", 0L, 0, 0, 0, false)
        }
        val schemaVer = if (json.has("schemaVersion")) json.getInt("schemaVersion") else json.optInt("_version", 1)
        val appVer = json.optString("appVersion", "unknown")
        val exportedAt = json.optLong("exportedAt", 0L)
        val prefsObj = json.optJSONObject("prefs") ?: JSONObject()

        var validSettings = 0
        var appRules = 0
        var skipped = 0

        prefsObj.keys().forEach { k ->
            if (k in skip) {
                skipped++
            } else {
                val o = prefsObj.optJSONObject(k)
                if (o != null && o.has("v")) {
                    validSettings++
                    if (k.startsWith("app_") || k.startsWith("color_")) {
                        appRules++
                    }
                } else {
                    skipped++
                }
            }
        }

        return BackupSummary(
            schemaVersion = schemaVer,
            appVersion = appVer,
            exportedAt = exportedAt,
            validSettingsCount = validSettings,
            appRulesCount = appRules,
            skippedKeysCount = skipped,
            isValid = true,
        )
    }

    fun migrate(json: JSONObject, fromVersion: Int): JSONObject {
        if (fromVersion < 2) {
            json.put("schemaVersion", 2)
        }
        return json
    }

    fun restoreFromJson(sp: SharedPreferences, text: String): Int = try {
        if (text.isBlank()) -1
        else {
            val json = JSONObject(text)
            if (json.optString("_app") == "glowled") {
                val version = if (json.has("schemaVersion")) json.getInt("schemaVersion") else json.optInt("_version", 1)
                val migrated = if (version < 2) migrate(json, version) else json
                val prefsObj = migrated.optJSONObject("prefs") ?: JSONObject()
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


package com.sakura.player.data

import android.content.Context
import android.content.SharedPreferences

object SettingsPrefs {
    private const val NAME = "sakura_prefs"
    private lateinit var prefs: SharedPreferences
    private var defaultPath = "/storage/emulated/0/SakuraAnime"

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        defaultPath = "/storage/emulated/0/SakuraAnime"
    }

    var downloadPath: String
        get() {
            val saved = prefs.getString("download_path", "")
            return if (saved.isNullOrEmpty()) defaultPath else saved
        }
        set(v) = prefs.edit().putString("download_path", v).apply()

    var downloadUri: String
        get() = prefs.getString("download_uri", "") ?: ""
        set(v) = prefs.edit().putString("download_uri", v).apply()

    var activeDomain: String
        get() = prefs.getString("active_domain", "") ?: ""
        set(v) = prefs.edit().putString("active_domain", v).apply()

    var lastDomainCheck: Long
        get() = prefs.getLong("last_domain_check", 0)
        set(v) = prefs.edit().putLong("last_domain_check", v).apply()

    var followLastCheck: Long
        get() = prefs.getLong("follow_last_check", 0)
        set(v) = prefs.edit().putLong("follow_last_check", v).apply()

    /**
     * Whether we have already shown the battery-optimization-exemption prompt for
     * background downloads. Persisted so we don't nag the user on every download;
     * once dismissed (or once the user grants the exemption) it stays true.
     */
    var hasPromptedBatteryOptimization: Boolean
        get() = prefs.getBoolean("has_prompted_battery_optimization", false)
        set(v) = prefs.edit().putBoolean("has_prompted_battery_optimization", v).apply()

    fun getString(key: String, def: String = ""): String = prefs.getString(key, def) ?: def
    fun setString(key: String, v: String) = prefs.edit().putString(key, v).apply()
}

package com.clashtimers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences-backed state shared by the app screen and the widget. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("clash_timers", Context.MODE_PRIVATE)

    var playerName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    var showBuilderBase: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BB, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_BB, value).apply()

    /** The last export as copied from the game; re-parsed when potions change. */
    var rawExport: String?
        get() = prefs.getString(KEY_RAW, null)
        set(value) = prefs.edit().putString(KEY_RAW, value).apply()

    var boosts: List<Boost>
        get() = prefs.getString(KEY_BOOSTS, null)
            ?.let { runCatching { JSONArray(it).mapObjects(Boost::fromJson) }.getOrNull() }
            ?: emptyList()
        set(value) = prefs.edit().putString(KEY_BOOSTS, JSONArray(value.map { it.toJson() }).toString()).apply()

    var village: Village?
        get() = prefs.getString(KEY_VILLAGE, null)
            ?.let { runCatching { Village.fromJson(JSONObject(it)) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_VILLAGE, value?.toJson()?.toString()).apply()

    private companion object {
        const val DEFAULT_NAME = "Chief"
        const val KEY_NAME = "player_name"
        const val KEY_SHOW_BB = "show_builder_base"
        const val KEY_RAW = "raw_export"
        const val KEY_BOOSTS = "boosts"
        const val KEY_VILLAGE = "village"
    }
}

package com.clashtimers

import android.content.Context
import org.json.JSONObject

/**
 * Names and per-level upgrade durations keyed by the export's `data` id.
 * Built from ClashKingAssets (see assets/gamedata.json, field "v" is the snapshot date).
 */
class GameData private constructor(private val items: JSONObject) {

    fun name(id: Int): String? =
        items.optJSONObject(id.toString())?.optString("n")?.takeIf { it.isNotEmpty() }

    /** Seconds for the [fromLevel] -> fromLevel + 1 step, or 0 when unknown. */
    fun stepSeconds(id: Int, fromLevel: Int): Long =
        items.optJSONObject(id.toString())?.optJSONObject("s")?.optLong(fromLevel.toString()) ?: 0L

    companion object {
        @Volatile
        private var instance: GameData? = null

        fun get(context: Context): GameData = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private fun load(context: Context): GameData {
            val text = context.assets.open("gamedata.json").bufferedReader().use { it.readText() }
            return GameData(JSONObject(text).getJSONObject("items"))
        }
    }
}

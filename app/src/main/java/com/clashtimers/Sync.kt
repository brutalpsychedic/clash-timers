package com.clashtimers

import android.content.ClipboardManager
import android.content.Context

object Sync {
    sealed interface Result
    data class Imported(val village: Village) : Result
    /** Clipboard holds the export we already have (or an older one). */
    data object NotNewer : Result
    data object NoExport : Result
    data class Failed(val message: String) : Result

    /** @param force re-import even when the export isn't newer than the stored one. */
    fun importText(context: Context, text: String?, force: Boolean): Result {
        if (text == null || !ExportParser.looksLikeExport(text)) return NoExport
        val store = Store(context)
        val previous = store.village
        val village = try {
            ExportParser.parse(text, GameData.get(context), store.boosts, previous)
        } catch (e: Exception) {
            return Failed(e.message ?: context.getString(R.string.export_unreadable))
        }
        if (!force && previous != null && previous.tag == village.tag && village.exportedAt <= previous.exportedAt) {
            return NotNewer
        }
        store.rawExport = text
        store.boosts = village.boosts
        store.village = village
        TimersWidget.refreshAll(context)
        return Imported(village)
    }

    /** Logs a 1-hour potion starting now; drinking another while one runs extends it. */
    fun logPotion(context: Context, group: Group, multiplier: Int): Boolean {
        val store = Store(context)
        val raw = store.rawExport ?: return false
        val now = System.currentTimeMillis()
        val current = store.boosts
        val active = current.firstOrNull { it.group == group && it.endsAt > now }
        val updated = active?.copy(endsAt = active.endsAt + HOUR) ?: Boost(group, multiplier, now, now + HOUR)
        store.boosts = current.filter { it != active } + updated
        rebuild(context, store, raw)
        return true
    }

    fun clearPotions(context: Context) {
        val store = Store(context)
        store.boosts = emptyList()
        store.rawExport?.let { rebuild(context, store, it) }
    }

    private fun rebuild(context: Context, store: Store, raw: String) {
        val village = runCatching {
            ExportParser.parse(raw, GameData.get(context), store.boosts, store.village)
        }.getOrNull() ?: return
        store.village = village
        TimersWidget.refreshAll(context)
    }
}

object Clipboard {
    /** Only works while one of our activities has window focus (Android 10+). */
    fun text(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}

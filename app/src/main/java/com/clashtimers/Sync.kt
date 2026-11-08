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
        // Keep only what was logged in the app; the export's own boosts come back on every parse.
        store.boosts = store.boosts.filter { it.endsAt > village.exportedAt }
        store.village = village
        TimersWidget.refreshAll(context)
        return Imported(village)
    }

    /**
     * Logs a boost starting now. Drinking another potion while one runs extends it, so the
     * new window starts where the running one (from the export or the app) ends.
     */
    fun logPotion(context: Context, group: Group, multiplier: Int, duration: Long = HOUR): Boolean {
        val store = Store(context)
        val raw = store.rawExport ?: return false
        val now = System.currentTimeMillis()
        val runningUntil = store.village?.boostedUntil(group, now) ?: 0L
        val start = maxOf(now, runningUntil)
        store.boosts = store.boosts + Boost(group, multiplier, start, start + duration)
        rebuild(context, store, raw)
        return true
    }

    fun clearPotions(context: Context) {
        val store = Store(context)
        store.boosts = emptyList()
        store.rawExport?.let { rebuild(context, store, it) }
    }

    /**
     * A tap on a Daily row. Ready (or [force], from the app's buttons): mark it used now and
     * return the toast text. Otherwise return null, and the widget opens the game like any other row.
     */
    fun useDaily(context: Context, kind: DailyKind, force: Boolean = false): String? {
        val store = Store(context)
        val now = System.currentTimeMillis()
        val daily = Dailies.from(store).firstOrNull { it.kind == kind } ?: return null
        if (!force && daily.state(now) != Daily.State.READY) return null
        when (kind) {
            DailyKind.HOME_STAR -> store.homeStarAt = now
            DailyKind.BUILDER_STAR -> store.builderStarAt = now
            DailyKind.CLOCK_TOWER -> {
                val tower = store.village?.clockTower ?: return null
                logPotion(context, Group.BUILDER_BASE, ClockTower.MULTIPLIER, tower.boostMillis)
                return context.getString(R.string.clock_tower_started, (tower.boostMillis / MINUTE).toInt())
            }
        }
        TimersWidget.refreshAll(context)
        return context.getString(R.string.star_collected, context.getString(kind.label), (kind.cooldown / HOUR).toInt())
    }

    /** Forget Star Bonus taps and Clock Tower boosts logged in the app. */
    fun resetDailies(context: Context) {
        val store = Store(context)
        store.homeStarAt = 0L
        store.builderStarAt = 0L
        store.boosts = store.boosts.filter { it.group != Group.BUILDER_BASE }
        val raw = store.rawExport
        if (raw != null) rebuild(context, store, raw) else TimersWidget.refreshAll(context)
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

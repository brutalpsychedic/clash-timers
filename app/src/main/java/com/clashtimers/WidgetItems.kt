package com.clashtimers

import android.content.res.Resources

/** Flat list behind the widget's ListView: section headers, dailies, helpers, idle warnings and upgrade rows. */
sealed interface WidgetItem {
    /** [boostUntil] > now shows a "×[boostMultiplier] mm:ss" pill. */
    data class Header(val title: String, val goblin: Boolean, val boostMultiplier: Int = 0, val boostUntil: Long = 0L) : WidgetItem
    data class DailyRow(val daily: Daily) : WidgetItem
    data class HelperRow(val helper: Helper) : WidgetItem
    data class Row(val upgrade: Upgrade) : WidgetItem
    data class Idle(val text: String) : WidgetItem
}

object WidgetItems {
    fun build(
        res: Resources,
        village: Village?,
        showBuilderBase: Boolean,
        now: Long,
        dailies: List<Daily> = emptyList(),
    ): List<WidgetItem> {
        val v = village ?: return emptyList()
        val out = ArrayList<WidgetItem>()
        if (dailies.isNotEmpty()) {
            out += WidgetItem.Header(res.getString(R.string.section_daily), goblin = false)
            dailies.mapTo(out) { WidgetItem.DailyRow(it) }
        }
        for (group in Group.entries) {
            val rows = v.upgrades.filter { it.kind.group == group }.sortedBy { it.finishAt }
            val running = rows.filter { it.finishAt > now }
            val goblin = rows.any { it.goblin }
            val multiplier = v.boosts.firstOrNull { it.group == group && it.isActive(now) }?.multiplier ?: 0
            val until = v.boostedUntil(group, now)
            fun header(title: String) = WidgetItem.Header(title, goblin, multiplier, until)

            // Goblin helpers don't occupy one of your own builders / the lab.
            val idle: String? = when (group) {
                Group.BUILDERS -> {
                    val busy = running.count { !it.goblin }
                    out += header(res.getString(R.string.section_builders, busy, v.homeBuilders))
                    val free = v.homeBuilders - busy
                    if (free > 0) res.getQuantityString(R.plurals.builders_idle, free, free) else null
                }
                Group.LAB -> {
                    if (!v.hasLab && rows.isEmpty()) continue
                    out += header(res.getString(R.string.section_lab))
                    if (running.none { !it.goblin }) res.getString(R.string.lab_idle) else null
                }
                Group.PETS -> {
                    if (!v.hasPetHouse && rows.isEmpty()) continue
                    out += header(res.getString(R.string.section_pets))
                    if (running.isEmpty()) res.getString(R.string.pets_idle) else null
                }
                Group.BUILDER_BASE -> {
                    if (!showBuilderBase || !v.hasBuilderBase) continue
                    out += header(res.getString(R.string.section_builder_base))
                    if (running.isEmpty()) res.getString(R.string.builder_base_idle) else null
                }
            }
            v.helpers.filter { it.group == group }.mapTo(out) { WidgetItem.HelperRow(it) }
            if (idle != null) out += WidgetItem.Idle(idle)
            rows.mapTo(out) { WidgetItem.Row(it) }
        }
        return out
    }
}

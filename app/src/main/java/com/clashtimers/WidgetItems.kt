package com.clashtimers

import android.content.res.Resources

/** Flat list behind the widget's ListView: section headers, helpers, idle warnings and upgrade rows. */
sealed interface WidgetItem {
    data class Header(val title: String, val goblin: Boolean, val boost: Boost?) : WidgetItem
    data class HelperRow(val helper: Helper) : WidgetItem
    data class Row(val upgrade: Upgrade) : WidgetItem
    data class Idle(val text: String) : WidgetItem
}

object WidgetItems {
    fun build(res: Resources, village: Village?, showBuilderBase: Boolean, now: Long): List<WidgetItem> {
        val v = village ?: return emptyList()
        val out = ArrayList<WidgetItem>()
        for (group in Group.entries) {
            val rows = v.upgrades.filter { it.kind.group == group }.sortedBy { it.finishAt }
            val running = rows.filter { it.finishAt > now }
            val goblin = rows.any { it.goblin }
            val boost = v.boosts.firstOrNull { it.group == group && it.isActive(now) }

            // Goblin helpers don't occupy one of your own builders / the lab.
            val idle: String? = when (group) {
                Group.BUILDERS -> {
                    val busy = running.count { !it.goblin }
                    out += WidgetItem.Header(res.getString(R.string.section_builders, busy, v.homeBuilders), goblin, boost)
                    val free = v.homeBuilders - busy
                    if (free > 0) res.getQuantityString(R.plurals.builders_idle, free, free) else null
                }
                Group.LAB -> {
                    if (!v.hasLab && rows.isEmpty()) continue
                    out += WidgetItem.Header(res.getString(R.string.section_lab), goblin, boost)
                    if (running.none { !it.goblin }) res.getString(R.string.lab_idle) else null
                }
                Group.PETS -> {
                    if (!v.hasPetHouse && rows.isEmpty()) continue
                    out += WidgetItem.Header(res.getString(R.string.section_pets), goblin, boost)
                    if (running.isEmpty()) res.getString(R.string.pets_idle) else null
                }
                Group.BUILDER_BASE -> {
                    if (!showBuilderBase || !v.hasBuilderBase) continue
                    out += WidgetItem.Header(res.getString(R.string.section_builder_base), goblin, boost)
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

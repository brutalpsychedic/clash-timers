package com.clashtimers

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.util.Date

const val MINUTE = 60_000L
const val HOUR = 3_600_000L
const val DAY = 86_400_000L

object Time {
    /** "2d 14h": used for long timers when the widget can't refresh exactly on the day rollover. */
    fun daysHours(ms: Long): String = "${ms / DAY}d ${(ms % DAY) / HOUR}h"

    /** "Exported 13:44", "Exported Mon 13:44", plus " · 2 done" once upgrades have finished. */
    fun exportedLabel(context: Context, village: Village, now: Long): String {
        val at = village.exportedAt
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        val whenText = when {
            DateUtils.isToday(at) -> time
            now - at < 6 * DAY -> "${DateFormat.format("EEE", at)} $time"
            else -> DateFormat.getMediumDateFormat(context).format(Date(at))
        }
        val label = context.getString(R.string.exported, whenText)
        val done = village.upgrades.count { it.finishAt <= now }
        return if (done > 0) context.getString(R.string.exported_with_done, label, done) else label
    }
}

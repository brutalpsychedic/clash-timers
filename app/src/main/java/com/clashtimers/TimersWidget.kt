package com.clashtimers

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

class TimersWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) =
        refreshAll(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        refreshAll(context)

    override fun onDisabled(context: Context) = Alarms.cancel(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED, // Chronometer bases are relative to boot time
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.clashtimers.REFRESH"

        @Suppress("DEPRECATION") // notifyAppWidgetViewDataChanged pairs with setRemoteAdapter(Intent)
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimersWidget::class.java))
            if (ids.isEmpty()) {
                Alarms.cancel(context)
                return
            }
            val store = Store(context)
            val village = store.village
            val now = System.currentTimeMillis()
            for (id in ids) manager.updateAppWidget(id, rootViews(context, store, village, id, now))
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
            Alarms.scheduleNext(context, village, now)
        }

        @Suppress("DEPRECATION") // setRemoteAdapter(Intent) keeps API 26-30 support
        internal fun rootViews(context: Context, store: Store, village: Village?, widgetId: Int, now: Long): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_root)
            rv.setTextViewText(R.id.player_name, store.playerName)
            rv.setTextViewText(R.id.th_level, village?.let { context.getString(R.string.th_level, it.townHall) } ?: "")
            rv.setTextViewText(R.id.footer, village?.let { Time.exportedLabel(context, it, now) } ?: "")
            rv.setOnClickPendingIntent(R.id.sync_button, SyncActivity.syncIntent(context))
            rv.setOnClickPendingIntent(
                R.id.title_area,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )

            val service = Intent(context, TimersWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            rv.setRemoteAdapter(R.id.list, service)
            rv.setEmptyView(R.id.list, R.id.empty)
            rv.setPendingIntentTemplate(R.id.list, SyncActivity.openGameTemplate(context))
            return rv
        }
    }
}

/** Wakes the widget exactly when a row's text has to change; progress bars ride updatePeriodMillis. */
object Alarms {
    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun scheduleNext(context: Context, village: Village?, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = refreshIntent(context)
        alarms.cancel(pending)
        val exact = canExact(context)
        val at = village?.let { nextChange(it, now, exact) } ?: return
        // RTC (not _WAKEUP): nobody sees the widget while the screen is off.
        if (exact) alarms.setExact(AlarmManager.RTC, at + 500, pending)
        else alarms.setWindow(AlarmManager.RTC, at + 500, MINUTE, pending)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    /** Next upgrade finish, day-digit rollover, hour change (inexact mode) or boost edge. */
    private fun nextChange(village: Village, now: Long, exact: Boolean): Long? {
        var next = Long.MAX_VALUE
        for (u in village.upgrades) {
            val left = u.finishAt - now
            if (left <= 0) continue
            val days = left / DAY
            next = minOf(
                next,
                when {
                    days == 0L -> u.finishAt
                    exact -> u.finishAt - days * DAY
                    else -> now + left % HOUR
                },
            )
        }
        for (b in village.boosts) {
            if (b.startAt > now) next = minOf(next, b.startAt)
            if (b.endsAt > now) next = minOf(next, b.endsAt)
        }
        return next.takeIf { it != Long.MAX_VALUE }
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimersWidget::class.java).setAction(TimersWidget.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

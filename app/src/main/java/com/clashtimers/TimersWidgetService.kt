package com.clashtimers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class TimersWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TimersFactory(applicationContext)
}

internal class TimersFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<WidgetItem> = emptyList()
    private var exact = true
    private val icons = IconCache(context)

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val store = Store(context)
        items = WidgetItems.build(context.resources, store.village, store.showBuilderBase, System.currentTimeMillis())
        exact = Alarms.canExact(context)
    }

    override fun onDestroy() {}

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val now = System.currentTimeMillis()
        return when (val item = items.getOrNull(position)) {
            is WidgetItem.Header -> header(item, now)
            is WidgetItem.Row -> row(item.upgrade, now)
            is WidgetItem.Idle -> idle(item.text)
            null -> idle("")
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 3

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = false

    private fun header(item: WidgetItem.Header, now: Long): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item_header)
        val title = SpannableStringBuilder(item.title)
        if (item.goblin) {
            val start = title.length
            title.append(context.getString(R.string.goblin_suffix))
            title.setSpan(ForegroundColorSpan(context.getColor(R.color.goblin_green)), start, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        rv.setTextViewText(R.id.title, title)

        val boost = item.boost
        if (boost != null && boost.endsAt > now) {
            rv.setViewVisibility(R.id.boost, View.VISIBLE)
            rv.setChronometer(R.id.boost, SystemClock.elapsedRealtime() + (boost.endsAt - now), "×${boost.multiplier} %s", true)
            rv.setChronometerCountDown(R.id.boost, true)
        } else {
            rv.setViewVisibility(R.id.boost, View.GONE)
        }
        return rv
    }

    private fun row(u: Upgrade, now: Long): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item_row)
        rv.setProgressBar(R.id.progress, 1000, (u.progress(now) * 1000).toInt(), false)

        val art = icons.get(u.id)
        if (art != null) {
            rv.setImageViewBitmap(R.id.icon, art)
            rv.setInt(R.id.icon, "setBackgroundResource", 0)
        } else {
            rv.setImageViewResource(R.id.icon, Icons.glyph(u.kind))
            rv.setInt(R.id.icon, "setBackgroundResource", Icons.background(u.kind))
        }
        rv.setViewVisibility(R.id.goblin, if (u.goblin) View.VISIBLE else View.GONE)

        val name = SpannableStringBuilder(u.name)
        val start = name.length
        name.append("  → ").append(u.target)
        name.setSpan(RelativeSizeSpan(0.8f), start, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rv.setTextViewText(R.id.name, name)

        val left = u.finishAt - now
        val days = left / DAY
        when {
            left <= 0 -> {
                rv.setImageViewResource(R.id.clock, R.drawable.ic_check)
                showStatic(rv, context.getString(R.string.ready))
            }
            // Chronometer can't count days, so tick the remainder of the current day
            // behind a "Nd" prefix; Alarms re-renders when that remainder hits zero.
            days == 0L || exact -> {
                rv.setImageViewResource(R.id.clock, R.drawable.ic_clock)
                rv.setViewVisibility(R.id.time, View.VISIBLE)
                rv.setViewVisibility(R.id.time_static, View.GONE)
                val format = if (days > 0) "${days}d %s" else null
                rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() + left - days * DAY, format, true)
                rv.setChronometerCountDown(R.id.time, true)
            }
            else -> {
                rv.setImageViewResource(R.id.clock, R.drawable.ic_clock)
                showStatic(rv, Time.daysHours(left))
            }
        }
        rv.setOnClickFillInIntent(R.id.row_root, Intent())
        return rv
    }

    private fun showStatic(rv: RemoteViews, text: String) {
        rv.setViewVisibility(R.id.time, View.GONE)
        rv.setViewVisibility(R.id.time_static, View.VISIBLE)
        rv.setTextViewText(R.id.time_static, text)
    }

    private fun idle(text: String) = RemoteViews(context.packageName, R.layout.widget_item_idle).apply {
        setTextViewText(R.id.idle_text, text)
        setOnClickFillInIntent(R.id.idle_root, Intent())
    }
}

object Icons {
    fun glyph(kind: Kind) = when (kind) {
        Kind.BUILDING, Kind.BB_BUILDING -> R.drawable.ic_building
        Kind.TRAP, Kind.BB_TRAP -> R.drawable.ic_trap
        Kind.HERO, Kind.BB_HERO -> R.drawable.ic_hero
        Kind.GUARDIAN -> R.drawable.ic_shield
        Kind.TROOP, Kind.BB_TROOP -> R.drawable.ic_troop
        Kind.SIEGE -> R.drawable.ic_siege
        Kind.SPELL -> R.drawable.ic_spell
        Kind.PET -> R.drawable.ic_pet
    }

    fun background(kind: Kind) = when (kind) {
        Kind.BUILDING -> R.drawable.icon_bg_building
        Kind.TRAP -> R.drawable.icon_bg_trap
        Kind.HERO -> R.drawable.icon_bg_hero
        Kind.GUARDIAN -> R.drawable.icon_bg_guardian
        Kind.TROOP, Kind.SIEGE -> R.drawable.icon_bg_lab
        Kind.SPELL -> R.drawable.icon_bg_spell
        Kind.PET -> R.drawable.icon_bg_pet
        Kind.BB_BUILDING, Kind.BB_TRAP, Kind.BB_HERO, Kind.BB_TROOP -> R.drawable.icon_bg_bb
    }
}

/** Optional real artwork: app/src/main/assets/icons/<dataId>.png (or .webp) overrides the glyph. */
private class IconCache(private val context: Context) {
    private val available: Set<String> by lazy {
        runCatching { context.assets.list("icons")?.toSet() }.getOrNull() ?: emptySet()
    }
    private val cache = HashMap<Int, Bitmap?>()

    fun get(id: Int): Bitmap? {
        if (cache.containsKey(id)) return cache[id]
        val file = listOf("$id.png", "$id.webp").firstOrNull { it in available }
        val bitmap = file?.let { f ->
            runCatching { context.assets.open("icons/$f").use(BitmapFactory::decodeStream) }.getOrNull()
        }?.let(::shrink)
        cache[id] = bitmap
        return bitmap
    }

    // Every row's bitmap is parceled to the launcher, so keep them small.
    private fun shrink(b: Bitmap): Bitmap {
        val max = 96
        if (b.width <= max && b.height <= max) return b
        val scale = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
    }
}

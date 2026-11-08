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
    private val glyphPadding = (7 * context.resources.displayMetrics.density).toInt()
    private val starPadding = (3 * context.resources.displayMetrics.density).toInt()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val store = Store(context)
        items = WidgetItems.build(
            context.resources, store.village, store.showBuilderBase, System.currentTimeMillis(), Dailies.from(store),
        )
        exact = Alarms.canExact(context)
    }

    override fun onDestroy() {}

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val now = System.currentTimeMillis()
        return when (val item = items.getOrNull(position)) {
            is WidgetItem.Header -> header(item, now)
            is WidgetItem.DailyRow -> daily(item.daily, now)
            is WidgetItem.HelperRow -> helper(item.helper, now)
            is WidgetItem.Row -> row(item.upgrade, now)
            is WidgetItem.Idle -> idle(item.text)
            null -> idle("")
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 5

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

        if (item.boostUntil > now) {
            rv.setViewVisibility(R.id.boost, View.VISIBLE)
            rv.setChronometer(R.id.boost, SystemClock.elapsedRealtime() + (item.boostUntil - now), "×${item.boostMultiplier} %s", true)
            rv.setChronometerCountDown(R.id.boost, true)
        } else {
            rv.setViewVisibility(R.id.boost, View.GONE)
        }
        return rv
    }

    /** Star Bonus / Clock Tower: countdown to the next one, "Ready!", or the running boost. */
    private fun daily(d: Daily, now: Long): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item_daily)
        rv.setProgressBar(R.id.progress, 1000, (d.progress(now) * 1000).toInt(), false)
        when (d.kind) {
            DailyKind.HOME_STAR -> bindStar(rv, R.drawable.ic_star_home)
            DailyKind.BUILDER_STAR -> bindStar(rv, R.drawable.ic_star_builder)
            DailyKind.CLOCK_TOWER -> bindIcon(rv, CLOCK_TOWER_ID, d.level, Kind.BB_BUILDING)
        }
        rv.setTextViewText(R.id.name, context.getString(d.kind.label))
        when (d.state(now)) {
            Daily.State.ACTIVE -> {
                rv.setTextColor(R.id.name, context.getColor(R.color.goblin_green))
                rv.setImageViewResource(R.id.status, R.drawable.ic_clock)
                rv.setViewVisibility(R.id.time, View.VISIBLE)
                rv.setViewVisibility(R.id.time_static, View.GONE)
                rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() + (d.activeUntil - now), "×${ClockTower.MULTIPLIER} %s", true)
                rv.setChronometerCountDown(R.id.time, true)
            }
            Daily.State.COOLDOWN -> {
                rv.setTextColor(R.id.name, context.getColor(R.color.text_light))
                rv.setImageViewResource(R.id.status, R.drawable.ic_clock)
                countdown(rv, d.readyAt - now)
            }
            Daily.State.READY -> {
                // The full gold bar already says "ready"; amber text would vanish on it.
                rv.setTextColor(R.id.name, context.getColor(R.color.text_light))
                rv.setImageViewResource(R.id.status, R.drawable.ic_check)
                showStatic(rv, context.getString(R.string.daily_ready))
            }
        }
        // Ready rows log the tap (collected / boost started); others open the game.
        rv.setOnClickFillInIntent(R.id.daily_root, Intent().putExtra(SyncActivity.EXTRA_DAILY, d.kind.name))
        return rv
    }

    private fun row(u: Upgrade, now: Long): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item_row)
        rv.setProgressBar(R.id.progress, 1000, (u.progress(now) * 1000).toInt(), false)
        bindIcon(rv, u.id, u.level, u.kind)
        rv.setViewVisibility(R.id.goblin, if (u.goblin) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.helper_badge, if (u.helped) View.VISIBLE else View.GONE)

        val name = SpannableStringBuilder(u.name)
        val start = name.length
        name.append("  → ").append(u.target)
        name.setSpan(RelativeSizeSpan(0.8f), start, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rv.setTextViewText(R.id.name, name)

        if (u.finishAt <= now) {
            rv.setImageViewResource(R.id.clock, R.drawable.ic_check)
            showStatic(rv, context.getString(R.string.ready))
        } else {
            rv.setImageViewResource(R.id.clock, R.drawable.ic_clock)
            countdown(rv, u.finishAt - now)
        }
        rv.setOnClickFillInIntent(R.id.row_root, Intent())
        return rv
    }

    /** Apprentice / Lab Assistant: what it's on and its shift timer, or when it wakes up. */
    private fun helper(h: Helper, now: Long): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_item_helper)
        bindIcon(rv, h.id, 0, null)
        val who = context.getString(if (h.group == Group.BUILDERS) R.string.helper_apprentice else R.string.helper_assistant)
        when (h.state(now)) {
            Helper.State.WORKING -> {
                val shiftStart = h.workingUntil - HOUR
                rv.setProgressBar(R.id.progress, 1000, ((now - shiftStart) * 1000 / HOUR).toInt().coerceIn(0, 1000), false)
                rv.setTextViewText(R.id.name, context.getString(R.string.helper_working, who, h.task ?: "?"))
                rv.setTextColor(R.id.name, context.getColor(R.color.text_light))
                rv.setImageViewResource(R.id.status, R.drawable.ic_clock)
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                countdown(rv, h.workingUntil - now)
            }
            Helper.State.SLEEPING -> {
                rv.setProgressBar(R.id.progress, 1000, 0, false)
                rv.setTextViewText(R.id.name, context.getString(R.string.helper_sleeping, who))
                rv.setTextColor(R.id.name, context.getColor(R.color.sleep_lavender))
                rv.setImageViewResource(R.id.status, R.drawable.ic_moon)
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                countdown(rv, h.awakeAt - now)
            }
            Helper.State.READY -> {
                rv.setProgressBar(R.id.progress, 1000, 0, false)
                rv.setViewVisibility(R.id.status, View.GONE)
                if (h.keepAssigned && h.task != null) {
                    // "Keep assigned": the game puts it back on the same upgrade each work day.
                    rv.setTextViewText(R.id.name, context.getString(R.string.helper_working, who, h.task))
                    rv.setTextColor(R.id.name, context.getColor(R.color.text_light))
                    showStatic(rv, context.getString(R.string.helper_awake))
                } else {
                    rv.setTextViewText(R.id.name, context.getString(R.string.helper_ready, who))
                    rv.setTextColor(R.id.name, context.getColor(R.color.idle_amber))
                    showStatic(rv, "")
                }
            }
        }
        rv.setOnClickFillInIntent(R.id.helper_root, Intent())
        return rv
    }

    // Recycled rows keep the previous row's background and padding unless reset.
    private fun bindStar(rv: RemoteViews, drawable: Int) {
        rv.setImageViewResource(R.id.icon, drawable)
        rv.setInt(R.id.icon, "setBackgroundResource", 0)
        rv.setViewPadding(R.id.icon, starPadding, starPadding, starPadding, starPadding)
    }

    private fun bindIcon(rv: RemoteViews, id: Int, level: Int, kind: Kind?) {
        val art = icons.get(id, level)
        if (art != null) {
            rv.setImageViewBitmap(R.id.icon, art)
            rv.setInt(R.id.icon, "setBackgroundResource", 0)
            rv.setViewPadding(R.id.icon, 0, 0, 0, 0)
        } else {
            rv.setImageViewResource(R.id.icon, kind?.let(Icons::glyph) ?: R.drawable.ic_hero)
            rv.setInt(R.id.icon, "setBackgroundResource", kind?.let(Icons::background) ?: R.drawable.icon_bg_spell)
            rv.setViewPadding(R.id.icon, glyphPadding, glyphPadding, glyphPadding, glyphPadding)
        }
    }

    /** Live countdown in R.id.time, or a static "2d 14h" when exact refreshes aren't allowed. */
    private fun countdown(rv: RemoteViews, left: Long) {
        val days = left / DAY
        if (days == 0L || exact) {
            // Chronometer can't count days, so tick the remainder of the current day
            // behind a "Nd" prefix; Alarms re-renders when that remainder hits zero.
            rv.setViewVisibility(R.id.time, View.VISIBLE)
            rv.setViewVisibility(R.id.time_static, View.GONE)
            val format = if (days > 0) "${days}d %s" else null
            rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() + left - days * DAY, format, true)
            rv.setChronometerCountDown(R.id.time, true)
        } else {
            showStatic(rv, Time.daysHours(left))
        }
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

    private companion object {
        const val CLOCK_TOWER_ID = 1000039
    }
}

/** Fallback glyphs for items with no artwork in assets/icons. */
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

/**
 * Game artwork from app/src/main/assets/icons: "<id>.webp", or "<id>_<level>.webp" for
 * buildings and traps (see tools/update-assets.ps1). PNG works too.
 */
internal class IconCache(private val context: Context) {
    /** id -> (level, file) sorted by level; level 0 is the single, level-less icon. */
    private val index: Map<Int, List<Pair<Int, String>>> by lazy {
        val files = runCatching { context.assets.list("icons")?.toList() }.getOrNull().orEmpty()
        files.mapNotNull { file ->
            val m = NAME.matchEntire(file) ?: return@mapNotNull null
            Triple(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, file)
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.sortedBy { it.first } }
    }
    private val cache = HashMap<String, Bitmap?>()

    /** Art for [level], else the closest lower level, else the lowest one there is. */
    fun get(id: Int, level: Int): Bitmap? {
        val options = index[id] ?: return null
        val file = (options.lastOrNull { it.first <= level } ?: options.first()).second
        if (cache.containsKey(file)) return cache[file]
        return decode(file).also { cache[file] = it }
    }

    // Every row's bitmap is parceled to the launcher, so decode them small.
    private fun decode(file: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open("icons/$file").use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.assets.open("icons/$file").use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()?.let(::shrink)

    private fun shrink(b: Bitmap): Bitmap {
        if (b.width <= MAX_PX && b.height <= MAX_PX) return b
        val scale = MAX_PX.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
    }

    private companion object {
        const val MAX_PX = 112 // 36dp at xxhdpi
        val NAME = Regex("""(\d+)(?:_(\d+))?\.(?:webp|png)""")
    }
}

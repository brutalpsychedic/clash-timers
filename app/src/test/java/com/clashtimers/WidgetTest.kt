package com.clashtimers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val exportedAt = System.currentTimeMillis() / 1000 - 60
    private val sample get() = SAMPLE_EXPORT.replace("\"NOW\"", exportedAt.toString())

    private fun parse(boosts: List<Boost> = emptyList()) =
        ExportParser.parse(sample, GameData.get(context), boosts, null)

    @Test
    fun parsesExport() {
        val v = parse()
        assertEquals(16, v.townHall)
        assertEquals(6, v.homeBuilders) // 5 huts + B.O.B
        assertEquals(13, v.upgrades.size)

        val tesla = v.upgrades.first { it.kind == Kind.BUILDING && it.id == 1000019 }
        assertEquals("Hidden Tesla", tesla.name)
        assertEquals("15", tesla.target)
        assertEquals(exportedAt * 1000 + 3_695_000, tesla.finishAt)
        assertTrue(tesla.progress(System.currentTimeMillis()) > 0.95f)

        val bombTowers = v.upgrades.filter { it.id == 1000032 }
        assertEquals(listOf(false, true), bombTowers.map { it.goblin })
        assertEquals("Angry Jelly", v.upgrades.single { it.kind == Kind.PET }.name)
        assertEquals("Power P.E.K.K.A", v.upgrades.single { it.kind == Kind.BB_TROOP }.name)
    }

    @Test
    fun potionMath() {
        val start = exportedAt * 1000
        val potion = listOf(Boost(Group.BUILDERS, 10, start, start + HOUR))
        // 3695 s of work fits inside the potion hour at 10x.
        assertEquals(start + 369_500, BoostMath.finishTime(3_695_000, start, potion))
        // 48 h of work: the hour covers 10 h, the other 38 h run at 1x.
        assertEquals(start + 39 * HOUR, BoostMath.finishTime(48 * HOUR, start, potion))
        // A potion that starts later leaves the first stretch at 1x.
        val later = listOf(Boost(Group.BUILDERS, 10, start + HOUR, start + 2 * HOUR))
        assertEquals(start + HOUR + 6 * MINUTE, BoostMath.finishTime(2 * HOUR, start, later))
    }

    @Test
    fun sectionsMatchScreenshot() {
        val items = WidgetItems.build(context.resources, parse(), true, System.currentTimeMillis())
        val headers = items.filterIsInstance<WidgetItem.Header>()
        assertEquals(listOf("BUILDERS 6/6", "LAB", "PETS", "BUILDER BASE"), headers.map { it.title })
        assertEquals(listOf(true, true, false, false), headers.map { it.goblin })
        assertTrue(items.none { it is WidgetItem.Idle })
    }

    @Test
    fun idleBuildersShow() {
        val oneLess = sample.replace("""{"data": 1000005, "lvl": 16, "timer": 91244},""", "")
        val v = ExportParser.parse(oneLess, GameData.get(context), emptyList(), null)
        val items = WidgetItems.build(context.resources, v, true, System.currentTimeMillis())
        assertEquals("BUILDERS 5/6", (items[0] as WidgetItem.Header).title)
        assertTrue(items[1] is WidgetItem.HelperRow) // the Apprentice sits right under the header
        assertEquals("💤 1 builder idle", (items[2] as WidgetItem.Idle).text)
    }

    @Test
    fun helpersFromExport() {
        val v = parse()
        val start = exportedAt * 1000
        val now = System.currentTimeMillis()

        val apprentice = v.helpers.single { it.group == Group.BUILDERS }
        assertEquals(8, apprentice.level)
        assertEquals("Royal Champion", apprentice.task)
        assertEquals(start + 1_800_000, apprentice.workingUntil)
        assertEquals(Helper.State.WORKING, apprentice.state(now))
        assertTrue(apprentice.keepAssigned)

        // Half an hour at 1 + 8 = 9x covers 4.5 h of the 223 732 s left; the rest runs at 1x.
        val champion = v.upgrades.single { it.id == 28000004 }
        assertTrue(champion.helped)
        assertEquals(start + (1800 + 223_732 - 1800 * 9) * 1000L, champion.finishAt)

        val assistant = v.helpers.single { it.group == Group.LAB }
        assertEquals(null, assistant.task)
        assertEquals(start + 49_617_000, assistant.awakeAt)
        assertEquals(Helper.State.SLEEPING, assistant.state(now))
        assertEquals(Helper.State.READY, assistant.state(assistant.awakeAt))
        assertTrue(v.upgrades.none { it.kind.group == Group.LAB && it.helped })

        // Shift over, work day still running: the Apprentice is asleep.
        assertEquals(Helper.State.SLEEPING, apprentice.state(apprentice.workingUntil))
    }

    @Test
    fun helperStacksWithPotion() {
        val start = exportedAt * 1000
        val potion = listOf(Boost(Group.BUILDERS, 10, start, start + HOUR))
        // 10x potion + level 8 helper = 18x for the shared half hour, then 10x, then 1x.
        val work = 30 * MINUTE * 18 + 30 * MINUTE * 10 + HOUR
        assertEquals(start + 2 * HOUR, BoostMath.finishTime(work, start, potion, 8, start + 30 * MINUTE))
    }

    @Test
    fun iconsResolvePerLevel() {
        val icons = IconCache(context)
        assertTrue(icons.get(1000019, 15) != null)        // Hidden Tesla level 15 art
        assertTrue(icons.get(1000019, 99) != null)        // past the top level: falls back to the highest
        assertTrue(icons.get(28000001, 0) != null)        // Archer Queen
        assertTrue(icons.get(93000000, 0) != null)        // Builder's Apprentice
        assertEquals(null, icons.get(1000064, 1))         // B.O.B's Hut has no art: glyph fallback
    }

    @Test
    fun rendersWidget() {
        val store = Store(context)
        store.playerName = "Bj"
        assertTrue(Sync.importText(context, sample, force = true) is Sync.Imported)
        snapshot(store, "widget.png")

        assertTrue(Sync.logPotion(context, Group.BUILDERS, 10))
        assertTrue(store.village!!.boosts.single().isActive(System.currentTimeMillis()))
        snapshot(store, "widget-builder-potion.png")
    }

    /** Inflates the real RemoteViews and writes a PNG; the launcher normally does this. */
    private fun snapshot(store: Store, fileName: String) {
        val now = System.currentTimeMillis()
        val root = TimersWidget.rootViews(context, store, store.village, 1, now).apply(context, FrameLayout(context))

        // Collection rows are bound by the launcher at runtime; stack them by hand here.
        val factory = TimersFactory(context).apply { onDataSetChanged() }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until factory.count) column.addView(factory.getViewAt(i).apply(context, column))
        val listHost = root.findViewById<ListView>(R.id.list).parent as ViewGroup
        listHost.removeAllViews()
        listHost.addView(column)

        val density = context.resources.displayMetrics.density
        val width = (380 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        val margin = (16 * density).toInt()
        val bitmap = Bitmap.createBitmap(width + 2 * margin, root.measuredHeight + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val wallpaper = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, bitmap.height.toFloat(), 0xFF2B1650.toInt(), 0xFF5B2A86.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), wallpaper)
        canvas.translate(margin.toFloat(), margin.toFloat())
        root.draw(canvas)

        val out = File("build/preview/$fileName")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("snapshot: ${out.absolutePath}")
    }

    private companion object {
        /** Shaped like the in-game Data Export, mirroring the screenshot's village. */
        const val SAMPLE_EXPORT = """
        {
          "tag": "#2PP0JYQ8",
          "timestamp": "NOW",
          "helpers": [
            {"data": 93000000, "lvl": 8, "helper_cooldown": 49617},
            {"data": 93000001, "lvl": 10, "helper_cooldown": 49617}
          ],
          "buildings": [
            {"data": 1000001, "lvl": 16, "cnt": 1},
            {"data": 1000015, "lvl": 7, "cnt": 5},
            {"data": 1000064, "lvl": 1, "cnt": 1},
            {"data": 1000007, "lvl": 14, "cnt": 1},
            {"data": 1000068, "lvl": 10, "cnt": 1},
            {"data": 1000019, "lvl": 15, "cnt": 4},
            {"data": 1000019, "lvl": 14, "timer": 3695},
            {"data": 1000032, "lvl": 10, "timer": 3763},
            {"data": 1000032, "lvl": 10, "timer": 79089, "extra": true},
            {"data": 1000003, "lvl": 16, "timer": 40499},
            {"data": 1000005, "lvl": 16, "timer": 91244},
            {"data": 1000010, "lvl": 16, "cnt": 250}
          ],
          "heroes": [
            {"data": 28000000, "lvl": 61, "timer": 7054},
            {"data": 28000004, "lvl": 44, "timer": 223732, "helper_timer": 1800, "helper_recurrent": true}
          ],
          "units": [
            {"data": 4000003, "lvl": 10, "timer": 33395},
            {"data": 4000009, "lvl": 10, "timer": 85369, "extra": true}
          ],
          "spells": [{"data": 26000000, "lvl": 11}],
          "pets": [{"data": 73000011, "lvl": 1, "timer": 90792}],
          "buildings2": [
            {"data": 1000034, "lvl": 10, "cnt": 1},
            {"data": 1000043, "lvl": 4, "timer": 13969},
            {"data": 1000052, "lvl": 6, "timer": 286810}
          ],
          "units2": [{"data": 4000036, "lvl": 14, "timer": 335279}]
        }
        """
    }
}

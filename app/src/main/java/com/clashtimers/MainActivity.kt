package com.clashtimers

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/** Setup screen: name, manual sync, potions, and widget pinning. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var exactAlarms: Button
    private lateinit var exactAlarmsHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        status = findViewById(R.id.status)
        exactAlarms = findViewById(R.id.exact_alarms)
        exactAlarmsHint = findViewById(R.id.exact_alarms_hint)

        val name = findViewById<EditText>(R.id.player_name)
        name.setText(store.playerName)
        findViewById<Button>(R.id.save_name).setOnClickListener {
            store.playerName = name.text.toString()
            TimersWidget.refreshAll(this)
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(name.windowToken, 0)
            name.clearFocus()
            SyncActivity.toast(this, getString(R.string.name_saved))
        }

        findViewById<Button>(R.id.sync).setOnClickListener { syncFromClipboard() }
        findViewById<Button>(R.id.open_game).setOnClickListener { SyncActivity.openGame(this) }
        findViewById<Button>(R.id.potion_builder).setOnClickListener { potion(Group.BUILDERS, 10, R.string.potion_builder) }
        findViewById<Button>(R.id.potion_research).setOnClickListener { potion(Group.LAB, 24, R.string.potion_research) }
        findViewById<Button>(R.id.potion_pet).setOnClickListener { potion(Group.PETS, 24, R.string.potion_pet) }
        findViewById<Button>(R.id.clear_potions).setOnClickListener {
            Sync.clearPotions(this)
            refreshStatus()
        }

        findViewById<Button>(R.id.daily_home_star).setOnClickListener { daily(DailyKind.HOME_STAR) }
        findViewById<Button>(R.id.daily_builder_star).setOnClickListener { daily(DailyKind.BUILDER_STAR) }
        findViewById<Button>(R.id.daily_clock_tower).setOnClickListener { daily(DailyKind.CLOCK_TOWER) }
        findViewById<Button>(R.id.daily_reset).setOnClickListener {
            Sync.resetDailies(this)
            SyncActivity.toast(this, getString(R.string.daily_reset_done))
            refreshStatus()
        }

        findViewById<Switch>(R.id.show_builder_base).apply {
            isChecked = store.showBuilderBase
            setOnCheckedChangeListener { _, checked ->
                store.showBuilderBase = checked
                TimersWidget.refreshAll(this@MainActivity)
            }
        }

        findViewById<Button>(R.id.add_widget).setOnClickListener { pinWidget() }
        exactAlarms.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val askExact = if (Alarms.canExact(this)) View.GONE else View.VISIBLE
        exactAlarms.visibility = askExact
        exactAlarmsHint.visibility = askExact
        refreshStatus()
        // Exact-alarm permission may have just changed in Settings.
        TimersWidget.refreshAll(this)
    }

    private fun syncFromClipboard() {
        when (val result = Sync.importText(this, Clipboard.text(this), force = true)) {
            is Sync.Imported -> SyncActivity.toast(this, getString(R.string.synced, result.village.upgrades.size))
            Sync.NoExport, Sync.NotNewer -> SyncActivity.toast(this, getString(R.string.clipboard_empty))
            is Sync.Failed -> SyncActivity.toast(this, result.message)
        }
        refreshStatus()
    }

    private fun daily(kind: DailyKind) {
        if (store.village == null) {
            SyncActivity.toast(this, getString(R.string.sync_first))
            return
        }
        val message = Sync.useDaily(this, kind, force = true)
        SyncActivity.toast(this, message ?: getString(R.string.daily_not_ready, getString(kind.label)))
        refreshStatus()
    }

    private fun potion(group: Group, multiplier: Int, label: Int) {
        if (Sync.logPotion(this, group, multiplier)) {
            SyncActivity.toast(this, getString(R.string.potion_logged, getString(label)))
        } else {
            SyncActivity.toast(this, getString(R.string.sync_first))
        }
        refreshStatus()
    }

    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, TimersWidget::class.java), null, null)
        } else {
            SyncActivity.toast(this, getString(R.string.add_widget_manual))
        }
    }

    private fun refreshStatus() {
        val village = store.village
        if (village == null) {
            status.setText(R.string.status_empty)
            return
        }
        val now = System.currentTimeMillis()
        val running = village.upgrades.count { it.finishAt > now }
        val lines = mutableListOf(
            getString(R.string.status_village, village.townHall, village.tag),
            Time.exportedLabel(this, village, now),
            resources.getQuantityString(R.plurals.status_running, running, running),
        )
        for (b in village.boosts.filter { it.isActive(now) }) {
            val minutes = ((b.endsAt - now) / MINUTE).toInt() + 1
            lines += getString(R.string.status_boost, b.group.name.lowercase().replace('_', ' '), b.multiplier, minutes)
        }
        status.text = lines.joinToString("\n")
    }
}

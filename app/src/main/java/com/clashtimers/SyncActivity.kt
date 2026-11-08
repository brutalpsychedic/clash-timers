package com.clashtimers

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * Invisible activity behind the widget's Sync button and row taps. Android only lets the
 * focused app read the clipboard, so the widget can't sync on its own.
 */
class SyncActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val daily = intent?.getStringExtra(EXTRA_DAILY)?.let { name -> DailyKind.entries.firstOrNull { it.name == name } }
        if (daily != null) {
            // Tapped a DAILY row: a ready one is marked collected / started, others open the game.
            val message = Sync.useDaily(this, daily)
            if (message != null) toast(this, message) else openGame(this)
        } else if (intent?.action == ACTION_OPEN_GAME) {
            openGame(this)
        } else {
            when (val result = Sync.importText(this, Clipboard.text(this), force = false)) {
                is Sync.Imported -> toast(this, getString(R.string.synced, result.village.upgrades.size))
                Sync.NotNewer, Sync.NoExport -> {
                    toast(this, getString(R.string.no_new_export))
                    openGame(this)
                }
                is Sync.Failed -> toast(this, result.message)
            }
        }
        finish()
    }

    companion object {
        /** Fill-in extra on DAILY rows: the [DailyKind] name. */
        const val EXTRA_DAILY = "com.clashtimers.DAILY"
        private const val ACTION_SYNC = "com.clashtimers.SYNC"
        private const val ACTION_OPEN_GAME = "com.clashtimers.OPEN_GAME"
        private const val CLASH_PACKAGE = "com.supercell.clashofclans"
        private const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION

        fun syncIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, SyncActivity::class.java).setAction(ACTION_SYNC).addFlags(LAUNCH_FLAGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Collection rows fill this template in, so it has to be mutable on Android 12+. */
        fun openGameTemplate(context: Context): PendingIntent {
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getActivity(
                context,
                2,
                Intent(context, SyncActivity::class.java).setAction(ACTION_OPEN_GAME).addFlags(LAUNCH_FLAGS),
                mutable or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun openGame(activity: Activity) {
            val launch = activity.packageManager.getLaunchIntentForPackage(CLASH_PACKAGE)
            if (launch == null) toast(activity, activity.getString(R.string.clash_missing))
            else activity.startActivity(launch)
        }

        fun toast(context: Context, text: String) =
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}

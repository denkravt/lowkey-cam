package com.justan.lowkeycam.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Poke the launcher twice: the standard APPWIDGET_UPDATE broadcast makes the host ask for fresh
     * RemoteViews, and Glance's updateAll rebuilds its session. Without both, hosts happily keep
     * showing the session they cached before the data changed.
     */
    fun update(context: Context) {
        val app = context.applicationContext

        runCatching {
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, RecordWidgetReceiver::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(app, RecordWidgetReceiver::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                app.sendBroadcast(intent)
            }
        }.onFailure { Log.w("WidgetUpdater", "broadcast failed", it) }

        scope.launch {
            runCatching {
                RecordWidget().updateAll(app)
                QuickRecordWidget().updateAll(app)
            }.onFailure { Log.w("WidgetUpdater", "updateAll failed", it) }
        }
    }
}

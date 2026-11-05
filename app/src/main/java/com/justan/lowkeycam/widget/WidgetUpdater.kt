package com.justan.lowkeycam.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun update(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { RecordWidget().updateAll(app) }
        }
    }
}

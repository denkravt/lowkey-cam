package com.justan.lowkeycam.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.justan.lowkeycam.camera.CameraActivity

/**
 * Quick Settings tile: one swipe + tap from anywhere, straight into low-key recording.
 * This is the Android answer to an iOS lock-screen widget.
 */
class RecordTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = CameraActivity.intent(this, lowKey = true, autoStart = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(intent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
    }
}

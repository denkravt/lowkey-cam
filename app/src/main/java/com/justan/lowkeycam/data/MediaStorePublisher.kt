package com.justan.lowkeycam.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Hands a finished export to the device gallery (Movies/LowKey). */
object MediaStorePublisher {

    fun publish(context: Context, file: File, displayName: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(context, file, displayName)
        } else {
            publishLegacy(context, file, displayName)
        }
    }.getOrNull()

    private fun publishScoped(context: Context, file: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LowKey")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val copied: Boolean = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, file: File, displayName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "LowKey",
        ).apply { mkdirs() }
        val target = File(dir, displayName)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(target)
    }

    fun displayName(projectName: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US)
            .format(java.util.Date())
        val safe = projectName.trim().replace(Regex("[^A-Za-z0-9 ]"), "").ifBlank { "vlog" }
        return "$safe $stamp.mp4"
    }
}

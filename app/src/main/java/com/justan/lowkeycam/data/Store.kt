package com.justan.lowkeycam.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
)

data class Clip(
    val id: String,
    val projectId: String,
    val fileName: String,
    val durationMs: Long,
    val lowKey: Boolean,
    val createdAt: Long,
)

/**
 * Tiny local store: projects + clips, backed by SharedPreferences.
 * Clips themselves live in app-private storage (never the gallery).
 */
object Store {
    private const val PREFS = "lowkey"
    private const val K_PROJECTS = "projects"
    private const val K_CLIPS = "clips"
    private const val K_CURRENT = "current_project"
    private const val K_LOWKEY_DEFAULT = "lowkey_default"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clipsDir(c: Context): File =
        File(c.filesDir, "clips").apply { if (!exists()) mkdirs() }

    fun projects(c: Context): List<Project> {
        val raw = prefs(c).getString(K_PROJECTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Project(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveProjects(c: Context, list: List<Project>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("createdAt", it.createdAt)
            })
        }
        prefs(c).edit().putString(K_PROJECTS, arr.toString()).apply()
    }

    fun newProject(c: Context, name: String): Project {
        val p = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Untitled" },
            createdAt = System.currentTimeMillis(),
        )
        saveProjects(c, projects(c) + p)
        setCurrent(c, p.id)
        return p
    }

    fun renameProject(c: Context, id: String, name: String) {
        saveProjects(c, projects(c).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun setCurrent(c: Context, id: String) {
        prefs(c).edit().putString(K_CURRENT, id).apply()
    }

    fun currentProject(c: Context): Project? {
        val list = projects(c)
        if (list.isEmpty()) return null
        val id = prefs(c).getString(K_CURRENT, null)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun ensureProject(c: Context): Project =
        currentProject(c) ?: newProject(c, "My Vlog")

    fun clips(c: Context): List<Clip> {
        val raw = prefs(c).getString(K_CLIPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Clip(
                    id = o.getString("id"),
                    projectId = o.getString("projectId"),
                    fileName = o.getString("fileName"),
                    durationMs = o.optLong("durationMs", 0L),
                    lowKey = o.optBoolean("lowKey", false),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clips(c: Context, projectId: String): List<Clip> =
        clips(c).filter { it.projectId == projectId }.sortedBy { it.createdAt }

    fun addClip(c: Context, clip: Clip) {
        saveClips(c, clips(c) + clip)
    }

    fun updateClip(c: Context, clip: Clip) {
        saveClips(c, clips(c).map { if (it.id == clip.id) clip else it })
    }

    fun removeClip(c: Context, clipId: String) {
        saveClips(c, clips(c).filterNot { it.id == clipId })
    }

    private fun saveClips(c: Context, all: List<Clip>) {
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("projectId", it.projectId)
                put("fileName", it.fileName)
                put("durationMs", it.durationMs)
                put("lowKey", it.lowKey)
                put("createdAt", it.createdAt)
            })
        }
        prefs(c).edit().putString(K_CLIPS, arr.toString()).apply()
    }

    fun latestClip(c: Context, projectId: String): Clip? =
        clips(c, projectId).maxByOrNull { it.createdAt }

    fun totalDurationMs(c: Context, projectId: String): Long =
        clips(c, projectId).sumOf { it.durationMs }

    fun lowKeyDefault(c: Context): Boolean = prefs(c).getBoolean(K_LOWKEY_DEFAULT, true)

    fun setLowKeyDefault(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(K_LOWKEY_DEFAULT, value).apply()
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "%d:%02d".format(m, s) else "0:%02d".format(s)
}

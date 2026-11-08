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
    val order: Long = createdAt,
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
                    order = o.optLong("order", o.optLong("createdAt", 0L)),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clips(c: Context, projectId: String): List<Clip> =
        clips(c)
            .filter { it.projectId == projectId }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))

    fun addClip(c: Context, clip: Clip) {
        saveClips(c, clips(c) + clip)
    }

    /** Moves a clip one slot up (-1) or down (+1) inside its project. */
    fun moveClip(c: Context, projectId: String, clipId: String, delta: Int) {
        val ordered = clips(c, projectId)
        val from = ordered.indexOfFirst { it.id == clipId }
        if (from < 0) return
        moveClipTo(c, projectId, from, from + delta)
    }

    /** Drops the clip at [from] onto [to]; everything in between shifts. */
    fun moveClipTo(c: Context, projectId: String, from: Int, to: Int) {
        val ordered = clips(c, projectId).toMutableList()
        if (from !in ordered.indices || to !in ordered.indices || from == to) return
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)

        val renumbered = ordered.mapIndexed { index, clip -> clip.copy(order = index * 1000L) }
        val others = clips(c).filterNot { it.projectId == projectId }
        saveClips(c, others + renumbered)
    }

    fun updateClip(c: Context, clip: Clip) {
        saveClips(c, clips(c).map { if (it.id == clip.id) clip else it })
    }

    fun removeClip(c: Context, clipId: String) {
        saveClips(c, clips(c).filterNot { it.id == clipId })
    }

    /** Deletes every clip in a project, records and files, but keeps the project. Returns the count. */
    fun deleteAllClips(c: Context, projectId: String): Int {
        val doomed = clips(c, projectId)
        if (doomed.isEmpty()) return 0
        saveClips(c, clips(c).filterNot { it.projectId == projectId })
        val dir = clipsDir(c)
        doomed.forEach { runCatching { File(dir, it.fileName).delete() } }
        pruneOrphans(c)
        return doomed.size
    }

    /**
     * Drops clip files that no record points at - leftovers from interrupted takes or older builds.
     * Files newer than a few minutes are left alone so an in-flight take is never deleted.
     */
    fun pruneOrphans(c: Context): Int {
        val referenced = clips(c).map { it.fileName }.toSet()
        val cutoff = System.currentTimeMillis() - 5 * 60_000L
        var pruned = 0
        clipsDir(c).listFiles()?.forEach { file ->
            if (file.name !in referenced && file.lastModified() < cutoff) {
                if (runCatching { file.delete() }.getOrDefault(false)) pruned++
            }
        }
        return pruned
    }

    /** Deletes a project with all of its clips, its music and its settings. Returns the clip count. */
    fun deleteProject(c: Context, projectId: String): Int {
        val doomed = clips(c, projectId)
        saveClips(c, clips(c).filterNot { it.projectId == projectId })
        val dir = clipsDir(c)
        doomed.forEach { runCatching { File(dir, it.fileName).delete() } }
        musicFor(c, projectId)?.let { name -> runCatching { File(musicDir(c), name).delete() } }
        prefs(c).edit()
            .remove("music_" + projectId)
            .remove("music_vol_" + projectId)
            .apply()

        val remaining = projects(c).filterNot { it.id == projectId }
        saveProjects(c, remaining)
        if (prefs(c).getString(K_CURRENT, null) == projectId) {
            if (remaining.isNotEmpty()) setCurrent(c, remaining.first().id)
            else prefs(c).edit().remove(K_CURRENT).apply()
        }
        pruneOrphans(c)
        return doomed.size
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
                put("order", it.order)
            })
        }
        prefs(c).edit().putString(K_CLIPS, arr.toString()).apply()
    }

    fun latestClip(c: Context, projectId: String): Clip? =
        clips(c, projectId).maxByOrNull { it.createdAt }

    fun totalDurationMs(c: Context, projectId: String): Long =
        clips(c, projectId).sumOf { it.durationMs }

    fun musicFor(c: Context, projectId: String): String? =
        prefs(c).getString("music_" + projectId, null)

    fun setMusic(c: Context, projectId: String, fileName: String?) {
        prefs(c).edit().apply {
            if (fileName == null) remove("music_" + projectId) else putString("music_" + projectId, fileName)
        }.apply()
    }

    fun musicVolumeFor(c: Context, projectId: String): Float =
        prefs(c).getFloat("music_vol_" + projectId, 0.35f)

    fun setMusicVolume(c: Context, projectId: String, volume: Float) {
        prefs(c).edit().putFloat("music_vol_" + projectId, volume.coerceIn(0f, 1f)).apply()
    }

    fun musicDir(c: Context): java.io.File =
        java.io.File(c.filesDir, "music").apply { if (!exists()) mkdirs() }

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

package com.comic.xenvyx.id.data

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Versioned JSON backup for bookmarks and reading history only. No cookies or credentials. */
class ReadingBackupManager(private val resolver: ContentResolver, private val database: ReadingDatabase) {
    data class Record(
        val comicId: String,
        val chapterId: String,
        val chapterTitle: String,
        val panelIndex: Int,
        val timestamp: Long
    )

    data class Snapshot(val bookmarks: List<Record>, val history: List<Record>)

    enum class ImportMode { MERGE, REPLACE }

    fun export(uri: Uri) {
        val snapshot = database.snapshot()
        val root = JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("created_at", System.currentTimeMillis())
            put("bookmarks", snapshot.bookmarks.toJsonArray())
            put("history", snapshot.history.toJsonArray())
        }
        resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString(2))
        } ?: error("Cannot open the selected backup destination")
    }

    fun import(uri: Uri, mode: ImportMode): Snapshot {
        val raw = resolver.openInputStream(uri)?.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BACKUP_CHARS) { "Backup file is too large" }
                text.append(buffer, 0, count)
            }
            text.toString()
        } ?: error("Cannot open the selected backup file")

        val root = JSONObject(raw)
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) { "Unsupported backup version" }
        val snapshot = Snapshot(
            root.getJSONArray("bookmarks").toRecords(),
            root.getJSONArray("history").toRecords()
        )
        database.restore(snapshot, replace = mode == ImportMode.REPLACE)
        return snapshot
    }

    private fun List<Record>.toJsonArray() = JSONArray().apply {
        forEach { record -> put(JSONObject().apply {
            put("comic_id", record.comicId)
            put("chapter_id", record.chapterId)
            put("chapter_title", record.chapterTitle)
            put("panel_index", record.panelIndex)
            put("timestamp", record.timestamp)
        }) }
    }

    private fun JSONArray.toRecords(): List<Record> {
        require(length() <= MAX_RECORDS) { "Too many records in backup" }
        return (0 until length()).map { index ->
            val item = getJSONObject(index)
            val comicId = item.getString("comic_id")
            val chapterId = item.getString("chapter_id")
            val title = item.optString("chapter_title", "")
            val panel = item.optInt("panel_index", 0)
            val timestamp = item.optLong("timestamp", 0L)
            require(comicId.isNotBlank() && comicId.length <= 512)
            require(chapterId.isNotBlank() && chapterId.length <= 512)
            require(title.length <= 2000 && panel >= 0 && timestamp >= 0L)
            Record(comicId, chapterId, title, panel, timestamp)
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_BACKUP_CHARS = 10 * 1024 * 1024
        private const val MAX_RECORDS = 100_000
    }
}

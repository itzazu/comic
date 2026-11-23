package com.comic.xenvyx.id.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local metadata only. Downloaded image files are managed separately from this database. */
class ReadingDatabase(context: Context) : SQLiteOpenHelper(context, "komiku_reader.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE bookmarks (
            comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, chapter_title TEXT NOT NULL,
            panel_index INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
            PRIMARY KEY(comic_id, chapter_id)
        )""".trimIndent())
        db.execSQL("""CREATE TABLE history (
            comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, chapter_title TEXT NOT NULL,
            panel_index INTEGER NOT NULL DEFAULT 0, last_read_at INTEGER NOT NULL,
            PRIMARY KEY(comic_id, chapter_id)
        )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations must preserve user reading data.
    }

    fun saveBookmark(comicId: String, chapterId: String, title: String, panel: Int) =
        upsert("bookmarks", comicId, chapterId, title, panel, "updated_at")

    fun saveProgress(comicId: String, chapterId: String, title: String, panel: Int) =
        upsert("history", comicId, chapterId, title, panel, "last_read_at")

    fun snapshot(): ReadingBackupManager.Snapshot = ReadingBackupManager.Snapshot(
        readRecords("bookmarks", "updated_at"),
        readRecords("history", "last_read_at")
    )

    fun restore(snapshot: ReadingBackupManager.Snapshot, replace: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("bookmarks", null, null)
                db.delete("history", null, null)
            }
            snapshot.bookmarks.forEach { insertRecord(db, "bookmarks", "updated_at", it) }
            snapshot.history.forEach { insertRecord(db, "history", "last_read_at", it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readRecords(table: String, timeColumn: String): List<ReadingBackupManager.Record> =
        readableDatabase.rawQuery(
            "SELECT comic_id, chapter_id, chapter_title, panel_index, $timeColumn FROM $table ORDER BY $timeColumn DESC",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ReadingBackupManager.Record(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3), cursor.getLong(4)
                ))
            }
        }

    private fun insertRecord(db: SQLiteDatabase, table: String, timeColumn: String, record: ReadingBackupManager.Record) {
        val values = ContentValues().apply {
            put("comic_id", record.comicId)
            put("chapter_id", record.chapterId)
            put("chapter_title", record.chapterTitle)
            put("panel_index", record.panelIndex)
            put(timeColumn, record.timestamp)
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsert(table: String, comicId: String, chapterId: String, title: String, panel: Int, timeColumn: String) {
        require(comicId.isNotBlank() && chapterId.isNotBlank())
        val values = ContentValues().apply {
            put("comic_id", comicId)
            put("chapter_id", chapterId)
            put("chapter_title", title)
            put("panel_index", panel.coerceAtLeast(0))
            put(timeColumn, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

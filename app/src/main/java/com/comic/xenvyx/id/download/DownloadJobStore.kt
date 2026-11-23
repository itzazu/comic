package com.comic.xenvyx.id.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.URL

/** Persistent manifest and progress store. WorkManager input contains only a job ID. */
class DownloadJobStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "komiku_downloads.db", null, 1) {

    data class Job(
        val id: String,
        val comicId: String,
        val chapterId: String,
        val status: String,
        val imageCount: Int,
        val completedCount: Int
    )

    data class Image(val index: Int, val url: String, val status: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE download_jobs (
            id TEXT PRIMARY KEY NOT NULL,
            comic_id TEXT NOT NULL,
            chapter_id TEXT NOT NULL,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            error TEXT
        )""".trimIndent())
        db.execSQL("""CREATE TABLE download_images (
            job_id TEXT NOT NULL,
            image_index INTEGER NOT NULL,
            image_url TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            PRIMARY KEY(job_id, image_index),
            FOREIGN KEY(job_id) REFERENCES download_jobs(id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("""CREATE TABLE download_allowed_hosts (
            job_id TEXT NOT NULL,
            host TEXT NOT NULL,
            PRIMARY KEY(job_id, host),
            FOREIGN KEY(job_id) REFERENCES download_jobs(id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX idx_download_jobs_status ON download_jobs(status, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migrations only. Never discard a user's download history or manifests.
    }

    fun createJob(id: String, comicId: String, chapterId: String, urls: List<String>, allowedHosts: Set<String>) {
        require(id.isNotBlank() && id.length <= 120)
        require(comicId.isSafeSegment() && chapterId.isSafeSegment())
        require(urls.isNotEmpty())
        val normalizedHosts = allowedHosts.map { it.lowercase() }.toSet()
        require(normalizedHosts.isNotEmpty())
        urls.forEach { raw ->
            val parsed = URL(raw)
            require(parsed.protocol == "https" && parsed.host.lowercase() in normalizedHosts) {
                "Every image URL must use an allow-listed HTTPS host"
            }
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val job = ContentValues().apply {
                put("id", id); put("comic_id", comicId); put("chapter_id", chapterId)
                put("status", STATUS_QUEUED); put("created_at", now); put("updated_at", now)
            }
            db.insertOrThrow("download_jobs", null, job)
            normalizedHosts.forEach { host ->
                val hostValues = ContentValues().apply { put("job_id", id); put("host", host) }
                db.insertOrThrow("download_allowed_hosts", null, hostValues)
            }
            urls.forEachIndexed { index, url ->
                val image = ContentValues().apply {
                    put("job_id", id); put("image_index", index); put("image_url", url); put("status", STATUS_PENDING)
                }
                db.insertOrThrow("download_images", null, image)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getJob(id: String): Job? = readableDatabase.rawQuery(
        """SELECT j.id, j.comic_id, j.chapter_id, j.status,
            COUNT(i.image_index), SUM(CASE WHEN i.status='complete' THEN 1 ELSE 0 END)
            FROM download_jobs j LEFT JOIN download_images i ON i.job_id=j.id
            WHERE j.id=? GROUP BY j.id""", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) null else Job(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3),
            c.getInt(4), c.getInt(5)
        )
    }

    fun findReusableJob(comicId: String, chapterId: String): Job? = readableDatabase.rawQuery(
        "SELECT id FROM download_jobs WHERE comic_id=? AND chapter_id=? ORDER BY created_at DESC LIMIT 1",
        arrayOf(comicId, chapterId)
    ).use { c -> if (c.moveToFirst()) getJob(c.getString(0)) else null }

    fun getAllowedHosts(jobId: String): Set<String> = readableDatabase.rawQuery(
        "SELECT host FROM download_allowed_hosts WHERE job_id=?", arrayOf(jobId)
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun getImages(jobId: String): List<Image> = readableDatabase.rawQuery(
        "SELECT image_index, image_url, status FROM download_images WHERE job_id=? ORDER BY image_index",
        arrayOf(jobId)
    ).use { c ->
        buildList { while (c.moveToNext()) add(Image(c.getInt(0), c.getString(1), c.getString(2))) }
    }

    fun setJobStatus(id: String, status: String, error: String? = null) {
        writableDatabase.update("download_jobs", ContentValues().apply {
            put("status", status); put("updated_at", System.currentTimeMillis()); put("error", error)
        }, "id=?", arrayOf(id))
    }

    fun setImageStatus(jobId: String, index: Int, status: String) {
        writableDatabase.update("download_images", ContentValues().apply { put("status", status) },
            "job_id=? AND image_index=?", arrayOf(jobId, index.toString()))
        writableDatabase.update("download_jobs", ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(jobId))
    }

    fun deleteJob(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("download_images", "job_id=?", arrayOf(id))
            db.delete("download_allowed_hosts", "job_id=?", arrayOf(id))
            db.delete("download_jobs", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listJobs(): List<Job> = readableDatabase.rawQuery(
        """SELECT j.id, j.comic_id, j.chapter_id, j.status,
            COUNT(i.image_index), SUM(CASE WHEN i.status='complete' THEN 1 ELSE 0 END)
            FROM download_jobs j LEFT JOIN download_images i ON i.job_id=j.id
            GROUP BY j.id ORDER BY j.created_at""", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(Job(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5)
        )) }
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_PENDING = "pending"
        const val STATUS_IMAGE_COMPLETE = "complete"
        const val STATUS_IMAGE_FAILED = "failed"
    }
}

internal fun String.isSafeSegment(): Boolean =
    isNotBlank() && length <= 120 && matches(Regex("[A-Za-z0-9._-]+")) && this != "." && this != ".."

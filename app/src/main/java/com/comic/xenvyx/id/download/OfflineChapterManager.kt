package com.comic.xenvyx.id.download

import android.content.Context
import androidx.work.WorkManager
import java.io.File

/** Lists and removes completed offline chapters without touching bookmarks or reading history. */
class OfflineChapterManager(context: Context) {
    private val appContext = context.applicationContext
    private val store = DownloadJobStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    data class OfflineChapter(
        val jobId: String,
        val comicId: String,
        val chapterId: String,
        val imageCount: Int,
        val totalBytes: Long,
        val directory: File
    )

    fun list(): List<OfflineChapter> = store.listJobs()
        .filter { it.status == DownloadJobStore.STATUS_COMPLETE }
        .mapNotNull { job ->
            val directory = File(appContext.filesDir, "offline/${job.comicId}/${job.chapterId}")
            val images = directory.listFiles()?.filter { it.isFile && it.extension == "img" }.orEmpty()
            if (images.isEmpty()) null else OfflineChapter(
                job.id, job.comicId, job.chapterId, images.size,
                images.sumOf { it.length() }, directory
            )
        }

    fun imageFiles(comicId: String, chapterId: String): List<File> {
        require(comicId.isSafeSegment() && chapterId.isSafeSegment())
        val directory = File(appContext.filesDir, "offline/$comicId/$chapterId")
        return directory.listFiles()?.filter { it.isFile && it.extension == "img" }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun delete(jobId: String): Boolean {
        val job = store.getJob(jobId) ?: return false
        // Cancellation is asynchronous. Confirm WorkManager has stopped this job before deleting files.
        val workInfos = try {
            workManager.getWorkInfosByTag("download:$jobId").get()
        } catch (_: Exception) {
            return false
        }
        if (workInfos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                it.state == androidx.work.WorkInfo.State.RUNNING ||
                it.state == androidx.work.WorkInfo.State.BLOCKED }) return false
        if (job.status == DownloadJobStore.STATUS_QUEUED || job.status == DownloadJobStore.STATUS_RUNNING) {
            workManager.cancelAllWorkByTag("download:$jobId")
            return false
        }
        val directory = File(appContext.filesDir, "offline/${job.comicId}/${job.chapterId}")
        val removed = !directory.exists() || directory.deleteRecursively()
        if (removed) store.deleteJob(jobId)
        return removed
    }
}

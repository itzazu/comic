package com.comic.xenvyx.id.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/** Persists the complete manifest, then queues a tiny WorkManager request containing only its ID. */
class DownloadScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val store = DownloadJobStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(comicId: String, chapterId: String, imageUrls: List<String>, allowedHosts: Set<String>): String {
        val existing = store.findReusableJob(comicId, chapterId)
        if (existing != null && existing.status in setOf(
                DownloadJobStore.STATUS_QUEUED,
                DownloadJobStore.STATUS_RUNNING,
                DownloadJobStore.STATUS_COMPLETE
            )) return existing.id

        val jobId = UUID.randomUUID().toString()
        store.createJob(jobId, comicId, chapterId, imageUrls, allowedHosts)
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG_DOWNLOAD)
            .addTag("download:$jobId")
            .build()

        // APPEND_OR_REPLACE serializes chapter jobs into one FIFO chain (one active chapter at a time).
        workManager.beginUniqueWork(UNIQUE_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
        return jobId
    }

    fun cancel(jobId: String) {
        workManager.cancelAllWorkByTag("download:$jobId")
        store.setJobStatus(jobId, DownloadJobStore.STATUS_CANCELLED)
    }

    fun jobs(): List<DownloadJobStore.Job> = store.listJobs()

    companion object {
        private const val UNIQUE_QUEUE = "komiku_chapter_download_queue"
        private const val TAG_DOWNLOAD = "komiku_chapter_download"
    }
}

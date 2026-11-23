package com.comic.xenvyx.id.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads only publicly accessible, caller-supplied images on explicitly allow-listed HTTPS hosts. */
class ChapterDownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val store by lazy { DownloadJobStore(applicationContext) }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val job = store.getJob(jobId) ?: return Result.failure()
        val images = store.getImages(jobId)
        val allowedHosts = store.getAllowedHosts(jobId)
        if (images.isEmpty() || allowedHosts.isEmpty()) {
            store.setJobStatus(jobId, DownloadJobStore.STATUS_FAILED, "Empty image manifest")
            return Result.failure()
        }

        val chapterDir = File(applicationContext.filesDir, "offline/${job.comicId}/${job.chapterId}")
        if (!chapterDir.exists() && !chapterDir.mkdirs()) {
            store.setJobStatus(jobId, DownloadJobStore.STATUS_FAILED, "Cannot create offline directory")
            return Result.failure()
        }

        store.setJobStatus(jobId, DownloadJobStore.STATUS_RUNNING)
        var completed = images.count { it.status == DownloadJobStore.STATUS_IMAGE_COMPLETE }
        for (image in images) {
            if (isStopped) return Result.retry()
            val target = File(chapterDir, "%04d.img".format(image.index + 1))
            if (target.exists() && target.length() > 0L) {
                store.setImageStatus(jobId, image.index, DownloadJobStore.STATUS_IMAGE_COMPLETE)
                if (image.status != DownloadJobStore.STATUS_IMAGE_COMPLETE) completed++
                publishProgress(completed, images.size)
                continue
            }

            val temp = File(chapterDir, target.name + ".part")
            try {
                downloadImage(image.url, allowedHosts, temp)
                if (!temp.renameTo(target)) error("Could not finalize image file")
                store.setImageStatus(jobId, image.index, DownloadJobStore.STATUS_IMAGE_COMPLETE)
                if (image.status != DownloadJobStore.STATUS_IMAGE_COMPLETE) completed++
                publishProgress(completed, images.size)
            } catch (e: InterruptedException) {
                temp.delete()
                return Result.retry()
            } catch (e: Exception) {
                temp.delete()
                store.setImageStatus(jobId, image.index, DownloadJobStore.STATUS_IMAGE_FAILED)
                if (runAttemptCount < MAX_RETRIES) {
                    store.setJobStatus(jobId, DownloadJobStore.STATUS_QUEUED, e.message)
                    return Result.retry()
                }
                store.setJobStatus(jobId, DownloadJobStore.STATUS_FAILED, e.message ?: "Download failed")
                return Result.failure(Data.Builder().putString(KEY_ERROR, e.message ?: "Download failed").build())
            }
        }

        store.setJobStatus(jobId, DownloadJobStore.STATUS_COMPLETE)
        return Result.success(Data.Builder()
            .putString(KEY_OFFLINE_PATH, chapterDir.absolutePath)
            .putInt(KEY_COMPLETED, completed)
            .putInt(KEY_TOTAL, images.size)
            .build())
    }

    private suspend fun publishProgress(completed: Int, total: Int) {
        setProgress(Data.Builder()
            .putInt(KEY_COMPLETED, completed)
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_PERCENT, completed * 100 / total)
            .build())
    }

    private fun downloadImage(raw: String, allowedHosts: Set<String>, destination: File) {
        var current = URL(raw)
        for (redirectCount in 0..5) {
            require(current.protocol == "https") { "HTTPS required" }
            require(current.host.lowercase() in allowedHosts) { "Host is not allow-listed" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "KomikuReader/0.1 Android")
                setRequestProperty("Accept", "image/avif,image/webp,image/*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(redirectCount < 5) { "Too many redirects" }
                    val location = connection.getHeaderField("Location") ?: error("Redirect without location")
                    current = URL(current, location)
                    continue
                }
                require(code in 200..299) { "HTTP $code" }
                val type = connection.contentType?.substringBefore(';')?.lowercase().orEmpty()
                require(type.startsWith("image/")) { "Response is not an image" }
                require(connection.contentLength.toLong() <= MAX_IMAGE_BYTES) { "Image exceeds 20 MB limit" }
                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            if (isStopped) throw InterruptedException("Worker stopped")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMAGE_BYTES) { "Image exceeds 20 MB limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(destination.length() > 0L) { "Empty image" }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Unable to download image")
    }

    companion object {
        const val KEY_JOB_ID = "download_job_id"
        const val KEY_COMPLETED = "completed_images"
        const val KEY_TOTAL = "total_images"
        const val KEY_PERCENT = "progress_percent"
        const val KEY_OFFLINE_PATH = "offline_path"
        const val KEY_ERROR = "error"
        private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
        private const val MAX_RETRIES = 3
    }
}

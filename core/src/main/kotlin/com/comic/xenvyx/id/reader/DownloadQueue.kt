package com.comic.xenvyx.id.reader

@JvmInline
value class DownloadId(val value: String)

data class DownloadTask(
    val id: DownloadId,
    val comicId: String,
    val chapterId: String,
    val imageUrls: List<String>,
    val completedImages: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
) {
    val totalImages: Int get() = imageUrls.size
    val progressPercent: Int
        get() = if (totalImages == 0) 0 else (completedImages * 100 / totalImages).coerceIn(0, 100)
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** In-memory queue policy. Persistence and actual HTTP transfer are owned by the Android layer. */
class DownloadQueueCoordinator(private val maxConcurrent: Int = 1) {
    init { require(maxConcurrent >= 1) }
    private val tasks = linkedMapOf<DownloadId, DownloadTask>()

    @Synchronized fun snapshot(): List<DownloadTask> = tasks.values.toList()

    @Synchronized fun enqueue(task: DownloadTask): Boolean {
        require(task.comicId.isNotBlank() && task.chapterId.isNotBlank())
        require(task.imageUrls.isNotEmpty())
        require(task.imageUrls.all { it.startsWith("https://") })
        if (tasks.values.any { it.comicId == task.comicId && it.chapterId == task.chapterId &&
                    it.status !in setOf(DownloadStatus.CANCELLED, DownloadStatus.FAILED) }) return false
        tasks[task.id] = task.copy(status = DownloadStatus.QUEUED, completedImages = 0, error = null)
        schedule()
        return true
    }

    @Synchronized fun markImageCompleted(id: DownloadId): DownloadTask? {
        val current = tasks[id] ?: return null
        if (current.status != DownloadStatus.RUNNING) return current
        val completed = (current.completedImages + 1).coerceAtMost(current.totalImages)
        val updated = current.copy(completedImages = completed,
            status = if (completed == current.totalImages) DownloadStatus.COMPLETED else DownloadStatus.RUNNING)
        tasks[id] = updated
        if (updated.status == DownloadStatus.COMPLETED) schedule()
        return updated
    }

    @Synchronized fun pause(id: DownloadId): Boolean = updateStatus(id, DownloadStatus.RUNNING, DownloadStatus.PAUSED)
    @Synchronized fun resume(id: DownloadId): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != DownloadStatus.PAUSED) return false
        tasks[id] = current.copy(status = DownloadStatus.QUEUED)
        schedule()
        return true
    }
    @Synchronized fun cancel(id: DownloadId): Boolean {
        val current = tasks[id] ?: return false
        if (current.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED)) return false
        tasks[id] = current.copy(status = DownloadStatus.CANCELLED)
        schedule()
        return true
    }
    @Synchronized fun fail(id: DownloadId, reason: String): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != DownloadStatus.RUNNING) return false
        tasks[id] = current.copy(status = DownloadStatus.FAILED, error = reason)
        schedule()
        return true
    }
    @Synchronized fun retry(id: DownloadId): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != DownloadStatus.FAILED) return false
        tasks[id] = current.copy(status = DownloadStatus.QUEUED, completedImages = 0, error = null)
        schedule()
        return true
    }

    private fun updateStatus(id: DownloadId, from: DownloadStatus, to: DownloadStatus): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != from) return false
        tasks[id] = current.copy(status = to)
        schedule()
        return true
    }

    private fun schedule() {
        var running = tasks.values.count { it.status == DownloadStatus.RUNNING }
        if (running >= maxConcurrent) return
        tasks.entries.toList().forEach { (id, task) ->
            if (running >= maxConcurrent) return
            if (task.status == DownloadStatus.QUEUED) {
                tasks[id] = task.copy(status = DownloadStatus.RUNNING)
                running++
            }
        }
    }
}

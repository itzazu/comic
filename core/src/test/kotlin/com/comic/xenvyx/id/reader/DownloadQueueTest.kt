package com.comic.xenvyx.id.reader

fun main() {
    val queue = DownloadQueueCoordinator(maxConcurrent = 1)
    fun task(id: String, chapter: String) = DownloadTask(
        DownloadId(id), "comic-a", chapter,
        listOf("https://img.komiku.org/1.jpg", "https://img.komiku.org/2.jpg")
    )
    check(queue.enqueue(task("d1", "ch1")))
    check(queue.enqueue(task("d2", "ch2")))
    check(queue.snapshot().first().status == DownloadStatus.RUNNING)
    check(queue.snapshot()[1].status == DownloadStatus.QUEUED)
    check(!queue.enqueue(task("d3", "ch1")))
    queue.markImageCompleted(DownloadId("d1"))
    check(queue.snapshot()[0].progressPercent == 50)
    queue.markImageCompleted(DownloadId("d1"))
    check(queue.snapshot()[0].status == DownloadStatus.COMPLETED)
    check(queue.snapshot()[1].status == DownloadStatus.RUNNING)
    check(queue.pause(DownloadId("d2")))
    check(queue.snapshot()[1].status == DownloadStatus.PAUSED)
    check(queue.resume(DownloadId("d2")))
    check(queue.snapshot()[1].status == DownloadStatus.RUNNING)
    check(queue.fail(DownloadId("d2"), "network error"))
    check(queue.retry(DownloadId("d2")))
    check(queue.snapshot()[1].status == DownloadStatus.RUNNING)
    println("PASS: queue order, duplicate prevention, progress, pause/resume, failure/retry")
}

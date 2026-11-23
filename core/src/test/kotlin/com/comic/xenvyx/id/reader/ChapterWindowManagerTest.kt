package com.comic.xenvyx.id.reader

/** Executable smoke test; run with the small script in the project root. */
fun main() {
    val requested = mutableListOf<ChapterDescriptor>()
    val released = mutableListOf<ChapterDescriptor>()
    val manager = ChapterWindowManager(
        maxActiveChapters = 5,
        requestChapter = requested::add,
        releaseInMemoryResources = released::add,
    )

    fun chapter(n: Long) = ChapterDescriptor(ChapterId("ch$n"), n, "Chapter $n")

    for (n in 1L..5L) manager.registerOpened(chapter(n), panelCount = 20)

    // Intermediate panel must not preload.
    check(manager.onPanelReached(ChapterId("ch5"), 18, false, chapter(6)) == WindowEvent.NoOp)
    check(requested.isEmpty())

    // Only the final panel requests the next chapter.
    check(manager.onPanelReached(ChapterId("ch5"), 19, true, chapter(6)) == WindowEvent.LoadRequested(chapter(6)))
    check(requested == listOf(chapter(6)))
    check(released.isEmpty()) // Never evict before the replacement is ready.

    val events = manager.onLoadSucceeded(ChapterId("ch6"), panelCount = 20)
    check(manager.snapshot().map { it.descriptor.id.value } == listOf("ch2", "ch3", "ch4", "ch5", "ch6"))
    check(released.map { it.id.value } == listOf("ch1"))
    check(events.any { it is WindowEvent.MemoryReleaseRequested && it.chapter.id.value == "ch1" })

    // Scrolling back to an older chapter's end must not initiate another load.
    check(manager.onPanelReached(ChapterId("ch4"), 19, true, chapter(5)) == WindowEvent.NoOp)

    // Reaching the last panel of the newest chapter starts the next cycle.
    check(manager.onPanelReached(ChapterId("ch6"), 19, true, chapter(7)) == WindowEvent.LoadRequested(chapter(7)))
    manager.onLoadSucceeded(ChapterId("ch7"))
    check(manager.snapshot().map { it.descriptor.id.value } == listOf("ch3", "ch4", "ch5", "ch6", "ch7"))
    check(released.map { it.id.value } == listOf("ch1", "ch2"))

    // Failed replacement must not evict any currently readable chapter.
    check(manager.onPanelReached(ChapterId("ch7"), 19, true, chapter(8)) == WindowEvent.LoadRequested(chapter(8)))
    check(manager.onLoadFailed(ChapterId("ch8"), "network unavailable") is WindowEvent.LoadFailed)
    check(manager.snapshot().map { it.descriptor.id.value } == listOf("ch3", "ch4", "ch5", "ch6", "ch7"))
    check(released.map { it.id.value } == listOf("ch1", "ch2"))

    println("PASS: end-panel-only preload, newest-chapter guard, five-chapter window, and safe eviction after successful load")
}

package com.comic.xenvyx.id.reader

/** Stable identifier for a chapter in the source catalogue. */
@JvmInline
value class ChapterId(val value: String)

data class ChapterDescriptor(
    val id: ChapterId,
    val ordinal: Long,
    val title: String,
)

enum class ChapterLoadStatus { QUEUED, LOADING, READY, FAILED }

data class ActiveChapter(
    val descriptor: ChapterDescriptor,
    val status: ChapterLoadStatus,
    val lastVisiblePanel: Int = -1,
    val panelCount: Int? = null,
)

sealed interface WindowEvent {
    data class LoadRequested(val chapter: ChapterDescriptor) : WindowEvent
    data class ChapterActivated(val chapter: ChapterDescriptor) : WindowEvent
    data class MemoryReleaseRequested(val chapter: ChapterDescriptor) : WindowEvent
    data class LoadFailed(val chapter: ChapterDescriptor, val reason: String) : WindowEvent
    data object NoOp : WindowEvent
}

/**
 * Keeps a rolling in-memory window of chapters for continuous reading.
 *
 * The next chapter is requested only after [onPanelReached] confirms that the
 * reader has reached the final panel of the newest READY chapter. A successful
 * load is required before an old chapter is evicted.
 *
 * This class never deletes offline files or persistent reading metadata.
 */
class ChapterWindowManager(
    private val maxActiveChapters: Int = 5,
    private val requestChapter: (ChapterDescriptor) -> Unit,
    private val releaseInMemoryResources: (ChapterDescriptor) -> Unit,
) {
    init { require(maxActiveChapters >= 2) }

    private val active = mutableListOf<ActiveChapter>()
    private var pending: ChapterDescriptor? = null
    private var lastFailure: String? = null

    @Synchronized
    fun snapshot(): List<ActiveChapter> = active.toList()

    @Synchronized
    fun pendingChapter(): ChapterDescriptor? = pending

    /** Add a chapter already opened by normal navigation or initial loading. */
    @Synchronized
    fun registerOpened(chapter: ChapterDescriptor, panelCount: Int? = null): WindowEvent {
        if (active.any { it.descriptor.id == chapter.id }) return WindowEvent.NoOp
        active += ActiveChapter(chapter, ChapterLoadStatus.READY, panelCount = panelCount)
        active.sortBy { it.descriptor.ordinal }
        return WindowEvent.ChapterActivated(chapter)
    }

    /**
     * Called by the reader's visibility observer. No loading happens for an
     * intermediate panel. `isFinalPanel` must be based on actual panel count,
     * not a proximity threshold or a scroll percentage estimate.
     */
    @Synchronized
    fun onPanelReached(chapterId: ChapterId, panelIndex: Int, isFinalPanel: Boolean,
                       nextChapter: ChapterDescriptor?): WindowEvent {
        val index = active.indexOfFirst { it.descriptor.id == chapterId }
        if (index < 0) return WindowEvent.NoOp
        val current = active[index]
        active[index] = current.copy(lastVisiblePanel = maxOf(current.lastVisiblePanel, panelIndex))

        // Only the newest chapter in the rolling sequence may trigger its successor.
        // This prevents scrolling back through older chapters from starting duplicate loads.
        if (index != active.lastIndex) return WindowEvent.NoOp
        if (!isFinalPanel || nextChapter == null) return WindowEvent.NoOp
        if (pending != null || active.any { it.descriptor.id == nextChapter.id }) return WindowEvent.NoOp

        pending = nextChapter
        lastFailure = null
        requestChapter(nextChapter)
        return WindowEvent.LoadRequested(nextChapter)
    }

    /** Call only after the next chapter's content and first panel are verified. */
    @Synchronized
    fun onLoadSucceeded(chapterId: ChapterId, panelCount: Int? = null): List<WindowEvent> {
        val chapter = pending?.takeIf { it.id == chapterId } ?: return listOf(WindowEvent.NoOp)
        pending = null
        active += ActiveChapter(chapter, ChapterLoadStatus.READY, panelCount = panelCount)
        active.sortBy { it.descriptor.ordinal }

        val events = mutableListOf<WindowEvent>(WindowEvent.ChapterActivated(chapter))
        while (active.size > maxActiveChapters) {
            val oldest = active.removeAt(0)
            releaseInMemoryResources(oldest.descriptor)
            events += WindowEvent.MemoryReleaseRequested(oldest.descriptor)
        }
        return events
    }

    @Synchronized
    fun onLoadFailed(chapterId: ChapterId, reason: String): WindowEvent {
        val chapter = pending?.takeIf { it.id == chapterId } ?: return WindowEvent.NoOp
        pending = null
        lastFailure = reason
        return WindowEvent.LoadFailed(chapter, reason)
    }

    /** Retry is explicit, so a transient network error cannot cause a tight loop. */
    @Synchronized
    fun retryPending(chapter: ChapterDescriptor): WindowEvent {
        if (pending != null || active.any { it.descriptor.id == chapter.id }) return WindowEvent.NoOp
        pending = chapter
        lastFailure = null
        requestChapter(chapter)
        return WindowEvent.LoadRequested(chapter)
    }

    @Synchronized
    fun lastLoadFailure(): String? = lastFailure
}

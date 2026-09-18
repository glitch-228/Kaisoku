package org.koitharu.kotatsu.reader.ui.novel

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Resolve a character anchor within one layout dispatch, before scroll listeners can save it. */
internal class NovelScrollLayoutManager(
    context: Context,
    private val chapters: () -> List<NovelChapterData>,
) : LinearLayoutManager(context) {
    var pendingAnchor: Pair<Int, Float>? = null
        private set

    fun restoreChapter(index: Int, ratio: Float) {
        pendingAnchor = index to ratio
        requestLayout()
    }

    fun clearPendingAnchor() {
        pendingAnchor = null
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.isPreLayout) {
            super.onLayoutChildren(recycler, state)
            return
        }
        val first = findFirstVisibleItemPosition()
        val visible = findViewByPosition(first) as? NovelChapterView
        // Width changes (rotation/resizing) reflow text; capture before child measurement.
        val anchor = pendingAnchor ?: if (visible != null && visible.width != width - paddingLeft - paddingRight) {
            chapters().getOrNull(first)?.let { it.chapterIndex to visible.progressAt(paddingTop - visible.top) }
        } else null
        val position = anchor?.let { a -> chapters().indexOfFirst { it.chapterIndex == a.first } } ?: -1
        if (position >= 0) scrollToPositionWithOffset(position, 0)
        super.onLayoutChildren(recycler, state)
        if (anchor != null && position >= 0) {
            val view = findViewByPosition(position) as? NovelChapterView
            if (view != null) {
                scrollToPositionWithOffset(position, -view.offsetForProgress(anchor.second))
                super.onLayoutChildren(recycler, state)
                pendingAnchor = null
            }
        }
    }
}

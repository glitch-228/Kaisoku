package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.chapters.ChapterGridSpanHelper
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.UUID

class InlineChaptersTest {
    @Test fun settingIsOffInitiallyAndRecreationPreservesItsIndependentValue() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "inline-chapters-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        try {
            assertFalse(AppSettings(context).isDetailsChaptersInline)
            prefs.edit().putBoolean(AppSettings.KEY_DETAILS_CHAPTERS_INLINE, true)
                .putBoolean(AppSettings.KEY_PAGES_TAB, false).commit()
            assertTrue(AppSettings(context).isDetailsChaptersInline)
            assertFalse(AppSettings(context).isPagesTabEnabled)
            prefs.edit().putString(AppSettings.KEY_DETAILS_CHAPTERS_INLINE, "invalid").commit()
            assertFalse(AppSettings(context).isDetailsChaptersInline)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun fiveThousandChaptersStayVirtualizedWithRealRowsAndFullWidthVolumeHeaders() = onMain {
        val context = themedContext()
        val chapters = ChaptersAdapter(clicks)
        val rv = create(context, chapters)
        chapters.items = items().withVolumeHeaders(context)
        val grid = GridLayoutManager(context, 3).apply {
            spanSizeLookup = ChapterGridSpanHelper.SpanSizeLookup(rv, InlineChaptersAdapter.headerTypes)
        }
        rv.layoutManager = grid
        size(rv)
        assertTrue(rv.adapter!!.itemCount > 5000)
        assertTrue("Chapter views must remain bounded", rv.childCount in 1..79)
        for (position in 0..2) assertEquals(3, grid.spanSizeLookup.getSpanSize(position))
        assertEquals(ListItemType.HEADER.ordinal, rv.adapter!!.getItemViewType(2))
        assertEquals(1, grid.spanSizeLookup.getSpanSize(3))
        grid.scrollToPositionWithOffset(4100, 0)
        size(rv)
        assertTrue(rv.childCount < 80)
        grid.scrollToPositionWithOffset(0, 0)
        size(rv) // Recycle/reattach both fixed headers; preserve their actual controls.
        assertEquals(InlineChaptersAdapter.METADATA_TYPE, rv.getChildViewHolder(rv.getChildAt(0)).itemViewType)
        rv.adapter = null
    }

    @Test fun headersDoNotConsumeSavedScrollBeforeDelayedChapterDataArrives() = onMain {
        val context = themedContext()
        val first = ChaptersAdapter(clicks)
        val rv = create(context, first)
        first.items = items()
        first.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
        size(rv)
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(4000, 13)
        size(rv)
        val originalManager = rv.layoutManager as LinearLayoutManager
        val originalFirstVisible = originalManager.findFirstVisibleItemPosition()
        assertEquals(13, originalManager.findViewByPosition(4000)!!.top)
        val state = SparseArray<Parcelable>()
        rv.saveHierarchyState(state)
        val second = ChaptersAdapter(clicks).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT
        }
        val restored = create(context, second)
        restored.restoreHierarchyState(state)
        size(restored)
        assertEquals(2, restored.adapter!!.itemCount)
        assertEquals(RecyclerView.Adapter.StateRestorationPolicy.PREVENT, restored.adapter!!.stateRestorationPolicy)
        second.items = items()
        second.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
        size(restored)
        val manager = restored.layoutManager as LinearLayoutManager
        // The positive offset intentionally leaves the preceding row partly visible.
        assertEquals(originalFirstVisible, manager.findFirstVisibleItemPosition())
        assertEquals(4000, manager.findFirstCompletelyVisibleItemPosition())
        assertEquals(13, manager.findViewByPosition(4000)!!.top)
        rv.adapter = null
        restored.adapter = null
    }

    private fun create(context: Context, chapters: ChaptersAdapter) = RecyclerView(context).apply {
        id = R.id.recycler_details_inline
        itemAnimator = null
        layoutManager = LinearLayoutManager(context)
        adapter = InlineChaptersAdapter.create(TextView(context).apply {
            text = "Saved title metadata"
            minimumHeight = 250
        }, TextView(context).apply { text = "Chapters"; minimumHeight = 80 }, chapters)
    }

    private fun items() = (1..5000).map { i ->
        ChapterListItem(MangaChapter(id = i.toLong(), title = "Chapter $i", number = i.toFloat(), volume = i / 100 + 1,
            url = "https://fixture.invalid/chapter/$i", scanlator = null, uploadDate = 0L, branch = "Fixture",
            source = MangaParserSource.MANGADEX), ChapterListItem.FLAG_GRID)
    }

    private val clicks = object : OnListItemClickListener<ChapterListItem> {
        override fun onItemClick(item: ChapterListItem, view: View) = Unit
        override fun onItemLongClick(item: ChapterListItem, view: View) = false
    }
    private fun themedContext() = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.Theme_Kotatsu)
    private fun size(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1200)
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

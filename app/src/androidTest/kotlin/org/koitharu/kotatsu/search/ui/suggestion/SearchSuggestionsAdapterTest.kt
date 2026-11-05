package org.koitharu.kotatsu.search.ui.suggestion

import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchTrackerAccount
import org.koitharu.kotatsu.search.ui.suggestion.adapter.SEARCH_SUGGESTION_ITEM_TYPE_QUERY
import org.koitharu.kotatsu.search.ui.suggestion.adapter.SearchSuggestionAdapter
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem
import org.koitharu.kotatsu.search.unified.CatalogueTitle
import org.koitharu.kotatsu.search.unified.TrackerPreviewAdapter
import org.koitharu.kotatsu.search.unified.TrackerSearchCard

/** Exercise the outer adapter, since testing each child alone misses ConcatAdapter's type remapping. */
class SearchSuggestionsAdapterTest {

    @Test fun defaultIsolationReproducesTheReportedWrongHolderCast() = onMain {
        val suggestions = suggestions(listOf(text))
        val broken = ConcatAdapter(preview(), suggestions)
        val holder = broken.createViewHolder(parent(), broken.getItemViewType(0))
        try {
            broken.bindViewHolder(holder, 0)
            fail("Default isolation must reproduce the incompatible AdapterDelegates dispatch")
        } catch (expected: ClassCastException) {
            assertTrue(expected.message.orEmpty().contains("AdapterDelegateViewBindingViewHolder"))
        }
    }

    @Test fun emptyPreviewBindsNativeAndViewBindingRowsInEveryStartingOrder() = onMain {
        val rows = listOf(text, query, SearchSuggestionItem.Hint("hint"), SearchSuggestionItem.Author("author"),
            SearchSuggestionItem.MangaList(emptyList()))
        for (offset in rows.indices) {
            val ordered = rows.drop(offset) + rows.take(offset)
            val child = suggestions(ordered)
            val combined = preview().withSuggestions(child)
            val types = ordered.indices.map(combined::getItemViewType)
            assertEquals(types.size, types.distinct().size)
            for (position in ordered.indices) {
                assertEquals(child.getItemViewType(position), types[position])
                val holder = combined.createViewHolder(parent(), types[position])
                combined.bindViewHolder(holder, position)
                assertEquals(ordered[position], holder.getItem(SearchSuggestionItem::class.java))
                combined.onViewAttachedToWindow(holder)
                combined.onViewDetachedFromWindow(holder)
                combined.onViewRecycled(holder)
            }
        }
    }

    @Test fun allSuggestionTypesRemainDistinctFromTheTrackerPreview() = onMain {
        val rows = listOf(text, query, SearchSuggestionItem.Hint("hint"), SearchSuggestionItem.Author("author"),
            SearchSuggestionItem.Tags(emptyList()), SearchSuggestionItem.MangaList(emptyList()),
            SearchSuggestionItem.Source(MangaParserSource.MANGADEX, false),
            SearchSuggestionItem.SourceTip(MangaParserSource.MANGADEX))
        val child = suggestions(rows)
        val combined = preview().apply { submit(listOf(card)) }.withSuggestions(child)
        val types = (0 until combined.itemCount).map(combined::getItemViewType)
        assertEquals(rows.size + 1, types.distinct().size)
        for (position in rows.indices) assertEquals(child.getItemViewType(position), types[position + 1])
    }

    @Test fun previewInsertionRemovalAndRecyclingPreserveSuggestionTypes() = onMain {
        val child = suggestions(listOf(text, query))
        val preview = preview()
        val combined = preview.withSuggestions(child)
        val holder = combined.createViewHolder(parent(), combined.getItemViewType(0))
        combined.bindViewHolder(holder, 0)
        combined.onViewRecycled(holder)
        repeat(3) {
            preview.submit(listOf(card))
            assertEquals(3, combined.itemCount)
            assertTrue((0 until child.itemCount).none { child.getItemViewType(it) == combined.getItemViewType(0) })
            val previewHolder = combined.createViewHolder(parent(), combined.getItemViewType(0))
            combined.bindViewHolder(previewHolder, 0)
            combined.onViewAttachedToWindow(previewHolder)
            combined.onViewDetachedFromWindow(previewHolder)
            combined.onViewRecycled(previewHolder)
            assertEquals(child.getItemViewType(0), combined.getItemViewType(1))
            combined.bindViewHolder(holder, 1)
            assertEquals(text, holder.getItem(SearchSuggestionItem::class.java))
            combined.onViewRecycled(holder)
            preview.submit(emptyList())
            assertEquals(2, combined.itemCount)
            combined.bindViewHolder(holder, 0)
            assertEquals(text, holder.getItem(SearchSuggestionItem::class.java))
            combined.onViewRecycled(holder)
        }
    }

    @Test fun previewNotificationsKeepCorrectOffsetsWithoutResettingTheWholeList() = onMain {
        val preview = preview()
        val combined = preview.withSuggestions(suggestions(listOf(text, query)))
        val notifications = mutableListOf<String>()
        combined.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fail("Preview updates must retain incremental notifications") }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                notifications += "insert:$positionStart:$itemCount"
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                notifications += "change:$positionStart:$itemCount"
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                notifications += "remove:$positionStart:$itemCount"
            }
        })
        preview.submit(listOf(card))
        preview.submit(listOf(card.copy(work = card.work.copy(title = "Updated title"))))
        preview.submit(emptyList())
        assertEquals(listOf("insert:0:1", "change:0:1", "remove:0:1"), notifications)
    }

    @Test fun mixedSearchRowsStayVirtualizedAndRecycleWhileScrolling() = onMain {
        val rows = (0 until 200).map {
            if (it % 5 == 0) text else SearchSuggestionItem.RecentQuery("Query $it")
        }
        val view = RecyclerView(parent().context)
        val manager = LinearLayoutManager(view.context)
        view.layoutManager = manager
        view.itemAnimator = null
        view.adapter = preview().withSuggestions(suggestions(rows))
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 600, 800)
            assertTrue("Search rows must remain virtualized", view.childCount in 1..39)
        }
        layout()
        manager.scrollToPositionWithOffset(140, 0)
        layout()
        manager.scrollToPositionWithOffset(0, 0)
        layout()
        assertEquals(text, view.findViewHolderForAdapterPosition(0)!!.getItem(SearchSuggestionItem::class.java))
        view.adapter = null
    }

    @Test fun onlyRecentQueriesCanBeSwipedAndDeletionUsesTheBoundQuery() = onMain {
        val preview = preview().apply { submit(listOf(card)) }
        val combined = preview.withSuggestions(suggestions(listOf(text, query)))
        val removed = mutableListOf<String>()
        val callback = SearchSuggestionItemCallback(object : SearchSuggestionItemCallback.SuggestionItemListener {
            override fun onRemoveQuery(query: String) { removed += query }
        })
        val parent = parent()
        for (position in 0 until combined.itemCount) {
            val holder = combined.createViewHolder(parent, combined.getItemViewType(position))
            combined.bindViewHolder(holder, position)
            val flags = callback.getMovementFlags(RecyclerView(parent.context), holder)
            if (position == 2) {
                assertEquals(SEARCH_SUGGESTION_ITEM_TYPE_QUERY, holder.itemViewType)
                assertNotEquals(0, flags)
                holder.itemView.performClick()
                callback.onSwiped(holder, ItemTouchHelper.LEFT)
            } else {
                assertEquals(0, flags)
            }
            combined.onViewRecycled(holder)
        }
        assertEquals(listOf(query.query), removed)
        assertEquals(listOf(query.query), listener.clicked)
    }

    private val text = SearchSuggestionItem.Text(R.string.search_manga, null)
    private val query = SearchSuggestionItem.RecentQuery("Saved query")
    private val card = TrackerSearchCard(SearchTrackerAccount("ANILIST", "fixture", 0, 1),
        CatalogueTitle("opaque-id", "Cached tracker title", url = "https://example.invalid/title"))
    private val listener = Listener()

    private fun suggestions(rows: List<SearchSuggestionItem>) = SearchSuggestionAdapter(listener).apply { items = rows }
    private fun preview() = TrackerPreviewAdapter(object : ItemSizeResolver {
        override val cellWidth = 160
        override fun attachToView(view: View, textView: TextView?, progressView: ReadingProgressView?) = Unit
    }) {}
    private fun parent() = FrameLayout(ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Kotatsu))
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Listener : SearchSuggestionListener {
        val clicked = mutableListOf<String>()
        override fun onQueryClick(query: String, kind: SearchKind, submit: Boolean) { clicked += query }
        override fun onMangaClick(manga: Manga) = Unit
        override fun onSourceToggle(source: MangaSource, isEnabled: Boolean) = Unit
        override fun onSourceClick(source: MangaSource) = Unit
        override fun onTagClick(tag: MangaTag) = Unit
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = Unit
        override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?) = false
    }
}

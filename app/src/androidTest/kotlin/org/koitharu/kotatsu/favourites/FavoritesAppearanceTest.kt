package org.koitharu.kotatsu.favourites

import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.list.ListLongPressTouchListener
import org.koitharu.kotatsu.list.ui.GridSpanResolver

class FavoritesAppearanceTest {
    @Test fun newAppearanceFeaturesAreOptInAndSingleColumnIsPersisted() {
        val context = isolatedContext()
        val settings = AppSettings(context)
        assertFalse(settings.isFavoritesOverviewEnabled)
        assertFalse(settings.isSearchFavoriteCategoriesEnabled)
        assertEquals(0, settings.listLongPressDelay)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(AppSettings.KEY_FAVORITES_OVERVIEW, true)
            .putBoolean(AppSettings.KEY_SEARCH_FAVORITE_CATEGORIES, true)
            .putInt(AppSettings.KEY_GRID_COLUMNS, 1).commit()
        val recreated = AppSettings(context)
        assertTrue(recreated.isFavoritesOverviewEnabled)
        assertTrue(recreated.isSearchFavoriteCategoriesEnabled)
        assertEquals(1, recreated.gridColumns)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = RecyclerView(context)
            val layout = GridLayoutManager(context, 3)
            view.layoutManager = layout
            view.layout(0, 0, 1080, 1000)
            val resolver = GridSpanResolver(context.resources)
            resolver.setGridSize(1f, view, 1)
            assertEquals(1, layout.spanCount)
            resolver.setGridSize(1f, view, 0)
            assertTrue(layout.spanCount >= 2)
        }
    }

    @Test fun fixedColumnsApplyBeforeMeasurementAndAdaptiveColumnsFollowLaterLayout() {
        val context = isolatedContext()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = RecyclerView(context)
            val layout = GridLayoutManager(context, 3)
            view.layoutManager = layout
            val resolver = GridSpanResolver(context.resources)
            resolver.setGridSize(1f, view, 1)
            assertEquals(1, layout.spanCount)
            view.layout(0, 0, 1080, 1000)
            resolver.setGridSize(1f, view)
            assertTrue(layout.spanCount >= 2)
            resolver.setGridSize(1f, view, 5)
            resolver.onLayoutChange(view, 0, 0, 720, 1000, 0, 0, 1080, 1000)
            assertEquals(5, layout.spanCount)
            assertEquals(5, resolver.spanCount)
        }
    }

    @Test fun customLongPressCancelsClicksOnScrollAndRebindingWhileSystemTimingIsUntouched() {
        val context = isolatedContext()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = View(context)
            var identity = 1
            var clicks = 0
            view.setOnClickListener { clicks++ }
            val touch = ListLongPressTouchListener { identity }
            fun event(action: Int, x: Float = 0f) = MotionEvent.obtain(0, 0, action, x, 0f, 0).also { event ->
                try { touch.onTouch(view, event) } finally { event.recycle() }
            }
            val system = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            try { assertFalse(touch.onTouch(view, system)) } finally { system.recycle() }
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(AppSettings.KEY_LIST_LONG_PRESS_DELAY, "1500").commit()
            event(MotionEvent.ACTION_DOWN)
            event(MotionEvent.ACTION_MOVE, 1000f)
            event(MotionEvent.ACTION_UP)
            assertEquals(0, clicks)
            event(MotionEvent.ACTION_DOWN)
            identity = 2
            event(MotionEvent.ACTION_UP)
            assertEquals(0, clicks)
            event(MotionEvent.ACTION_DOWN)
            event(MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            touch.onViewDetachedFromWindow(view)
        }
    }

    private fun isolatedContext(): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "favorites-appearance-${System.nanoTime()}-"
        return object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
    }
}

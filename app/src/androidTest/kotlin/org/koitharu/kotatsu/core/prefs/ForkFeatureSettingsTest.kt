package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.ContextWrapper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.reader.domain.TapGridArea

@RunWith(AndroidJUnit4::class)
class ForkFeatureSettingsTest {
    @Test fun defaultsPreserveReaderAndAppearanceAndOverridesStayIndependent() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("feature-test-$name", mode)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        try {
            val settings = AppSettings(context)
            assertTrue(settings.isFavoritesCategoryStripVisible); assertTrue(settings.isNewChapterCounterVisible)
            assertFalse(settings.isHoldScrollEnabled); assertFalse(settings.isFixedGridColumnsEnabled)
            assertEquals(0, settings.widePageRotation); assertFalse(settings.isLocalDiagnosticsEnabled)
            settings.setUsesDefaultFavoritesOrder(1, true)
            assertTrue(settings.usesDefaultFavoritesOrder(1)); assertFalse(settings.usesDefaultFavoritesOrder(2))
            settings.setUsesDefaultFavoritesOrder(1, false)
            assertFalse(settings.usesDefaultFavoritesOrder(1))
            settings.gridColumns = 99; assertEquals(12, settings.gridColumns)
            settings.gridColumns = -1; assertEquals(1, settings.gridColumns)
            prefs.edit().putBoolean(AppSettings.KEY_NEW_CHAPTER_COUNTER, false).putString(AppSettings.KEY_WIDE_PAGE_ROTATION, "270").commit()
            assertTrue(settings.isFavoritesCategoryStripVisible); assertFalse(settings.isNewChapterCounterVisible)
            assertEquals(270, settings.widePageRotation)
            assertEquals(-1, settings.holdScrollDirection(TapGridArea.TOP_CENTER))
            assertEquals(1, settings.holdScrollDirection(TapGridArea.CENTER_RIGHT))
            assertEquals(0, settings.holdScrollDirection(TapGridArea.CENTER))
            prefs.edit().putString(AppSettings.KEY_HOLD_SCROLL_TOP, "1").commit()
            assertEquals(1, settings.holdScrollDirection(TapGridArea.TOP_LEFT))
            assertEquals(1, settings.holdScrollDirection(TapGridArea.TOP_RIGHT))
        } finally { prefs.edit().clear().commit() }
    }
}

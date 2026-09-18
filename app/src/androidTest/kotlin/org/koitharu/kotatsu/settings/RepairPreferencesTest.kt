package org.koitharu.kotatsu.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderStorage
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.sources.ExtensionLanguageFilter
import java.util.UUID

class RepairPreferencesTest {
    @Test fun novelReadingDirectionPersistsPerSourceAndCanBeReset() = isolated { context ->
        val one = org.koitharu.kotatsu.core.model.MangaSource("lnreader:order-one")
        val two = org.koitharu.kotatsu.core.model.MangaSource("lnreader:order-two")
        val settings = org.koitharu.kotatsu.core.prefs.SourceSettings(context, one)
        assertFalse(settings.isNovelReadingReversed)
        settings.isNovelReadingReversed = true
        val reopened = org.koitharu.kotatsu.core.prefs.SourceSettings(context, one)
        assertTrue(reopened.isNovelReadingReversed)
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, two).isNovelReadingReversed)
        reopened.isNovelReadingReversed = false
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, one).isNovelReadingReversed)
    }

    private inline fun isolated(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "repair-test-" + UUID.randomUUID()
        val names = HashSet<String>()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                names.add(isolatedName)
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        try { block(context) } finally { names.forEach(app::deleteSharedPreferences) }
    }

    @Test fun languageSelectionsPersistSeparatelyAcrossRecreation() = isolated { context ->
        val mihon = ExtensionLanguageFilter(context, "mihon")
        val novels = ExtensionLanguageFilter(context, "lnreader")
        assertTrue(mihon.selected.value.isEmpty())
        assertTrue(novels.selected.value.isEmpty())
        mihon.select(setOf("Русский", "EN"))
        novels.select(setOf("Japanese"))
        assertEquals(setOf("ru", "en"), ExtensionLanguageFilter(context, "mihon").selected.value)
        assertEquals(setOf("ja"), ExtensionLanguageFilter(context, "lnreader").selected.value)
        mihon.select(emptySet())
        assertTrue(ExtensionLanguageFilter(context, "mihon").selected.value.isEmpty())
        assertEquals(setOf("ja"), ExtensionLanguageFilter(context, "lnreader").selected.value)
    }

    @Test fun upscalerConfigurationPersistsAndClampsOutOfRangeValues() = isolated { context ->
        val settings = AppSettings(context)
        assertEquals(75, settings.readerUpscaleStrength)
        assertEquals(0, settings.readerUpscalePasses)
        assertEquals(1.5f, settings.readerUpscaleThreshold, 0f)
        settings.isReaderUpscaleEnabled = true
        settings.readerUpscaleStrength = 50
        settings.readerUpscalePasses = 3
        settings.readerUpscaleThreshold = 2f
        val restored = AppSettings(context)
        assertTrue(restored.isReaderUpscaleEnabled)
        assertEquals(50, restored.readerUpscaleStrength)
        assertEquals(3, restored.readerUpscalePasses)
        assertEquals(2f, restored.readerUpscaleThreshold, 0f)
        restored.readerUpscaleStrength = 150
        restored.readerUpscalePasses = -1
        assertEquals(100, restored.readerUpscaleStrength)
        assertEquals(0, restored.readerUpscalePasses)
    }

    @Test fun pluginStoragePersistsWithoutCrossPluginValues() = isolated { context ->
        val one = context.getSharedPreferences("plugin-one", Context.MODE_PRIVATE)
        val two = context.getSharedPreferences("plugin-two", Context.MODE_PRIVATE)
        LNReaderStorage(one).set("plugin:book", "{\"id\":7}")
        assertEquals("{\"id\":7}", LNReaderStorage(one).get("plugin:book"))
        assertNull(LNReaderStorage(two).get("plugin:book"))
        LNReaderStorage(one).set("plugin:book", null)
        assertTrue(LNReaderStorage(one).keys().isEmpty())
    }
}

package org.koitharu.kotatsu.settings.sources.mihon

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.ContextThemeWrapper
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.R
import java.util.UUID

class MihonPreferencesTest {
    @SuppressLint("RestrictedApi")
    @Test fun nativeWidgetsUseTheExactSourceStoreAndPreserveCallbacksAndNestedKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val filename = "mihon-preferences-fixture-${UUID.randomUUID()}"
        val otherFilename = "$filename-other"
        try {
            val preferences = app.getSharedPreferences(filename, Context.MODE_PRIVATE)
            val other = app.getSharedPreferences(otherFilename, Context.MODE_PRIVATE)
            preferences.edit().putString("text", "existing").putBoolean("enabled", true).commit()
            other.edit().putString("text", "unrelated").commit()
            instrumentation.runOnMainSync {
                val context = ContextThemeWrapper(app, R.style.Theme_Kotatsu)
                val source = Fixture(preferences)
                val store = SourcePreferenceDataStore(source.getSourcePreferences())
                assertEquals(emptySet<String>(), store.getStringSet("languages", null))
                assertEquals(setOf("en"), store.getStringSet("missing_default", setOf("en")))
                assertFalse(preferences.contains("languages"))
                assertFalse(preferences.contains("missing_default"))
                val manager = PreferenceManager(context).apply {
                    sharedPreferencesName = source.preferenceKey()
                    preferenceDataStore = store
                }
                val screen = manager.createPreferenceScreen(context)
                manager.setPreferences(screen)
                source.setupPreferenceScreen(screen)
                assertEquals(emptySet<String>(), screen.findPreference<MultiSelectListPreference>("languages")!!.values)
                assertFalse("Opening an absent empty selection must not persist a new value", preferences.contains("languages"))
                assertEquals("existing", screen.findPreference<EditTextPreference>("text")!!.text)
                assertTrue(screen.findPreference<CheckBoxPreference>("enabled")!!.isChecked)
                val text = screen.findPreference<EditTextPreference>("text")!!
                assertFalse(text.callChangeListener("reject"))
                assertEquals("existing", preferences.getString("text", null))
                assertTrue(text.callChangeListener("accepted"))
                text.text = "accepted"
                screen.findPreference<MultiSelectListPreference>("languages")!!.values = setOf("en", "ru")
                screen.findPreference<CheckBoxPreference>("enabled")!!.isChecked = false
                val action = screen.findPreference<Preference>("callback")!!
                assertTrue(action.onPreferenceClickListener!!.onPreferenceClick(action))
                assertEquals(1, source.clicks)
                assertEquals(2, source.changes)
                val nested = screen.findPreference<PreferenceScreen>("nested")!!
                assertNotNull(nested.findPreference<EditTextPreference>("nested_text"))
                assertEquals("accepted", preferences.getString("text", null))
                assertEquals(setOf("en", "ru"), preferences.getStringSet("languages", null))
                assertFalse(preferences.getBoolean("enabled", true))
                assertEquals("unrelated", other.getString("text", null))
                assertSame(preferences, source.getSourcePreferences())
                // Rebuilding a new native screen reads the same values instead of reseeding defaults.
                val recreated = manager.createPreferenceScreen(context)
                source.setupPreferenceScreen(recreated)
                assertEquals("accepted", recreated.findPreference<EditTextPreference>("text")!!.text)
                assertFalse(recreated.findPreference<CheckBoxPreference>("enabled")!!.isChecked)
            }
        } finally {
            app.deleteSharedPreferences(filename)
            app.deleteSharedPreferences(otherFilename)
        }
    }

    private class Fixture(private val preferences: SharedPreferences) : ConfigurableSource {
        override val id = 877191L
        override val name = "Settings fixture"
        var clicks = 0
        var changes = 0
        override fun getSourcePreferences() = preferences
        override fun setupPreferenceScreen(screen: PreferenceScreen) {
            screen.removeAll()
            screen.addPreference(EditTextPreference(screen.context).apply {
                key = "text"
                setOnPreferenceChangeListener { _, value -> changes++; value != "reject" }
            })
            screen.addPreference(CheckBoxPreference(screen.context).apply { key = "enabled" })
            screen.addPreference(MultiSelectListPreference(screen.context).apply {
                key = "languages"
                entries = arrayOf("English", "Russian")
                entryValues = arrayOf("en", "ru")
            })
            screen.addPreference(Preference(screen.context).apply {
                key = "callback"
                setOnPreferenceClickListener { clicks++; true }
            })
            screen.addPreference(screen.preferenceManager.createPreferenceScreen(screen.context).apply {
                key = "nested"
                addPreference(EditTextPreference(context).apply { key = "nested_text" })
            })
        }
    }
}

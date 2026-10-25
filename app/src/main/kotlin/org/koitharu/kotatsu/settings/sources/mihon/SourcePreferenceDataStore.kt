package org.koitharu.kotatsu.settings.sources.mihon

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore

/** Forward AndroidX widgets to the extension's actual store without copying or renaming keys. */
internal class SourcePreferenceDataStore(private val preferences: SharedPreferences) : PreferenceDataStore() {
    override fun putString(key: String, value: String?) { preferences.edit { putString(key, value) } }
    override fun putStringSet(key: String, values: Set<String>?) {
        preferences.edit { putStringSet(key, values?.toSet()) }
    }
    override fun putInt(key: String, value: Int) { preferences.edit { putInt(key, value) } }
    override fun putLong(key: String, value: Long) { preferences.edit { putLong(key, value) } }
    override fun putFloat(key: String, value: Float) { preferences.edit { putFloat(key, value) } }
    override fun putBoolean(key: String, value: Boolean) { preferences.edit { putBoolean(key, value) } }
    override fun getString(key: String, defValue: String?): String? = preferences.getString(key, defValue)
    // AndroidX MultiSelectListPreference cannot initialize from null when the source omits a default.
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String> =
        preferences.getStringSet(key, defValues.orEmpty()).orEmpty().toSet()
    override fun getInt(key: String, defValue: Int): Int = preferences.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = preferences.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = preferences.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = preferences.getBoolean(key, defValue)
}

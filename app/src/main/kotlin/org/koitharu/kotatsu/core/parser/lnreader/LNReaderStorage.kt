package org.koitharu.kotatsu.core.parser.lnreader

import android.content.SharedPreferences

/** Values are JSON strings, namespaced by plugin in the repository factory. */
class LNReaderStorage(private val preferences: SharedPreferences? = null) {
	private val values = HashMap<String, String>()

	@Synchronized
	fun get(key: String): String? = preferences?.getString(key, null) ?: values[key]

	@Synchronized
	fun keys(): Set<String> = (preferences?.all?.keys ?: values.keys).toSet()

	@Synchronized
	fun set(key: String, value: String?) {
		if (preferences != null) {
			preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
		} else if (value == null) {
			values.remove(key)
		} else {
			values[key] = value
		}
	}
}

package org.koitharu.kotatsu.settings.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import java.util.Locale

class ExtensionLanguageFilter(context: Context, ecosystem: String) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val key = "extension_languages_$ecosystem"
	private val mutableSelected = MutableStateFlow(prefs.getStringSet(key, emptySet()).orEmpty().toSet())
	val selected = mutableSelected.asStateFlow()

	fun select(languages: Set<String>) {
		val normalized = languages.map(::normalize).toSet()
		prefs.edit().putStringSet(key, normalized).apply()
		mutableSelected.value = normalized
	}

	fun showDialog(context: Context, available: List<String>) {
		val choices = (available + selected.value).distinct().sortedBy(::label)
		val pending = selected.value.toMutableSet()
		MaterialAlertDialogBuilder(context).setTitle(R.string.languages)
			.setMultiChoiceItems(choices.map(::label).toTypedArray(), BooleanArray(choices.size) { choices[it] in pending }) { _, index, checked ->
				if (checked) pending.add(choices[index]) else pending.remove(choices[index])
			}
			.setPositiveButton(android.R.string.ok) { _, _ -> select(pending) }
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.all_languages) { _, _ -> select(emptySet()) }
			.show()
	}

	companion object {
		private val aliases by lazy {
			buildMap {
				for (code in Locale.getISOLanguages()) {
					val locale = Locale.forLanguageTag(code)
					put(code, code)
					put(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT), code)
					put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
				}
				put("русский", "ru")
				put("bahasa indonesia", "id")
				put("in", "id")
				put("iw", "he")
			}
		}

		fun normalize(value: String): String {
			val raw = value.trim().lowercase(Locale.ROOT).replace('_', '-')
			return aliases[raw] ?: raw.ifEmpty { "und" }
		}

		fun matches(selected: Set<String>, languages: List<String>): Boolean =
			selected.isEmpty() || languages.any { normalize(it) in selected }

		fun label(code: String): String = when (code) {
			"all" -> "Multilingual"
			"und" -> "Unknown"
			else -> Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).takeUnless { it.isBlank() } ?: code
		}
	}
}

package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.putEnumValue
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.settings.utils.validation.DomainValidator
import java.io.File

class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {

    private val prefs = context.getSharedPreferences(
        prefsName(source),
        Context.MODE_PRIVATE,
    )

    init {
        val newName = prefsName(source)
        listOf(source.name, "plugin.jar:$newName")
            .filter { it != newName }
            .forEach { legacyName ->
                val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
                if (legacy.all.isEmpty()) {
                    return@forEach
                }
                prefs.edit(commit = true) {
                    legacy.all.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                putStringSet(key, value as Set<String>)
                            }
                        }
                    }
                }
                legacy.edit(commit = true) { clear() }
            }
    }

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

	val isSlowdownEnabled: Boolean
		get() = prefs.getBoolean(KEY_SLOWDOWN, false)

	var isNovelReadingReversed: Boolean
		get() = prefs.getBoolean(KEY_NOVEL_REVERSE_READING, false)
		set(value) = prefs.edit { putBoolean(KEY_NOVEL_REVERSE_READING, value) }

	val isCaptchaNotificationsDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

	val isCaptchaAutoResolveDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_AUTO_CAPTCHA, false)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		return when (key) {
			is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
				?.trim()
				?.takeIf { DomainValidator.isValidDomain(it) }
				?: key.defaultValue

			is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
			is ConfigKey.DisableUpdateChecking -> prefs.getBoolean(key.key, key.defaultValue)
            is ConfigKey.InterceptCloudflare -> prefs.getBoolean(key.key, key.defaultValue)
		} as T
	}

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit(commit = true) {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String?)
            is ConfigKey.InterceptCloudflare -> putBoolean(key.key, value as Boolean)
			is ConfigKey.DisableUpdateChecking -> {
				// Read-only - parser-controlled only, users cannot change this
			}
		}
	}

	fun subscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	companion object {

		const val KEY_DOMAIN = "domain"
		const val KEY_NO_CAPTCHA = "no_captcha"
		const val KEY_NO_AUTO_CAPTCHA = "no_auto_captcha"
		const val KEY_SLOWDOWN = "slowdown"
		const val KEY_SORT_ORDER = "sort_order"
		const val KEY_NOVEL_REVERSE_READING = "novel_reverse_reading"

        fun prefsName(source: MangaSource): String {
            return source.name.substringAfter(':').replace(File.separatorChar, '$')
        }
	}
}

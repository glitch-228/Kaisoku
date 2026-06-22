package org.koitharu.kotatsu.settings

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.EInkFlashColor
import org.koitharu.kotatsu.reader.translate.TranslateProvider
import org.koitharu.kotatsu.reader.translate.TranslationCoordinator
import org.koitharu.kotatsu.core.prefs.ReaderAnimation
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.utils.MultiSummaryProvider
import org.koitharu.kotatsu.settings.utils.PercentSummaryProvider
import org.koitharu.kotatsu.settings.utils.SliderPreference

@AndroidEntryPoint
class ReaderSettingsFragment :
	BasePreferenceFragment(R.string.reader_settings),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var translationCoordinator: TranslationCoordinator

	private val cacheScope = MainScope()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_reader)
		findPreference<ListPreference>(AppSettings.KEY_READER_MODE)?.run {
			entryValues = ReaderMode.entries.names()
			setDefaultValueCompat(ReaderMode.STANDARD.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_READER_ORIENTATION)?.run {
			entryValues = arrayOf(
				ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.toString(),
				ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR.toString(),
				ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT.toString(),
				ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE.toString(),
			)
			setDefaultValueCompat(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.toString())
		}
		findPreference<MultiSelectListPreference>(AppSettings.KEY_READER_CONTROLS)?.run {
			entryValues = ReaderControl.entries.names()
			setDefaultValueCompat(ReaderControl.DEFAULT.mapToSet { it.name })
			summaryProvider = MultiSummaryProvider(R.string.none)
		}
		findPreference<ListPreference>(AppSettings.KEY_READER_BACKGROUND)?.run {
			entryValues = ReaderBackground.entries.names()
			setDefaultValueCompat(ReaderBackground.DEFAULT.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_READER_ANIMATION)?.run {
			entryValues = ReaderAnimation.entries.names()
			setDefaultValueCompat(ReaderAnimation.DEFAULT.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_ZOOM_MODE)?.run {
			entryValues = ZoomMode.entries.names()
			setDefaultValueCompat(ZoomMode.FIT_CENTER.name)
		}
		findPreference<MultiSelectListPreference>(AppSettings.KEY_READER_CROP)?.run {
			summaryProvider = MultiSummaryProvider(R.string.disabled)
		}
		findPreference<SliderPreference>(AppSettings.KEY_WEBTOON_ZOOM_OUT)?.summaryProvider = PercentSummaryProvider()
		findPreference<SliderPreference>(AppSettings.KEY_EINK_FLASH_DURATION)?.summaryProvider =
			FlashDurationSummaryProvider
		findPreference<SliderPreference>(AppSettings.KEY_EINK_FLASH_EVERY)?.summaryProvider =
			FlashEverySummaryProvider
		findPreference<ListPreference>(AppSettings.KEY_EINK_FLASH_COLOR)?.run {
			entries = arrayOf(
				getString(R.string.color_white),
				getString(R.string.color_black),
			)
			entryValues = EInkFlashColor.entries.names()
			setDefaultValueCompat(EInkFlashColor.WHITE.name)
		}
		updateReaderModeDependency()
		updateTranslateDependencies()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_READER_TAP_ACTIONS -> {
				router.openReaderTapGridSettings()
				true
			}

			AppSettings.KEY_TRANSLATE_CLEAR_CACHE -> {
				cacheScope.launch { translationCoordinator.clearAll() }
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	override fun onDestroy() {
		cacheScope.cancel()
		super.onDestroy()
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_READER_MODE -> updateReaderModeDependency()
			AppSettings.KEY_TRANSLATE_PROVIDER, AppSettings.KEY_TRANSLATE_ENABLED -> updateTranslateDependencies()
		}
	}

	/**
	 * Gate every translation sub-setting on the master beta toggle, and hide the BYOK
	 * endpoint/key/model/headers fields when the keyless Google Lens provider is selected.
	 */
	private fun updateTranslateDependencies() {
		val enabled = settings.isPageTranslationEnabled
		val isLens = settings.translateProvider == TranslateProvider.GOOGLE_LENS
		for (key in TRANSLATE_CONFIG_KEYS) {
			findPreference<Preference>(key)?.isEnabled = enabled
		}
		for (key in TRANSLATE_BYOK_KEYS) {
			findPreference<Preference>(key)?.isVisible = !isLens
		}
	}

	private fun updateReaderModeDependency() {
		findPreference<Preference>(AppSettings.KEY_READER_MODE_DETECT)?.run {
			isEnabled = settings.defaultReaderMode != ReaderMode.WEBTOON
		}
	}

	private object FlashDurationSummaryProvider : Preference.SummaryProvider<SliderPreference> {
		override fun provideSummary(preference: SliderPreference): CharSequence {
			return preference.context.getString(R.string.milliseconds_pattern, preference.value)
		}
	}

	private object FlashEverySummaryProvider : Preference.SummaryProvider<SliderPreference> {
		override fun provideSummary(preference: SliderPreference): CharSequence {
			val value = preference.value
			return preference.context.resources.getQuantityStringSafe(R.plurals.pages, value, value)
		}
	}

	private companion object {
		// BYOK fields hidden for the keyless Google Lens provider.
		private val TRANSLATE_BYOK_KEYS = arrayOf(
			AppSettings.KEY_TRANSLATE_ENDPOINT,
			AppSettings.KEY_TRANSLATE_API_KEY,
			AppSettings.KEY_TRANSLATE_MODEL,
			AppSettings.KEY_TRANSLATE_CUSTOM_HEADERS,
		)

		// Everything under the category, disabled when the master toggle is off.
		private val TRANSLATE_CONFIG_KEYS = arrayOf(
			AppSettings.KEY_TRANSLATE_PROVIDER,
			AppSettings.KEY_TRANSLATE_ENDPOINT,
			AppSettings.KEY_TRANSLATE_API_KEY,
			AppSettings.KEY_TRANSLATE_MODEL,
			AppSettings.KEY_TRANSLATE_CUSTOM_HEADERS,
			AppSettings.KEY_TRANSLATE_SOURCE_LANG,
			AppSettings.KEY_TRANSLATE_TARGET_LANG,
			AppSettings.KEY_TRANSLATE_TRIGGER_MODE,
			AppSettings.KEY_TRANSLATE_OVERLAY_BG,
			AppSettings.KEY_TRANSLATE_CONCURRENCY,
			AppSettings.KEY_TRANSLATE_RPM,
			AppSettings.KEY_TRANSLATE_CLEAR_CACHE,
		)
	}
}

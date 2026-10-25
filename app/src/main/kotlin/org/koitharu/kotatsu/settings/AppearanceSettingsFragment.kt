package org.koitharu.kotatsu.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.SearchSuggestionType
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.core.ui.theme.WallpaperPaletteCompat
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.getLocalesConfig
import org.koitharu.kotatsu.core.util.ext.postDelayed
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.core.util.ext.toList
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.settings.protect.ProtectSetupActivity
import org.koitharu.kotatsu.settings.utils.ActivityListPreference
import org.koitharu.kotatsu.settings.utils.MultiSummaryProvider
import org.koitharu.kotatsu.settings.utils.PercentSummaryProvider
import org.koitharu.kotatsu.settings.utils.SliderPreference
import org.koitharu.kotatsu.settings.utils.ThemeChooserPreference
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceSettingsFragment :
    BasePreferenceFragment(R.string.appearance),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var activityRecreationHandle: ActivityRecreationHandle

    @Inject
    lateinit var appShortcutManager: AppShortcutManager

    @Inject
    lateinit var wallpaperPalette: WallpaperPaletteCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_appearance)
        findPreference<TwoStatePreference>(AppSettings.KEY_BAR_BLUR)?.isEnabled = Build.VERSION.SDK_INT >= 33
        findPreference<TwoStatePreference>(AppSettings.KEY_TV_LAUNCHER)?.setOnPreferenceChangeListener { _, value ->
            try {
                org.koitharu.kotatsu.core.os.TvLauncherSettings.apply(requireContext(), value as Boolean)
                true
            } catch (error: Exception) {
                com.google.android.material.snackbar.Snackbar.make(listView, error.message ?: getString(R.string.operation_not_supported), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                false
            }
        }
        findPreference<SliderPreference>(AppSettings.KEY_GRID_SIZE)?.summaryProvider = PercentSummaryProvider()
        findPreference<ListPreference>(AppSettings.KEY_FAVORITES_ORDER)?.run {
            val orders = ListSortOrder.FAVORITES.sortedBy { it.ordinal }
            entries = orders.map {
                "${getString(it.titleResId)} (${getString(if (it.isAscending) R.string.sort_order_asc else R.string.sort_order_desc)})"
            }.toTypedArray()
            entryValues = orders.map { it.name }.toTypedArray()
            setDefaultValueCompat(ListSortOrder.NEWEST.name)
        }
        findPreference<ListPreference>(AppSettings.KEY_LIST_MODE)?.run {
            entryValues = ListMode.entries.names()
            setDefaultValueCompat(ListMode.GRID.name)
        }
        findPreference<ListPreference>(AppSettings.KEY_PROGRESS_INDICATORS)?.run {
            entryValues = ProgressIndicatorMode.entries.names()
            setDefaultValueCompat(ProgressIndicatorMode.PERCENT_READ.name)
        }
        findPreference<ActivityListPreference>(AppSettings.KEY_APP_LOCALE)?.run {
            initLocalePicker(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activityIntent = Intent(
                    Settings.ACTION_APP_LOCALE_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            }
            summaryProvider = Preference.SummaryProvider<ActivityListPreference> {
                val locale = AppCompatDelegate.getApplicationLocales().get(0)
                locale?.getDisplayName(locale)?.toTitleCase(locale) ?: getString(R.string.follow_system)
            }
            setDefaultValueCompat("")
        }
        findPreference<MultiSelectListPreference>(AppSettings.KEY_MANGA_LIST_BADGES)?.run {
            summaryProvider = MultiSummaryProvider(R.string.none)
        }
        findPreference<Preference>(AppSettings.KEY_SHORTCUTS)?.isVisible =
            appShortcutManager.isDynamicShortcutsAvailable()
        findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
            ?.isChecked = !settings.appPassword.isNullOrEmpty()
        findPreference<ListPreference>(AppSettings.KEY_SCREENSHOTS_POLICY)?.run {
            entryValues = ScreenshotsPolicy.entries.names()
            setDefaultValueCompat(ScreenshotsPolicy.ALLOW.name)
        }
        findPreference<MultiSelectListPreference>(AppSettings.KEY_SEARCH_SUGGESTION_TYPES)?.let { pref ->
            pref.entryValues = SearchSuggestionType.entries.names()
            pref.entries = SearchSuggestionType.entries.map { pref.context.getString(it.titleResId) }.toTypedArray()
            pref.summaryProvider = MultiSummaryProvider(R.string.none)
            pref.values = settings.searchSuggestionTypes.mapToSet { it.name }
        }
        bindNavSummary()
        bindWallpaperStatus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(this)
        if (WallpaperPaletteCompat.isSupported) viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wallpaperPalette.status.collect {
                    bindWallpaperStatus()
                    findPreference<ThemeChooserPreference>(AppSettings.KEY_COLOR_THEME)?.refreshPalettePreview()
                }
            }
        }
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_THEME -> {
                AppCompatDelegate.setDefaultNightMode(settings.theme)
            }

            AppSettings.KEY_COLOR_THEME,
            AppSettings.KEY_THEME_AMOLED,
            AppSettings.KEY_ANIMATED_MENUS,
                -> {
                bindWallpaperStatus()
                postRestart()
            }

            AppSettings.KEY_APP_LOCALE -> {
                AppCompatDelegate.setApplicationLocales(settings.appLocales)
            }

            AppSettings.KEY_NAV_MAIN -> {
                bindNavSummary()
            }

            AppSettings.KEY_APP_PASSWORD -> {
                findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
                    ?.isChecked = !settings.appPassword.isNullOrEmpty()
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            "wallpaper_palette_refresh" -> {
                if (WallpaperPaletteCompat.isSupported && settings.colorScheme == ColorScheme.WALLPAPER) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = wallpaperPalette.refresh(force = true)
                        if (result == WallpaperPaletteCompat.Status.READY &&
                            settings.colorScheme == ColorScheme.WALLPAPER && isResumed) postRestart()
                    }
                }
                true
            }
            AppSettings.KEY_PROTECT_APP -> {
                val pref = (preference as? TwoStatePreference ?: return false)
                if (pref.isChecked) {
                    pref.isChecked = false
                    startActivity(Intent(preference.context, ProtectSetupActivity::class.java))
                } else {
                    settings.appPassword = null
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun postRestart() {
        viewLifecycleOwner.lifecycle.postDelayed(400) {
            activityRecreationHandle.recreateAll()
        }
    }

    private fun bindWallpaperStatus() {
        findPreference<Preference>("wallpaper_palette_refresh")?.run {
            isVisible = WallpaperPaletteCompat.isSupported
            isEnabled = settings.colorScheme == ColorScheme.WALLPAPER
            setSummary(if (!isEnabled) R.string.wallpaper_palette_choose else when (wallpaperPalette.status.value) {
                WallpaperPaletteCompat.Status.NOT_PREPARED -> R.string.wallpaper_palette_pending
                WallpaperPaletteCompat.Status.READY -> R.string.wallpaper_palette_ready
                WallpaperPaletteCompat.Status.UNAVAILABLE -> R.string.wallpaper_palette_unavailable
                WallpaperPaletteCompat.Status.OVERRIDE_UNAVAILABLE -> R.string.wallpaper_palette_override_unavailable
            })
        }
    }

    private fun initLocalePicker(preference: ListPreference) {
        val locales = preference.context.getLocalesConfig()
            .toList()
            .sortedWithSafe(LocaleComparator())
        preference.entries = Array(locales.size + 1) { i ->
            if (i == 0) {
                getString(R.string.follow_system)
            } else {
                val lc = locales[i - 1]
                lc.getDisplayName(lc).toTitleCase(lc)
            }
        }
        preference.entryValues = Array(locales.size + 1) { i ->
            if (i == 0) {
                ""
            } else {
                locales[i - 1].toLanguageTag()
            }
        }
    }

    private fun bindNavSummary() {
        val pref = findPreference<Preference>(AppSettings.KEY_NAV_MAIN) ?: return
        pref.summary = settings.mainNavItems.joinToString {
            getString(it.title)
        }
    }
}

package org.koitharu.kotatsu.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.settings.utils.MultiAutoCompleteTextViewPreference
import org.koitharu.kotatsu.settings.utils.TagsAutoCompleteProvider
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import javax.inject.Inject
import org.koitharu.kotatsu.core.model.getLocale

@AndroidEntryPoint
class SuggestionsSettingsFragment : BasePreferenceFragment(R.string.suggestions),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var repository: SuggestionRepository

    @Inject
    lateinit var tagsCompletionProvider: TagsAutoCompleteProvider

    @Inject
    lateinit var suggestionsScheduler: SuggestionsWorker.Scheduler

    @Inject lateinit var recommendationEngine: org.koitharu.kotatsu.suggestions.local.RecommendationEngine
    @Inject lateinit var sources: org.koitharu.kotatsu.explore.data.MangaSourcesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings.subscribe(this)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_suggestions)

        findPreference<androidx.preference.SeekBarPreference>(AppSettings.KEY_SUGGESTIONS_DIVERSITY)?.summaryProvider =
            androidx.preference.Preference.SummaryProvider<androidx.preference.SeekBarPreference> { getString(R.string.recommendations_diversity_value, it.value * 40 / 100) }
        findPreference<androidx.preference.SeekBarPreference>(AppSettings.KEY_SUGGESTIONS_THRESHOLD)?.summaryProvider =
            androidx.preference.Preference.SummaryProvider<androidx.preference.SeekBarPreference> { getString(R.string.recommendations_threshold_value, it.value) }
        findPreference<androidx.preference.Preference>("recommendations_clear_dismissals")?.setOnPreferenceClickListener {
            lifecycleScope.launch { recommendationEngine.clearDismissals() }
            true
        }
        lifecycleScope.launch {
            val languages = sources.getParserSourcesSnapshot().mapNotNull { it.source.let { source ->
                source.getLocale()?.language
            } }.distinct().sorted()
            findPreference<androidx.preference.MultiSelectListPreference>(AppSettings.KEY_SUGGESTIONS_LANGUAGES)?.apply {
                entries = languages.map { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()) }.toTypedArray()
                entryValues = languages.toTypedArray()
            }
        }
        updateRecommendationControls()

        findPreference<MultiAutoCompleteTextViewPreference>(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS)?.run {
            autoCompleteProvider = tagsCompletionProvider
            summaryProvider = MultiAutoCompleteTextViewPreference.SimpleSummaryProvider(summary)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        settings.unsubscribe(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key !in org.koitharu.kotatsu.suggestions.local.RecommendationEngine.KEYS) return
        updateRecommendationControls()
        val localOnly = key in setOf(AppSettings.KEY_SUGGESTIONS_DIVERSITY, AppSettings.KEY_SUGGESTIONS_STARTED,
            AppSettings.KEY_SUGGESTIONS_THRESHOLD, AppSettings.KEY_SUGGESTIONS_TITLE_ONLY)
        if (settings.isSuggestionsEnabled && !localOnly) updateSuggestions()
    }

    private fun updateRecommendationControls() {
        listOf(AppSettings.KEY_SUGGESTIONS_TITLE_ONLY, AppSettings.KEY_SUGGESTIONS_DIVERSITY, AppSettings.KEY_SUGGESTIONS_STARTED,
            AppSettings.KEY_SUGGESTIONS_PUBLIC, AppSettings.KEY_SUGGESTIONS_LANGUAGES, "recommendations_clear_dismissals",
            "recommendations_beta_info").forEach { key -> findPreference<androidx.preference.Preference>(key)?.isEnabled = !settings.useLegacySuggestions }
        findPreference<androidx.preference.Preference>(AppSettings.KEY_SUGGESTIONS_THRESHOLD)?.isEnabled =
            !settings.useLegacySuggestions && settings.recommendStartedTitles
    }

    private fun updateSuggestions() {
        lifecycleScope.launch(Dispatchers.Default) {
            suggestionsScheduler.startNow(force = false)
        }
    }
}

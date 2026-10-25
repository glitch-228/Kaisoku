package org.koitharu.kotatsu.community.ui

import android.view.View
import android.widget.ProgressBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.CommunityRepository
import org.koitharu.kotatsu.community.data.allowed
import org.koitharu.kotatsu.community.data.isCommunitySimilarSeed
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.suggestions.local.*
import javax.inject.Inject

class CommunitySimilarUi @Inject constructor(
    private val community: CommunityRepository,
    private val settings: AppSettings,
    private val engine: RecommendationEngine,
    private val recommendations: RecommendationUi,
) {
    fun show(seed: Manga, host: View, scope: CoroutineScope, router: AppRouter) {
        if (!settings.isCommunitySimilarEnabled || settings.useLegacySuggestions || !seed.isCommunitySimilarSeed) return
        var job: Job? = null
        val loading = MaterialAlertDialogBuilder(host.context).setTitle(R.string.community_similar)
            .setView(ProgressBar(host.context)).setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }.show()
        job = scope.launch {
            try {
                val novel = seed.recommendationKind() == RecommendationKind.NOVEL
                val results = community.getSimilarTitles(seed, engine.networkAllowed()).filter { it.allowed(settings, novel) }
                loading.dismiss()
                if (!settings.isCommunitySimilarEnabled || settings.useLegacySuggestions) return@launch
                MaterialAlertDialogBuilder(host.context).setTitle(R.string.community_similar)
                    .setMessage(if (results.isEmpty()) host.context.getString(R.string.community_similar_empty) else null)
                    .setItems(results.map { it.title }.toTypedArray()) { _, index ->
                        val selected = results[index]
                        recommendations.findReadableSource(selected.title, selected.isNovel, host, scope, router)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } catch (error: CancellationException) {
                loading.dismiss()
                throw error
            } catch (error: Exception) {
                loading.dismiss()
                if (settings.isCommunitySimilarEnabled && !settings.useLegacySuggestions) {
                    showRecommendationErrorDetails(host.context, error.getCopyableErrorDetails())
                }
            }
        }
    }
}

package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.core.parser.hasChapterText

import android.content.Context
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.reverseAsync
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

fun showRecommendationErrorDetails(context: Context, details: String) {
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.recommendation_sources_with_errors)
        .setMessage(RecommendationFailures.summary(details))
        .setPositiveButton(R.string.close, null)
        .setNeutralButton(R.string.recommendation_copy_full_error) { _, _ ->
            context.copyToClipboard(context.getString(R.string.error_details), RecommendationFailures.fullDetails(details))
        }
        .show()
        .findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
}

fun recommendationReasonText(value: Recommendation, context: Context): String = when (value.reason) {
    RecommendationReason.BECAUSE_READ -> context.getString(R.string.recommendation_because, value.because.orEmpty())
    RecommendationReason.BECAUSE_FAVORITE -> context.getString(R.string.recommendation_because_favorite, value.because.orEmpty())
    RecommendationReason.GENRES -> context.getString(R.string.recommendation_genres)
    RecommendationReason.AUTHOR -> context.getString(R.string.recommendation_author)
    RecommendationReason.RELATED -> context.getString(R.string.recommendation_related)
    RecommendationReason.TITLE_TERMS -> context.getString(R.string.recommendation_title_terms)
    RecommendationReason.TITLE_RELATION -> context.getString(R.string.recommendation_title_relation)
    RecommendationReason.NEW_CHAPTERS -> context.getString(R.string.recommendation_new_chapters)
    RecommendationReason.DISCOVERY -> context.getString(R.string.recommendation_discovery)
}

class RecommendationUi @Inject constructor(
    private val engine: RecommendationEngine,
    private val providers: PublicRecommendationProviders,
) {
    fun feedback(value: Recommendation?, host: View, scope: CoroutineScope): Boolean {
        if (value == null || engine.settings.useLegacySuggestions) return false
        MaterialAlertDialogBuilder(host.context).setTitle(value.manga.title)
            .setMessage(recommendationReasonText(value, host.context))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recommendation_not_interested) { _, _ ->
                scope.launch {
                    try {
                    val undo = engine.dismiss(value)
                    Snackbar.make(host, R.string.recommendation_hidden, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) { undo.reverseAsync() }.show()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { showError(host, e) }
                }
            }.show()
        return true
    }

    fun findReadableSource(manga: Manga, host: View, scope: CoroutineScope, router: AppRouter) {
        findReadableSource(manga.title, manga.recommendationKind() == RecommendationKind.NOVEL, host, scope, router)
    }

    fun findReadableSource(title: String, novel: Boolean, host: View, scope: CoroutineScope, router: AppRouter) {
        scope.launch {
            try {
            val choices = engine.availableSources()
                .filter { it.hasChapterText() == novel }
            MaterialAlertDialogBuilder(host.context).setTitle(R.string.recommendation_find_source)
                .setMessage(if (choices.isEmpty()) host.context.getString(R.string.recommendation_no_sources) else null)
                .setItems(choices.map { it.getTitle(host.context) }.toTypedArray()) { _, index -> router.openSearch(choices[index], title) }
                .setNegativeButton(android.R.string.cancel, null).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(host, e) }
        }
    }

    fun confirmProvider(seed: Manga, host: View, scope: CoroutineScope, onConfirmed: () -> Unit) {
        if (!engine.settings.publicRecommendations || engine.settings.useLegacySuggestions) return
        if (!engine.networkAllowed()) {
            Snackbar.make(host, R.string.recommendation_network_required, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(host.context).setTitle(R.string.recommendation_public_match)
            .setItems(arrayOf("AniList", "MangaUpdates")) { _, index ->
                scope.launch {
                    try {
                        val matches = providers.search(if (index == 0) "ANILIST" else "MANGAUPDATES", seed)
                        MaterialAlertDialogBuilder(host.context).setTitle(R.string.recommendation_confirm_match)
                            .setItems(matches.map { "${it.manga.title} · ${(it.target as RecommendationTarget.PublicTitle).identifier}" }.toTypedArray()) { _, selection ->
                                val selected = matches[selection]
                                MaterialAlertDialogBuilder(host.context).setTitle(selected.manga.title)
                                    .setMessage(R.string.recommendation_match_risk)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.confirm) { _, _ -> scope.launch {
                                        try { providers.confirm(seed, selected); onConfirmed() }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { showError(host, e) }
                                    } }.show()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Snackbar.make(host, e.message ?: host.context.getString(R.string.error), Snackbar.LENGTH_LONG).show() }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showError(host: View, error: Exception) {
        Snackbar.make(host, error.message ?: host.context.getString(R.string.error), Snackbar.LENGTH_LONG).show()
    }
}

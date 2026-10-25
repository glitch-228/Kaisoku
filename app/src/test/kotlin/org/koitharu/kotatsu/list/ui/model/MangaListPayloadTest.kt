package org.koitharu.kotatsu.list.ui.model

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.UnresolvedMangaSource
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_ANYTHING_CHANGED
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.suggestions.local.Recommendation
import org.koitharu.kotatsu.suggestions.local.RecommendationReason
import org.koitharu.kotatsu.suggestions.local.RecommendationTarget
import java.time.Instant

class MangaListPayloadTest {
    private val source = object : MangaSource { override val name = "fixture:plugin" }
    private val title = Manga(id = 7, title = "Title", altTitles = emptySet(), url = "/fixture",
        publicUrl = "https://fixture.test/fixture", rating = -1f, contentRating = null, coverUrl = "https://fixture.test/cover",
        tags = emptySet(), state = null, authors = emptySet(), source = source)
    private val progress = ReadingProgress(.5f, 20, ProgressIndicatorMode.PERCENT_READ)
    private val category = FavouriteCategory(3, "Category", 0, ListSortOrder.entries.first(), Instant.EPOCH, false, true)
    private fun grid(manga: Manga = title) = MangaGridModel(manga, null, 0, null, false, false)
    private fun detailed(manga: Manga = title) = MangaDetailedListModel(manga, null, "Text", 0, null, false, false, emptyList())
    private fun compact(manga: Manga = title) = MangaCompactListModel(manga, null, "Text", 0)

    @Test fun resolutionUpdatesEveryRowInPlaceWithoutChangingItsIdentity() {
        val unresolved = title.copy(source = UnresolvedMangaSource(source.name))
        for ((old, new) in listOf(grid(unresolved) to grid(), detailed(unresolved) to detailed(), compact(unresolved) to compact())) {
            assertTrue(old.areItemsTheSame(new))
            assertSame(PAYLOAD_ANYTHING_CHANGED, new.getChangePayload(old))
        }
    }

    @Test fun differentWorkSourceOrMangaContentRequiresAFullBind() {
        for (changed in listOf(title.copy(id = 99), title.copy(title = "New"), title.copy(coverUrl = "new"),
            title.copy(url = "/different"), title.copy(source = UnresolvedMangaSource("different:plugin")))) {
            assertNull(grid(changed).getChangePayload(grid()))
            assertNull(detailed(changed).getChangePayload(detailed()))
            assertNull(compact(changed).getChangePayload(compact()))
        }
        assertNull(grid().getChangePayload(compact()))
        assertNull(compact().getChangePayload(detailed()))
    }

    @Test fun unchangedRowsDoNotProduceAPayload() {
        for (row in listOf(grid(), detailed(), compact())) assertNull(row.getChangePayload(row))
    }

    @Test fun progressAnimatesOnlyWhenItIsTheSoleChange() {
        val g = grid(); val d = detailed()
        assertSame(PAYLOAD_PROGRESS_CHANGED, g.copy(progress = progress).getChangePayload(g))
        assertSame(PAYLOAD_PROGRESS_CHANGED, d.copy(progress = progress).getChangePayload(d))
        assertSame(PAYLOAD_ANYTHING_CHANGED, g.copy(progress = progress, counter = 1).getChangePayload(g))
        assertSame(PAYLOAD_ANYTHING_CHANGED, d.copy(progress = progress, isSaved = true).getChangePayload(d))
    }

    @Test fun overridesAndCategoryLinksRebindAllPresentations() {
        val override = MangaOverride("new-cover", "Override", null, "Synopsis")
        val old = listOf(grid(), detailed(), compact())
        val changed = listOf(grid().copy(override = override, favoriteCategories = listOf(category)),
            detailed().copy(override = override, favoriteCategories = listOf(category)),
            compact().copy(override = override, favoriteCategories = listOf(category)))
        old.zip(changed).forEach { (before, after) -> assertSame(PAYLOAD_ANYTHING_CHANGED, after.getChangePayload(before)) }
    }

    @Test fun recommendationReasonsAndSourceChooserTargetsAreRebound() {
        val recommendation = Recommendation(RecommendationTarget.SourceTitle(title), reason = RecommendationReason.GENRES)
        assertSame(PAYLOAD_ANYTHING_CHANGED, grid().copy(recommendation = recommendation).getChangePayload(grid()))
        assertSame(PAYLOAD_ANYTHING_CHANGED, compact().copy(recommendation = recommendation).getChangePayload(compact()))
        val prior = grid().copy(recommendation = recommendation)
        assertSame(PAYLOAD_ANYTHING_CHANGED, prior.copy(recommendation = recommendation.copy(because = "Another title")).getChangePayload(prior))
    }

    @Test fun subtitlesTagsAndFlagsAreNeverDroppedByASmallUpdate() {
        assertSame(PAYLOAD_ANYTHING_CHANGED, compact().copy(subtitle = "New").getChangePayload(compact()))
        assertSame(PAYLOAD_ANYTHING_CHANGED, detailed().copy(tags = listOf(ChipsView.ChipModel("Tag"))).getChangePayload(detailed()))
        assertSame(PAYLOAD_ANYTHING_CHANGED, grid().copy(isTitleHidden = true).getChangePayload(grid()))
        assertSame(PAYLOAD_ANYTHING_CHANGED, grid().copy(isFavorite = true).getChangePayload(grid()))
    }
}

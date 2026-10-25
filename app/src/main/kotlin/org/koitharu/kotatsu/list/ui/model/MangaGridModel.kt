package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.parsers.model.Manga

data class MangaGridModel(
    override val manga: Manga,
    override val override: MangaOverride?,
    override val counter: Int,
    val progress: ReadingProgress?,
    val isFavorite: Boolean,
    val isSaved: Boolean,
    val isTitleHidden: Boolean = false,
    override val recommendation: org.koitharu.kotatsu.suggestions.local.Recommendation? = null,
    override val favoriteCategories: List<org.koitharu.kotatsu.core.model.FavouriteCategory> = emptyList(),
) : MangaListModel() {

    override fun getChangePayload(previousState: ListModel): Any? = when {
        previousState !is MangaGridModel || !hasSameMangaContent(previousState.manga) -> null
        previousState.progress != progress && previousState.copy(progress = progress) == this -> PAYLOAD_PROGRESS_CHANGED
        else -> super.getChangePayload(previousState)
    }
}

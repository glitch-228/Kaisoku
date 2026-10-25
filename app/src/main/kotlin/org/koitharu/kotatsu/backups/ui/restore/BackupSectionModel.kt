package org.koitharu.kotatsu.backups.ui.restore

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel

data class BackupSectionModel(
    val section: BackupSection,
    val isChecked: Boolean,
    val isEnabled: Boolean,
) : ListModel {

    @get:StringRes
    val titleResId: Int
        get() = when (section) {
            BackupSection.INDEX -> 0 // should not appear here
            BackupSection.HISTORY -> R.string.history
            BackupSection.CATEGORIES -> R.string.favourites_categories
            BackupSection.FAVOURITES -> R.string.favourites
            BackupSection.FAVORITE_ORDER -> R.string.favorites_manual_order
            BackupSection.SETTINGS -> R.string.settings
            BackupSection.SETTINGS_READER_GRID -> R.string.reader_actions
            BackupSection.BOOKMARKS -> R.string.bookmarks
            BackupSection.SOURCES -> R.string.remote_sources
            BackupSection.SCROBBLING -> R.string.tracking
            BackupSection.STATS -> R.string.statistics
            BackupSection.SAVED_FILTERS -> R.string.saved_filters
            BackupSection.MANGA_PREFERENCES -> R.string.manga_preferences
            BackupSection.TRACKS -> R.string.feed_tracking_state
            BackupSection.EXACT_COMPLETION -> R.string.exact_completion
            BackupSection.FOREIGN_IMPORT_STATE -> R.string.mihon_review_pending_state
            BackupSection.REMOTE_LIBRARIES -> R.string.remote_library_title
            BackupSection.TRACKER_LINKS -> R.string.tracker_link_backup
            BackupSection.TRACKER_CATEGORIES -> R.string.tracker_categories_beta
            BackupSection.METADATA_OVERRIDES -> R.string.tracker_metadata_backup
        }

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is BackupSectionModel && other.section == section
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        if (previousState !is BackupSectionModel) {
            return null
        }
        return if (previousState.isEnabled != isEnabled) {
            ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
        } else if (previousState.isChecked != isChecked) {
            ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }
}

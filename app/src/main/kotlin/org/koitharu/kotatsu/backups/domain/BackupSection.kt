package org.koitharu.kotatsu.backups.domain

import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
    val entryName: String,
) {

    INDEX("index"),
    HISTORY("history"),
    CATEGORIES("categories"),
    FAVOURITES("favourites"),
    FAVORITE_ORDER("favorite_order"),
    SETTINGS("settings"),
    SETTINGS_READER_GRID("reader_grid"),
    BOOKMARKS("bookmarks"),
    SOURCES("sources"),
    SCROBBLING("scrobbling"),
    STATS("statistics"),
    SAVED_FILTERS("saved_filters"),
    MANGA_PREFERENCES("manga_preferences"),
    TRACKS("tracks"),
    EXACT_COMPLETION("exact_completion_v1"),
    FOREIGN_IMPORT_STATE("foreign_import_state_v1"),
    REMOTE_LIBRARIES("remote_libraries_v1"),
    TRACKER_LINKS("tracker_links_v1"),
    TRACKER_CATEGORIES("tracker_categories_v1"),
    METADATA_OVERRIDES("metadata_overrides_v1"),
    ;

    /** These sections have independent causal formats and never enter Legacy Drive ownership/tombstones. */
    val isExtended: Boolean
        get() = when (this) {
            EXACT_COMPLETION, FOREIGN_IMPORT_STATE, REMOTE_LIBRARIES, TRACKER_LINKS, TRACKER_CATEGORIES,
            METADATA_OVERRIDES -> true
            else -> false
        }

    companion object {

        fun of(entry: ZipEntry): BackupSection? {
            if (entry.isDirectory) {
                return null
            }
            // By file name alone: a backup unzipped and zipped again usually sits in a folder
            val name = entry.name.replace('\\', '/')
                .substringAfterLast('/')
                .removeSuffix(".json")
                .lowercase(Locale.ROOT)
            return entries.find { x -> x.entryName == name }
        }
    }
}

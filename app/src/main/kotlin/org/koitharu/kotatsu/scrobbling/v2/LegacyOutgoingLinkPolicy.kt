package org.koitharu.kotatsu.scrobbling.v2

import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity

/** A corrected or unreviewed v2 link must not leave an old sender updating another work. */
internal object LegacyOutgoingLinkPolicy {
    fun allows(current: TrackerLinkV2Entity?, legacy: ScrobblingEntity, manga: MangaEntity?): Boolean {
        if (current == null) return true
        if (!current.confirmed || current.deleted || manga == null) return false
        return current.mangaId == legacy.mangaId && current.source == manga.source &&
            (current.editionUrl == manga.url || current.editionUrl == manga.publicUrl) &&
            current.mediaId == legacy.targetId.toString() &&
            (current.listEntryId == null || current.listEntryId == legacy.id.toString())
    }
}

package org.koitharu.kotatsu.history.exact

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/** Durable state intentionally has no FK to chapters or history: cache eviction/reset must retain it. */
@Serializable
@Entity(tableName = "chapter_read_policy", primaryKeys = ["manga_id"])
data class ChapterReadPolicyEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "edition_url") val editionUrl: String,
    @ColumnInfo(name = "generation") val generation: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    @ColumnInfo(name = "progress_branch") val progressBranch: String,
    @ColumnInfo(name = "revision_wall") val revisionWall: Long,
    @ColumnInfo(name = "revision_logical") val revisionLogical: Long,
    @ColumnInfo(name = "revision_actor") val revisionActor: String,
    @ColumnInfo(name = "import_fingerprint") val importFingerprint: String?,
    @ColumnInfo(name = "generation_wall", defaultValue = "0") val generationWall: Long = revisionWall,
    @ColumnInfo(name = "generation_logical", defaultValue = "0") val generationLogical: Long = revisionLogical,
    @ColumnInfo(name = "generation_actor", defaultValue = "'unknown'") val generationActor: String = revisionActor,
) {
    val revision: ReadRevision get() = ReadRevision(revisionWall, revisionLogical, revisionActor)
    val generationRevision: ReadRevision get() = ReadRevision(generationWall, generationLogical, generationActor)
}

@Serializable
@Entity(tableName = "chapter_read_record", primaryKeys = ["manga_id", "generation", "reference_id"])
data class ChapterReadRecordEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "generation") val generation: String,
    @ColumnInfo(name = "reference_id") val referenceId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "branch_key") val branchKey: String,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "original_url") val originalUrl: String,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "revision_wall") val revisionWall: Long,
    @ColumnInfo(name = "revision_logical") val revisionLogical: Long,
    @ColumnInfo(name = "revision_actor") val revisionActor: String,
) {
    fun toState() = ExplicitChapterRead(referenceId, generation,
        ReadChapterReference(source, decodeReadBranch(branchKey), chapterId, originalUrl), isRead,
        ReadRevision(revisionWall, revisionLogical, revisionActor))
}

@Serializable
@Entity(tableName = "chapter_read_alias", primaryKeys = ["manga_id", "reference_id", "source", "branch_key", "url"])
data class ChapterReadAliasEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "reference_id") val referenceId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "branch_key") val branchKey: String,
    @ColumnInfo(name = "url") val url: String,
) {
    fun toAlias() = ChapterReadAlias(referenceId, source, branchKey, url)
}

@Entity(tableName = "chapter_read_clock", primaryKeys = ["id"])
data class ChapterReadClockEntity(
    @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "wall") val wall: Long,
    @ColumnInfo(name = "logical") val logical: Long,
)

@Serializable
@Entity(tableName = "chapter_read_count", primaryKeys = ["manga_id", "branch_key"])
data class ChapterReadCountEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "branch_key") val branchKey: String,
    @ColumnInfo(name = "read_count") val readCount: Int,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "list_revision") val listRevision: String,
    @ColumnInfo(name = "chapter_references", defaultValue = "'[]'") val chapterReferences: String = "[]",
    @ColumnInfo(name = "revision_wall", defaultValue = "0") val revisionWall: Long = 0,
    @ColumnInfo(name = "revision_logical", defaultValue = "0") val revisionLogical: Long = 0,
    @ColumnInfo(name = "revision_actor", defaultValue = "'unknown'") val revisionActor: String = "unknown",
) {
    val percent: Float get() = if (totalCount <= 0) 0f else (readCount.toFloat() / totalCount).coerceIn(0f, 1f)
    val revision: ReadRevision get() = ReadRevision(revisionWall, revisionLogical, revisionActor)
}

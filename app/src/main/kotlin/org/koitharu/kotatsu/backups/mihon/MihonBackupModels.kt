package org.koitharu.kotatsu.backups.mihon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Wire models for Mihon/Tachiyomi's gzip-wrapped protobuf backup format. */
@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.Backup")
data class MihonBackup(
	@ProtoNumber(1) val manga: List<MihonBackupManga> = emptyList(),
	@ProtoNumber(2) val categories: List<MihonBackupCategory> = emptyList(),
	@ProtoNumber(101) val sources: List<MihonBackupSource> = emptyList(),
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupManga")
data class MihonBackupManga(
	@ProtoNumber(1) val source: Long = 0,
	@ProtoNumber(2) val url: String = "",
	@ProtoNumber(3) val title: String = "",
	@ProtoNumber(4) val artist: String? = null,
	@ProtoNumber(5) val author: String? = null,
	@ProtoNumber(7) val genre: List<String> = emptyList(),
	@ProtoNumber(9) val thumbnailUrl: String? = null,
	@ProtoNumber(13) val dateAdded: Long = 0,
	@ProtoNumber(16) val chapters: List<MihonBackupChapter> = emptyList(),
	@ProtoNumber(17) val categories: List<Long> = emptyList(),
	@ProtoNumber(18) val tracking: List<MihonBackupTracking> = emptyList(),
	@ProtoNumber(100) val favorite: Boolean = true,
	@ProtoNumber(104) val history: List<MihonBackupHistory> = emptyList(),
	@ProtoNumber(106) val lastModifiedAt: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupChapter")
data class MihonBackupChapter(
	@ProtoNumber(1) val url: String = "",
	@ProtoNumber(2) val name: String = "",
	@ProtoNumber(3) val scanlator: String? = null,
	@ProtoNumber(4) val read: Boolean = false,
	@ProtoNumber(5) val bookmark: Boolean = false,
	@ProtoNumber(6) val lastPageRead: Long = 0,
	@ProtoNumber(8) val dateUpload: Long = 0,
	@ProtoNumber(9) val chapterNumber: Float = 0f,
	@ProtoNumber(10) val sourceOrder: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupHistory")
data class MihonBackupHistory(
	@ProtoNumber(1) val url: String = "",
	@ProtoNumber(2) val lastRead: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupCategory")
data class MihonBackupCategory(
	@ProtoNumber(1) val name: String = "",
	@ProtoNumber(2) val order: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupTracking")
data class MihonBackupTracking(
	@ProtoNumber(1) val syncId: Int = 0,
	@ProtoNumber(2) val libraryId: Long = 0,
	@ProtoNumber(6) val lastChapterRead: Float = 0f,
	@ProtoNumber(8) val score: Float = 0f,
	@ProtoNumber(9) val status: Int = 0,
	@ProtoNumber(100) val mediaId: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupSource")
data class MihonBackupSource(
	@ProtoNumber(1) val name: String = "",
	@ProtoNumber(2) val sourceId: Long = 0,
)

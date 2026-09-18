package org.koitharu.kotatsu.backups.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.data.model.MangaPreferencesBackup
import org.koitharu.kotatsu.backups.data.model.TrackBackup

class BackupCompatibilityTest {
	@Test fun exportOmitsCustomCoversAndKeepsOtherPreferencesReadableByOldVersions() {
		val original = MangaPreferencesBackup(manga, 2, .2f, .4f, true, true, true,
			"My title", "file:///custom.webp", "AQIDBA==", "webp", "SAFE")
		val exported = original.withoutCustomCover()
		val json = compact.encodeToString(exported)
		assertFalse(json.contains("cover_data"))
		assertFalse(json.contains("custom.webp"))
		val restored = old.decodeFromString<MangaPreferencesBackup>(json)
		assertEquals(old.encodeToString(original.copy(coverOverride = null, coverData = null, coverExtension = null)),
			old.encodeToString(restored))
		assertEquals(manga.coverUrl, restored.manga.coverUrl)
		assertEquals("AQIDBA==", original.coverData) // Export does not alter the live preference or older backup.
	}
	// 9.8.4 uses the same unchanged serializers, with encodeDefaults enabled.
	private val old = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = false }
	private val manga = MangaBackup(id = 7, title = "Novel", url = "book/7", publicUrl = "https://example.org/book/7",
		coverUrl = "https://example.org/cover.jpg", source = "lnreader:test")

	@Test fun tracksRemainSelfContainedAndRetainEveryFieldWithOldReader() {
		val track = TrackBackup(manga, 55, 3, 123456, 98765, -1, "Network failed\nstack frame\n".repeat(200))
		val bytes = compact.encodeToString(track)
		val restored = old.decodeFromString<TrackBackup>(bytes)
		assertEquals(track.toEntity(), restored.toEntity())
		assertEquals(manga.toEntity(), restored.manga.toEntity())
		assertEquals(old.encodeToString(track), old.encodeToString(restored))
		assertTrue(bytes.length < old.encodeToString(track).length)
		assertTrue(bytes.contains("\"manga\""))
	}

	@Test fun customCoverOverridesAndNondefaultMangaFieldsSurviveBothDirections() {
		val prefs = MangaPreferencesBackup(manga, 2, .2f, .4f, true, true, true,
			"My title", "file:///custom.webp", "AQIDBA==", "webp", "SAFE")
		val restored = old.decodeFromString<MangaPreferencesBackup>(compact.encodeToString(prefs))
		assertEquals(old.encodeToString(prefs), old.encodeToString(restored))
		assertEquals(old.encodeToString(prefs), old.encodeToString(compact.decodeFromString<MangaPreferencesBackup>(old.encodeToString(prefs))))
		val custom = MangaBackup(9, "Title", "Other", "book", "https://example.org/book", .8f, true,
			"ADULT", "cover", "large", "ONGOING", "Author", "source")
		assertEquals(old.encodeToString(custom), old.encodeToString(old.decodeFromString<MangaBackup>(compact.encodeToString(custom))))
	}
}

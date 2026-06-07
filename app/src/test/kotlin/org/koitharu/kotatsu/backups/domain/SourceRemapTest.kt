package org.koitharu.kotatsu.backups.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRemapTest {

	@Test
	fun `resolve precedence is per-manga then per-source then original`() {
		val remap = SourceRemap(
			perSource = mapOf("BATOTO" to "mihon:pkg/1"),
			perManga = mapOf(SourceRemap.key("BATOTO", "https://bato.to/x") to "READMANGA"),
		)
		// per-manga override wins for the exact title
		assertEquals("READMANGA", remap.resolve("BATOTO", "https://bato.to/x"))
		// per-source default applies to other titles of the same source
		assertEquals("mihon:pkg/1", remap.resolve("BATOTO", "https://bato.to/y"))
		// untouched source keeps its original string
		assertEquals("MANGADEX", remap.resolve("MANGADEX", "https://mangadex.org/z"))
	}

	@Test
	fun `identity keeps every source`() {
		assertTrue(SourceRemap.IDENTITY.isEmpty)
		assertEquals("X", SourceRemap.IDENTITY.resolve("X", "https://example.org/u"))
	}

	@Test
	fun `json round-trips losslessly`() {
		val remap = SourceRemap(
			perSource = mapOf("A" to "B"),
			perManga = mapOf("A https://a/1" to "C"),
		)
		assertEquals(remap, SourceRemap.fromJson(remap.toJson()))
	}

	@Test
	fun `fromJson tolerates null, empty and malformed input`() {
		assertTrue(SourceRemap.fromJson(null).isEmpty)
		assertTrue(SourceRemap.fromJson("").isEmpty)
		assertTrue(SourceRemap.fromJson("{ not json").isEmpty)
	}
}

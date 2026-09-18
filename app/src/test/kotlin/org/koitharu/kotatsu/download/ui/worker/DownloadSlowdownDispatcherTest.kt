package org.koitharu.kotatsu.download.ui.worker

import androidx.collection.MutableObjectLongMap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaSource

class DownloadSlowdownDispatcherTest {

	@Test
	fun `concurrent requests reserve separate slowdown slots`() {
		val slots = MutableObjectLongMap<MangaSource>()

		assertEquals(1_000L, reserveSlowdownSlot(slots, testSource, now = 1_000L, interval = 1_600L))
		assertEquals(2_600L, reserveSlowdownSlot(slots, testSource, now = 1_000L, interval = 1_600L))
		assertEquals(4_200L, reserveSlowdownSlot(slots, testSource, now = 1_000L, interval = 1_600L))
	}

	@Test
	fun `idle source starts its next request immediately`() {
		val slots = MutableObjectLongMap<MangaSource>()

		reserveSlowdownSlot(slots, testSource, now = 1_000L, interval = 1_600L)
		assertEquals(5_000L, reserveSlowdownSlot(slots, testSource, now = 5_000L, interval = 1_600L))
	}

	private companion object {

		val testSource = object : MangaSource {
			override val name = "test"
		}
	}
}

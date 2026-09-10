package org.koitharu.kotatsu.core.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ListCheckpointTest {

	@Test
	fun `parse encode round trip`() {
		val checkpoint = ListCheckpoint.Checkpoint(mangaId = 42L, index = 7, offset = -12)
		val restored = ListCheckpoint.Checkpoint.parse(checkpoint.encode())
		assertNotNull(restored)
		assertEquals(checkpoint, restored)
	}

	@Test
	fun `parse malformed values`() {
		assertNull(ListCheckpoint.Checkpoint.parse(null))
		assertNull(ListCheckpoint.Checkpoint.parse(""))
		assertNull(ListCheckpoint.Checkpoint.parse("1"))
		assertNull(ListCheckpoint.Checkpoint.parse("1:2"))
		assertNull(ListCheckpoint.Checkpoint.parse("1:2:3:4"))
		assertNull(ListCheckpoint.Checkpoint.parse("a:b:c"))
		assertNull(ListCheckpoint.Checkpoint.parse("1:b:c"))
		assertNull(ListCheckpoint.Checkpoint.parse("1:2:c"))
	}

	@Test
	fun `parse rejects zero manga id`() {
		assertNull(ListCheckpoint.Checkpoint.parse("0:1:1"))
	}

	@Test
	fun `parse accepts negative offset and index`() {
		val restored = ListCheckpoint.Checkpoint.parse("17:-3:-40")
		assertNotNull(restored)
		assertEquals(-3, restored?.index)
		assertEquals(-40, restored?.offset)
	}
}

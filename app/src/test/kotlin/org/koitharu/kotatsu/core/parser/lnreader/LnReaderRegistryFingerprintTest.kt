package org.koitharu.kotatsu.core.parser.lnreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity

class LnReaderRegistryFingerprintTest {

	private fun entity(
		pluginId: String,
		name: String = "Test",
		lang: String? = "en",
		site: String? = "https://example.com",
		version: String = "1.0.0",
	) = LnReaderSourceEntity(
		pluginId = pluginId,
		name = name,
		lang = lang,
		site = site,
		version = version,
		jsCode = "…",
		createdAt = 0L,
		updatedAt = 0L,
	)

	@Test
	fun `identical sets in different order share the fingerprint`() {
		val a = listOf(entity("one"), entity("two"), entity("three"))
		val b = listOf(entity("three"), entity("one"), entity("two"))
		assertEquals(registryFingerprint(a), registryFingerprint(b))
	}

	@Test
	fun `identity changes alter the fingerprint`() {
		assertNotEquals(
			registryFingerprint(listOf(entity("one"))),
			registryFingerprint(listOf(entity("two"))),
		)
	}

	@Test
	fun `adding a plugin alters the fingerprint`() {
		val base = listOf(entity("one"))
		val grown = base + entity("two")
		assertNotEquals(registryFingerprint(base), registryFingerprint(grown))
	}

	@Test
	fun `removing a plugin alters the fingerprint`() {
		val base = listOf(entity("one"), entity("two"))
		val shrunk = listOf(entity("one"))
		assertNotEquals(registryFingerprint(base), registryFingerprint(shrunk))
	}

	@Test
	fun `observable metadata changes alter the fingerprint`() {
		val renamed = entity("one", name = "Renamed")
		assertNotEquals(
			registryFingerprint(listOf(entity("one"))),
			registryFingerprint(listOf(renamed)),
		)
		assertNotEquals(
			registryFingerprint(listOf(entity("one", version = "1.0.0"))),
			registryFingerprint(listOf(entity("one", version = "2.0.0"))),
		)
	}

	@Test
	fun `js code changes alone do not alter the fingerprint`() {
		val old = listOf(entity("one", version = "1.0.0"))
		val patched = listOf(entity("one").copy(jsCode = "brand new code", updatedAt = 99L))
		// Same version bump semantics: reinstall with the same version is not a registry change.
		assertEquals(registryFingerprint(old), registryFingerprint(patched))
	}
}

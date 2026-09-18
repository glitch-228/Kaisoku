package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.*
import org.junit.Test

class ExtensionLanguageFilterTest {
	@Test fun codesAndNativeNamesMatch() {
		assertEquals("ru", ExtensionLanguageFilter.normalize("Русский"))
		assertEquals("en", ExtensionLanguageFilter.normalize("English"))
		assertEquals("pt-br", ExtensionLanguageFilter.normalize("pt_BR"))
		assertEquals("id", ExtensionLanguageFilter.normalize("Bahasa Indonesia"))
	}
	@Test fun multilingualPackagesMatchTheirIndividualSources() {
		assertTrue(ExtensionLanguageFilter.matches(setOf("ru"), listOf("all", "en", "ru")))
		assertFalse(ExtensionLanguageFilter.matches(setOf("ru"), listOf("all", "en")))
		assertTrue(ExtensionLanguageFilter.matches(emptySet(), listOf("und")))
	}
}

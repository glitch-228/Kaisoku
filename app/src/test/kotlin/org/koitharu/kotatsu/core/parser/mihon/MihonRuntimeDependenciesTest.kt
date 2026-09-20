package org.koitharu.kotatsu.core.parser.mihon

import org.junit.Assert.assertNotNull
import org.junit.Test

class MihonRuntimeDependenciesTest {

	@Test
	fun okhttpZstdDecoderIsAvailableToHostedExtensions() {
		assertNotNull(Class.forName("com.squareup.zstd.okio.OkioZstd"))
	}
}

package org.koitharu.kotatsu.core.parser.mihon

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import okio.Source
import okio.Sink

class MihonRuntimeDependenciesTest {

	@Test
	fun okhttpZstdDecoderIsAvailableToHostedExtensions() {
		val bridge = Class.forName("com.squareup.zstd.okio.OkioZstd")
		assertEquals(Source::class.java, bridge.getMethod("zstdDecompress", Source::class.java).returnType)
		assertEquals(Sink::class.java, bridge.getMethod("zstdCompress", Sink::class.java).returnType)
		assertNotNull(Class.forName("okhttp3.zstd.Zstd"))
	}
}

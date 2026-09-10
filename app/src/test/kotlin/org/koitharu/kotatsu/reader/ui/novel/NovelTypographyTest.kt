package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTypographyTest {

	@Test
	fun `han and latin neighbours gain a space`() {
		assertEquals("汉x", "汉 x", spacingFor("汉x"))
		assertEquals("x汉", "x 汉", spacingFor("x汉"))
	}

	@Test
	fun `unrelated scripts are left alone`() {
		assertEquals("hello world", spacingFor("hello world"))
		assertEquals("テストx", spacingFor("テストx"))
	}

	@Test
	fun `code point ranges cover the unified ideographs block`() {
		// U+4E00 (一) .. U+9FFF (鿿) must all match the spacing rule
		assertEquals("一A", "一 A", spacingFor("一A"))
		assertEquals("鿿B", "鿿 B", spacingFor("鿿B"))
		// CJK Extension A and Compatibility Ideographs
		assertEquals("㐀C", "㐀 C", spacingFor("㐀C"))
		assertEquals("豈D", "豈 D", spacingFor("豈D"))
	}

	private fun spacingFor(line: String): String {
		// Mirrors the per-line pipeline in NovelTypography.optimizeChineseTypography without
		// pulling TextPaint-dependent settings into the unit test.
		val regex = java.util.regex.Pattern
			.compile("""([\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF])([A-Za-z0-9@#&])|([A-Za-z0-9@#&])([\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF])""")
			.toRegex()
		return line.replace(regex) { match ->
			val left = match.groupValues[1].ifBlank { match.groupValues[3] }
			val right = match.groupValues[2].ifBlank { match.groupValues[4] }
			"$left $right"
		}
	}

	@Test
	fun `source uses literal code point ranges`() {
		// The ICU regex on API <= 28 rejects Unicode script-name classes, and any use of them
		// would crash the object's <clinit> on those devices. Guard the fix at the source level
		// so the pattern cannot quietly come back.
		val userDir = System.getProperty("user.dir")
		val candidates = listOf(
			java.io.File(userDir, "src/main/kotlin/org/koitharu/kotatsu/reader/ui/novel/NovelTypography.kt"),
			java.io.File(userDir, "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/novel/NovelTypography.kt"),
		)
		val source = candidates.firstOrNull(java.io.File::exists)?.readText()
			?: error("NovelTypography.kt not found from $userDir")
		assertTrue("NovelTypography must not use script-name regex classes", !source.contains("\\p{Is"))
	}
}

package org.koitharu.kotatsu.details.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSynopsisTranslationCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun completeUnicodeOutputSurvivesRecreationAndReplacementIsAtomic() {
        val directory = temporary.newFolder()
        val key = "a".repeat(64)
        FileSynopsisTranslationCache(directory).write(key, "我的狐仙老婆\nمرحبا\n😀")
        assertEquals("我的狐仙老婆\nمرحبا\n😀", FileSynopsisTranslationCache(directory).read(key))
        FileSynopsisTranslationCache(directory).write(key, "Новый перевод")
        assertEquals("Новый перевод", FileSynopsisTranslationCache(directory).read(key))
        assertEquals(listOf("$key.bin"), directory.list()?.toList())
    }

    @Test
    fun invalidMagicTruncatedOrOversizedFilesAreCacheMisses() {
        val directory = temporary.newFolder()
        val key = "b".repeat(64)
        val file = File(directory, "$key.bin")
        val cache = FileSynopsisTranslationCache(directory)
        file.writeText("not a valid cache envelope")
        assertNull(cache.read(key))
        cache.write(key, "complete")
        file.writeBytes(file.readBytes().dropLast(1).toByteArray())
        assertNull(cache.read(key))
        file.writeBytes(ByteArray(SynopsisTranslationService.MAX_RESULT_BYTES + 9))
        assertNull(cache.read(key))
    }

    @Test
    fun entryCountAndByteLimitsEvictLeastRecentlyUsedOnlyOwnedFiles() {
        val directory = temporary.newFolder()
        var now = 10_000L
        val cache = FileSynopsisTranslationCache(directory, maxBytes = 100, maxEntries = 2) { now++ }
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        File(directory, "unrelated.file").writeText("unrelated")
        cache.write(a, "first")
        cache.write(b, "second")
        assertEquals("first", cache.read(a))
        cache.write(c, "third")
        assertNull(cache.read(b))
        assertEquals("first", cache.read(a))
        assertEquals("third", cache.read(c))
        assertEquals("unrelated", File(directory, "unrelated.file").readText())
        assertTrue(directory.listFiles().orEmpty().filter { it.extension == "bin" }.sumOf { it.length() } <= 100)
        cache.write(b, "x".repeat(70))
        assertEquals("x".repeat(70), cache.read(b))
        assertTrue(directory.listFiles().orEmpty().filter { it.extension == "bin" }.sumOf { it.length() } <= 100)
    }

    @Test
    fun invalidKeysCannotEscapePrivateCacheAndOversizedOutputDoesNotReplaceOldResult() {
        val directory = temporary.newFolder()
        val cache = FileSynopsisTranslationCache(directory)
        assertThrows(IllegalArgumentException::class.java) { cache.read("../outside") }
        assertThrows(IllegalArgumentException::class.java) { cache.write("../outside", "result") }
        val key = "c".repeat(64)
        cache.write(key, "old complete result")
        cache.write(key, "x".repeat(SynopsisTranslationService.MAX_RESULT_BYTES + 1))
        cache.write(key, " \n")
        assertEquals("old complete result", cache.read(key))
        assertFalse(directory.list().orEmpty().any { it.endsWith(".tmp") })
    }
}

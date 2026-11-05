package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HiddenPagesStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun page(hash: String, size: Long = 100, label: String = "T") = HiddenPage(hash, size, 1L, label)

    @Test
    fun aPageMatchesOnlyWhenBothContentHashAndSizeAreTheSame() {
        val store = HiddenPagesStore(File(folder.root, "hidden.json"))
        assertTrue(store.isEmpty())
        store.add(page("aa", 100))
        assertTrue(store.contains("aa", 100))
        assertFalse(store.contains("aa", 101))
        assertFalse(store.contains("ab", 100))
    }

    @Test
    fun hiddenPagesSurviveARestartAndCanBeShownAgain() {
        val file = File(folder.root, "hidden.json")
        HiddenPagesStore(file).apply { add(page("aa")); add(page("bb", label = "Other")) }
        val reopened = HiddenPagesStore(file)
        assertEquals(listOf("bb", "aa"), reopened.all().map { it.hash })
        reopened.remove(listOf("bb"))
        assertEquals(listOf("aa"), HiddenPagesStore(file).all().map { it.hash })
        reopened.clear()
        assertTrue(HiddenPagesStore(file).isEmpty())
    }

    @Test
    fun hidingTheSamePageAgainDoesNotDuplicateIt() {
        val store = HiddenPagesStore(File(folder.root, "hidden.json"))
        store.add(page("aa", label = "first"))
        store.add(page("aa", label = "second"))
        assertEquals(listOf("second"), store.all().map { it.label })
    }

    @Test
    fun theListIsBoundedAndKeepsTheNewest() {
        val store = HiddenPagesStore(File(folder.root, "hidden.json"))
        repeat(520) { store.add(page("h$it")) }
        assertEquals(500, store.all().size)
        assertTrue(store.contains("h519", 100))
        assertFalse(store.contains("h0", 100))
    }

    @Test
    fun anUnreadableFileIsTreatedAsNothingHidden() {
        val file = File(folder.root, "hidden.json").apply { writeText("not json") }
        val store = HiddenPagesStore(file)
        assertTrue(store.isEmpty())
        assertFalse(store.contains("aa", 100))
    }
}

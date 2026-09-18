package org.koitharu.kotatsu.local.data.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.core.exceptions.CbrImportException

class CbrImportTransactionTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun publishesOnlyCompletedConversionAndReplacesExistingFile() = runTest {
		val existing = temporaryFolder.newFile("book.cbz").apply { writeText("old") }
		val result = CbrImportTransaction.import(
			sourceName = "book.cbr",
			outputDir = temporaryFolder.root,
			copyInput = { it.writeText("rar") },
			converter = { input, output ->
				assertEquals("rar", input.readText())
				output.writeText("complete")
			},
		)

		assertEquals(existing, result)
		assertEquals("complete", result.readText())
		assertNoTemporaryFiles()
	}

	@Test
	fun conversionFailurePreservesExistingFileAndRemovesTemporaryFiles() = runTest {
		val existing = temporaryFolder.newFile("book.cbz").apply { writeText("old") }
		val error = runCatching {
			CbrImportTransaction.import(
				sourceName = "book.cbr",
				outputDir = temporaryFolder.root,
				copyInput = { it.writeText("rar") },
				converter = { _, output ->
					output.writeText("partial")
					throw CbrImportException(CbrImportException.Reason.CORRUPTED)
				},
			)
		}.exceptionOrNull()
		assertTrue(error is CbrImportException)
		assertEquals(CbrImportException.Reason.CORRUPTED, (error as CbrImportException).reason)

		assertEquals("old", existing.readText())
		assertNoTemporaryFiles()
	}

	@Test
	fun cancellationRemovesCopiedInput() = runTest {
		val error = runCatching {
			CbrImportTransaction.import(
				sourceName = "cancelled.CBR",
				outputDir = temporaryFolder.root,
				copyInput = {
					it.writeText("partial input")
					throw CancellationException("cancel")
				},
			)
		}.exceptionOrNull()
		assertTrue(error is CancellationException)
		assertNoTemporaryFiles()
	}

	@Test
	fun cancellationDuringConversionPreservesExistingFileAndRemovesTemporaryFiles() = runTest {
		val existing = temporaryFolder.newFile("book.cbz").apply { writeText("old") }
		val error = runCatching {
			CbrImportTransaction.import(
				sourceName = "book.cbr",
				outputDir = temporaryFolder.root,
				copyInput = { it.writeText("rar") },
				converter = { _, output ->
					output.writeText("partial")
					throw CancellationException("cancel conversion")
				},
			)
		}.exceptionOrNull()

		assertTrue(error is CancellationException)
		assertEquals("old", existing.readText())
		assertNoTemporaryFiles()
	}

	private fun assertNoTemporaryFiles() {
		val temporaryFiles = temporaryFolder.root.listFiles().orEmpty().filter { it.name.startsWith(".cbr-import-") }
		assertEquals(emptyList<java.io.File>(), temporaryFiles)
	}
}

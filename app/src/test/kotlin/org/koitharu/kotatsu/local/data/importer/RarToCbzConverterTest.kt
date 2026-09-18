package org.koitharu.kotatsu.local.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows
import org.koitharu.kotatsu.core.exceptions.CbrImportException
import java.io.File
import java.util.zip.ZipFile

class RarToCbzConverterTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun convertsRar4Rar5AndSolidArchives() {
		for (fixtureName in listOf("rar4.rar", "rar5.rar", "rar4-solid.rar", "rar5-solid.rar")) {
			val input = copyFixture(fixtureName)
			val output = temporaryFolder.newFile("$fixtureName.cbz")
			val result = RarToCbzConverter.convert(
				input = input,
				output = output,
				isImage = { true },
				isReadableImage = { _, _ -> true },
			)

			assertTrue("$fixtureName should contain files", result.imageCount > 0)
			assertTrue("$fixtureName should produce a CBZ", result.outputSize > 0L)
			ZipFile(output).use { zip ->
				val entries = zip.entries().asSequence().toList()
				assertEquals(result.imageCount, entries.size)
				assertTrue(entries.all { it.name.isNotBlank() && !it.isDirectory })
				assertTrue(entries.all { zip.getInputStream(it).use { stream -> stream.read() } >= 0 })
			}
		}
	}

	@Test
	fun convertsOnlyImagesFromComicArchives() {
		for (fixtureName in listOf("comic-rar5.rar", "comic-rar5-solid.rar")) {
			val output = temporaryFolder.newFile("$fixtureName.cbz")
			val result = RarToCbzConverter.convert(copyFixture(fixtureName), output)

			assertEquals(2, result.imageCount)
			ZipFile(output).use { zip ->
				val names = zip.entries().asSequence().map { it.name }.toSet()
				assertEquals(
					setOf(
						"Volume 1/Chapter 2/Page 01.png",
						"Volume 1/Chapter 2/Page 02.jpg",
					),
					names,
				)
				assertFalse("ComicInfo.xml must not be copied", names.contains("ComicInfo.xml"))
			}
		}
	}

	@Test
	fun writesPagesInNaturalOrder() {
		val output = temporaryFolder.newFile("ordered.cbz")
		RarToCbzConverter.convert(
			input = copyFixture("comic-rar5.rar"),
			output = output,
		)

		ZipFile(output).use { zip ->
			assertEquals(
				listOf(
					"Volume 1/Chapter 2/Page 01.png",
					"Volume 1/Chapter 2/Page 02.jpg",
				),
				zip.entries().asSequence().map { it.name }.toList(),
			)
		}
	}

	@Test
	fun rejectsImageExtensionWithUnreadableContentAndCleansPageTemp() {
		val error = assertCbrFailure("invalid-image.rar", validateImageContent = true)
		assertEquals(CbrImportException.Reason.CORRUPTED, error.reason)
		assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".cbr-page-") })
	}

	@Test
	fun defaultFilterRejectsArchiveWithoutImages() {
		val error = assertCbrFailure("rar4.rar")
		assertEquals(CbrImportException.Reason.EMPTY, error.reason)
	}

	@Test
	fun recognizesSupportedImageNames() {
		assertTrue(RarToCbzConverter.isSupportedImagePath("chapter/Page001.JPG"))
		assertTrue(RarToCbzConverter.isSupportedImagePath("page.webp"))
		assertFalse(RarToCbzConverter.isSupportedImagePath("ComicInfo.xml"))
		assertFalse(RarToCbzConverter.isSupportedImagePath("page.jpg.exe"))
	}

	@Test
	fun rejectsEncryptedArchive() {
		assertEquals(CbrImportException.Reason.ENCRYPTED, assertCbrFailure("encrypted.rar", isImage = { true }).reason)
	}

	@Test
	fun rejectsMultipartArchive() {
		assertEquals(CbrImportException.Reason.MULTIPART, assertCbrFailure("multipart.part1.rar", isImage = { true }).reason)
	}

	@Test
	fun rejectsTraversalAndLinks() {
		assertEquals(CbrImportException.Reason.UNSAFE, assertCbrFailure("parent-dir.rar", isImage = { true }).reason)
		assertEquals(CbrImportException.Reason.UNSAFE, assertCbrFailure("links-hostile.rar", isImage = { true }).reason)
	}

	@Test
	fun rejectsOversizedDictionary() {
		assertEquals(
			CbrImportException.Reason.TOO_LARGE_DICTIONARY,
			assertCbrFailure("oversized-dictionary.rar", isImage = { true }).reason,
		)
	}

	@Test
	fun reservesFreeSpaceAndRejectsOverflowedSizes() {
		val reserve = 16L * 1024L * 1024L
		assertTrue(RarToCbzConverter.hasEnoughSpace(1024L, reserve + 1024L))
		assertFalse(RarToCbzConverter.hasEnoughSpace(1025L, reserve + 1024L))
		assertFalse(RarToCbzConverter.hasEnoughSpace(Long.MAX_VALUE, Long.MAX_VALUE))
		assertFalse(RarToCbzConverter.hasEnoughSpace(-1L, Long.MAX_VALUE))
	}

	@Test
	fun rejectsCorruptArchive() {
		assertEquals(CbrImportException.Reason.CORRUPTED, assertCbrFailure("corrupt.rar", isImage = { true }).reason)
	}

	@Test
	fun pathValidatorPreservesSafeNestedPathsAndNormalizesSeparators() {
		val validator = ArchiveEntryPathValidator()
		assertEquals("Volume 1/Chapter 2/Page 01.jpg", validator.validate("Volume 1\\Chapter 2\\Page 01.jpg"))
	}

	@Test
	fun pathValidatorRejectsUnsafeAndDuplicatePaths() {
		for (path in listOf("/page.jpg", "C:/page.jpg", "../page.jpg", "chapter/../page.jpg", "a//page.jpg")) {
			val error = assertThrows(CbrImportException::class.java) {
				ArchiveEntryPathValidator().validate(path)
			}
			assertEquals(path, CbrImportException.Reason.UNSAFE, error.reason)
		}
		val validator = ArchiveEntryPathValidator()
		validator.validate("Chapter/Page.jpg")
		val duplicate = assertThrows(CbrImportException::class.java) {
			validator.validate("chapter/page.JPG")
		}
		assertEquals(CbrImportException.Reason.UNSAFE, duplicate.reason)
	}

	private fun assertCbrFailure(
		fixtureName: String,
		isImage: (String) -> Boolean = RarToCbzConverter::isSupportedImagePath,
		validateImageContent: Boolean = false,
	): CbrImportException {
		val input = copyFixture(fixtureName)
		val output = temporaryFolder.newFile("$fixtureName.failed.cbz")
		return assertThrows(CbrImportException::class.java) {
			if (validateImageContent) {
				RarToCbzConverter.convert(input = input, output = output, isImage = isImage)
			} else {
				RarToCbzConverter.convert(
					input = input,
					output = output,
					isImage = isImage,
					isReadableImage = { _, _ -> true },
				)
			}
		}
	}

	private fun copyFixture(name: String): File {
		val destination = temporaryFolder.newFile("fixture-$name")
		val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("cbr/$name")) {
			"Missing test fixture: $name"
		}
		resource.use { input -> destination.outputStream().use(input::copyTo) }
		return destination
	}
}

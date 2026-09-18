package org.koitharu.kotatsu.local.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object CbrImportTransaction {

	suspend fun import(
		sourceName: String,
		outputDir: File,
		copyInput: suspend (File) -> Unit,
		converter: (File, File) -> Unit = { input, output -> RarToCbzConverter.convert(input, output) },
	): File {
		val outputName = sourceName.safeLeafName()
			.substringBeforeLast('.', missingDelimiterValue = "")
			.takeIf { it.isNotBlank() }
			?.let { "$it.cbz" }
			?: throw UnsupportedFileException("Invalid CBR file name: $sourceName")
		val destination = File(outputDir, outputName)
		val operationId = UUID.randomUUID().toString()
		val inputTemp = File(outputDir, ".cbr-import-$operationId.rar.part")
		val outputTemp = File(outputDir, ".cbr-import-$operationId.cbz.part")
		try {
			copyInput(inputTemp)
			runInterruptible(Dispatchers.IO) {
				converter(inputTemp, outputTemp)
				publish(outputTemp, destination)
			}
			return destination
		} finally {
			inputTemp.delete()
			outputTemp.delete()
		}
	}

	private fun publish(source: File, destination: File) {
		try {
			Files.move(
				source.toPath(),
				destination.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun String.safeLeafName(): String = substringAfterLast('/').substringAfterLast('\\')
}

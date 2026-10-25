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
        converter: (File, File) -> Unit = ::convertArchive,
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

    /** Some comic downloads have a RAR extension but contain a ZIP, including encrypted ZIPs. */
    private fun convertArchive(input: File, output: File) {
        val signature = input.inputStream().use { stream ->
            val bytes = ByteArray(4)
            if (stream.read(bytes) == bytes.size) bytes else byteArrayOf()
        }
        val zip = signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) ||
            signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06)) ||
            signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x07, 0x08))
        if (!zip) {
            RarToCbzConverter.convert(input, output)
            return
        }
        if (!net.lingala.zip4j.ZipFile(input).use { it.isValidZipFile }) {
            throw org.koitharu.kotatsu.core.exceptions.CbrImportException(
                org.koitharu.kotatsu.core.exceptions.CbrImportException.Reason.CORRUPTED,
            )
        }
        // Copy ciphertext unchanged. ArchiveAccess performs normal comic/session validation later.
        input.inputStream().use { source ->
            output.outputStream().use { destination ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
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
